package ru.partsflow.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST клиентов.
 *
 * <p>Минимум, без которого продавать нельзя: найти позвонившего по телефону
 * и завести нового прямо в разговоре. Полная карточка с историей, скидками
 * и лицевым счётом — отдельная задача; тормозить из-за неё оформление сделки
 * незачем.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    public List<CustomerService.Customer> search(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return customers.search(query, limit);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','SELLER')")
    public ResponseEntity<CustomerService.Customer> create(
            @Valid @RequestBody CreateRequest request) {

        CustomerService.Customer created = customers.create(
                request.name(), request.phone(), request.email(), request.customerType());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    public record CreateRequest(@NotBlank String name, String phone, String email,
                                String customerType) {
    }
}
