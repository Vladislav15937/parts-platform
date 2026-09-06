package ru.partsflow.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST клиентов.
 *
 * <p>Долго был минимумом, без которого продавать нельзя: найти позвонившего
 * по телефону и завести нового прямо в разговоре ({@link #search}/{@link #create}).
 * Раздел «Клиенты» добавил карточку с историей и правкой ({@link #directory},
 * {@link #get}, {@link #update}) — до неё поля {@code email}, {@code note},
 * {@code public_note}, {@code inn} и {@code company_name} лежали в схеме
 * и были недоступны человеку: заполнить их было нечем.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    /**
     * Кто видит раздел «Клиенты»: тот же список, что продаёт. Кладовщик
     * и «Просмотр» видят клиента только через чужую сделку — отдельного
     * экрана истории и лицевого счёта у них нет.
     */
    private static final String READS = "hasAnyRole('OWNER','MANAGER','SELLER')";

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

    /**
     * Раздел «Клиенты»: список с балансом и поиском.
     *
     * <p>Путь отдельный от {@link #search}, а не тот же с новым параметром:
     * {@link #search} зовёт {@code CustomerPicker} на каждое нажатие клавиши,
     * и его контракт (простой список без баланса) менять нельзя, не тронув
     * форму продажи.
     */
    @GetMapping("/directory")
    @PreAuthorize(READS)
    public CustomerService.CustomersPage directory(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return customers.directory(query, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize(READS)
    public CustomerService.CustomerDetail get(@PathVariable Long id) {
        return customers.getDetail(id);
    }

    /**
     * Правка карточки — владельцу и менеджеру, не продавцу: телефон в чужой
     * сделке поправить продавец не должен.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public CustomerService.CustomerDetail update(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateRequest request) {
        return customers.update(id, request.name(), request.phone(), request.email(),
                request.publicNote(), request.note(), request.customerType(),
                request.inn(), request.companyName());
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

    /**
     * @param publicNote примечание клиенту — печатается в накладной
     * @param note       заметка для себя — нигде не выводится
     */
    public record UpdateRequest(@NotBlank String name, String phone, String email,
                                String publicNote, String note, String customerType,
                                String inn, String companyName) {
    }
}
