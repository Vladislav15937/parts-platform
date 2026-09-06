package ru.partsflow.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;
import ru.partsflow.platform.security.MemberService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Лицевой счёт клиента.
 *
 * <p>Отдельным контроллером, а не в {@code SalesController}: счёт принадлежит
 * клиенту, а не сделке, и живёт дольше любой из них. Туда же уходит переплата
 * по сделке — на разборке обычное дело: округлили вверх, отдали лишнюю тысячу,
 * забрали в следующий приезд.
 */
@RestController
@RequestMapping("/api/customers/{customerId}/account")
public class CustomerAccountController {

    /** Счётом распоряжается тот, кто продаёт: это деньги в сделке. */
    private static final String SELLS = "hasAnyRole('OWNER','MANAGER','SELLER')";

    private final SalesService sales;
    private final DealRepository deals;
    private final MemberService members;

    public CustomerAccountController(SalesService sales, DealRepository deals,
                                     MemberService members) {
        this.sales = sales;
        this.deals = deals;
        this.members = members;
    }

    /**
     * Остаток и журнал операций — целиком, не восемь последних: вкладка
     * «Движения по счёту» карточки клиента (задача 0022) показывает весь
     * журнал, а прежде экран продавца обрезал его сам ({@code slice(0, 8)}
     * в {@code SellerScreen}). Здесь по-прежнему нет предела — счёт одного
     * клиента не дорастает до тысяч строк, в отличие от склада целиком.
     *
     * <p>Имя автора и номер сделки резолвятся одним запросом на всю выдачу,
     * как и наименования в других реестрах: «автор 3» и «сделка 7» вместо
     * имени и номера документа ничего не говорят тому, кто их разбирает.
     */
    @GetMapping
    @PreAuthorize(SELLS)
    public AccountView account(@PathVariable Long customerId) {
        List<CustomerAccountEntry> entries = sales.accountEntries(customerId);
        Map<Long, String> authorNames = members.namesOf(
                entries.stream().map(CustomerAccountEntry::getCreatedBy).toList());
        Map<Long, Long> dealNumbers = deals.findAllById(
                        entries.stream().map(CustomerAccountEntry::getDealId)
                                .filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Deal::getId, Deal::getNumber));
        return new AccountView(
                customerId,
                sales.accountBalance(customerId),
                entries.stream().map(e -> EntryView.of(e, authorNames, dealNumbers)).toList());
    }

    @PostMapping("/top-up")
    @PreAuthorize(SELLS)
    public ResponseEntity<EntryView> topUp(@PathVariable Long customerId,
                                           @Valid @RequestBody TopUpRequest request) {
        CustomerAccountEntry entry = sales.topUpAccount(
                customerId, request.amount(), request.paymentSourceId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryView.of(entry));
    }

    /**
     * @param balance остаток. Считается по журналу, а не хранится полем:
     *                хранимый разъедется с журналом на первой же правке
     */
    public record AccountView(Long customerId, BigDecimal balance, List<EntryView> entries) {
    }

    /**
     * Выдача со счёта наличными.
     *
     * <p>Отдельно от зачёта: там деньги остаются у нас и меняют назначение,
     * здесь уходят из кассы — и платёж создаётся, иначе касса не сойдётся.
     */
    @PostMapping("/withdraw")
    @PreAuthorize(SELLS)
    public ResponseEntity<EntryView> withdraw(@PathVariable Long customerId,
                                              @Valid @RequestBody TopUpRequest request) {
        CustomerAccountEntry entry = sales.withdrawFromAccount(
                customerId, request.amount(), request.paymentSourceId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryView.of(entry));
    }

    /**
     * Ручная правка остатка — владельцу и менеджеру, не продавцу.
     *
     * <p>Остальные операции по счёту продавец делает сам: они опираются
     * на факт — принял деньги, выдал, зачёл в сделку. Правка ни на что
     * не опирается, кроме решения, и отвечать за неё должен тот, кто отвечает
     * за деньги.
     */
    @PostMapping("/correct")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<EntryView> correct(@PathVariable Long customerId,
                                             @Valid @RequestBody CorrectionRequest request) {
        CustomerAccountEntry entry = sales.correctAccount(
                customerId, request.amount(), request.reason(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryView.of(entry));
    }

    /**
     * @param amount со знаком: правка бывает в обе стороны
     * @param reason почему. Без неё через месяц правку не отличить от ошибки
     */
    public record CorrectionRequest(@NotNull BigDecimal amount,
                                    @jakarta.validation.constraints.NotBlank String reason) {
    }

    public record TopUpRequest(@NotNull @Positive BigDecimal amount, Long paymentSourceId) {
    }

    /**
     * @param signedAmount сумма со знаком: журнал читают глазами, и «−500»
     *                     понятнее, чем «500, тип DEAL_PAYMENT»
     * @param dealNumber   номер сделки, к которой относится движение; пусто —
     *                     движение не связано со сделкой (пополнение, выдача,
     *                     правка)
     * @param authorName   кто сделал движение; пусто — фоновый процесс,
     *                     у которого автора нет и быть не может
     */
    public record EntryView(Long id, Long customerId, AccountEntryType entryType,
                            BigDecimal amount, BigDecimal signedAmount,
                            String comment, Instant createdAt,
                            Long dealNumber, String authorName) {

        /** Для одиночной записи сразу после операции — имя и номер сделки не нужны там. */
        static EntryView of(CustomerAccountEntry entry) {
            return of(entry, Map.of(), Map.of());
        }

        static EntryView of(CustomerAccountEntry entry, Map<Long, String> authorNames,
                            Map<Long, Long> dealNumbers) {
            // Ключ поиска, а не только карта, может быть пуст: у пополнения,
            // выдачи и правки сделки нет вовсе. Map.of() (в отличие от HashMap)
            // бросает NullPointerException уже на попытке найти null-ключ —
            // поэтому проверка идёт до обращения к карте, а не полагается
            // на то, что там лежит пустая карта конкретной реализации.
            Long dealId = entry.getDealId();
            Long authorId = entry.getCreatedBy();
            return new EntryView(entry.getId(), entry.getCustomerId(), entry.getEntryType(),
                    entry.getAmount(), entry.signedAmount(),
                    entry.getComment(), entry.getCreatedAt(),
                    dealId == null ? null : dealNumbers.get(dealId),
                    authorId == null ? null : authorNames.get(authorId));
        }
    }
}
