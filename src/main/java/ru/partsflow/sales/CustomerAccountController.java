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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

    public CustomerAccountController(SalesService sales) {
        this.sales = sales;
    }

    /**
     * Остаток и журнал операций.
     *
     * <p>Пока читать счёт было нечем, переплата уходила на него молча:
     * деньги клиента в системе есть, а увидеть их продавец не мог — то есть
     * при следующем приезде клиент про свою тысячу помнил, а система нет.
     */
    @GetMapping
    @PreAuthorize(SELLS)
    public AccountView account(@PathVariable Long customerId) {
        return new AccountView(
                customerId,
                sales.accountBalance(customerId),
                sales.accountEntries(customerId).stream().map(EntryView::of).toList());
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

    public record TopUpRequest(@NotNull @Positive BigDecimal amount, Long paymentSourceId) {
    }

    /**
     * @param signedAmount сумма со знаком: журнал читают глазами, и «−500»
     *                     понятнее, чем «500, тип DEAL_PAYMENT»
     */
    public record EntryView(Long id, Long customerId, AccountEntryType entryType,
                            BigDecimal amount, BigDecimal signedAmount,
                            String comment, Instant createdAt) {

        static EntryView of(CustomerAccountEntry entry) {
            return new EntryView(entry.getId(), entry.getCustomerId(), entry.getEntryType(),
                    entry.getAmount(), entry.signedAmount(),
                    entry.getComment(), entry.getCreatedAt());
        }
    }
}
