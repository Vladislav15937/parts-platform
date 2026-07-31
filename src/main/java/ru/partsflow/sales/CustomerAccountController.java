package ru.partsflow.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;

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

    private final SalesService sales;

    public CustomerAccountController(SalesService sales) {
        this.sales = sales;
    }

    @PostMapping("/top-up")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','SELLER')")
    public ResponseEntity<EntryView> topUp(@PathVariable Long customerId,
                                           @Valid @RequestBody TopUpRequest request) {
        CustomerAccountEntry entry = sales.topUpAccount(
                customerId, request.amount(), request.paymentSourceId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryView.of(entry));
    }

    public record TopUpRequest(@NotNull @Positive BigDecimal amount, Long paymentSourceId) {
    }

    public record EntryView(Long id, Long customerId, AccountEntryType entryType,
                            BigDecimal amount, Instant createdAt) {

        static EntryView of(CustomerAccountEntry entry) {
            return new EntryView(entry.getId(), entry.getCustomerId(), entry.getEntryType(),
                    entry.getAmount(), entry.getCreatedAt());
        }
    }
}
