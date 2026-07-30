package ru.partsflow.platform.organization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST структуры склада.
 *
 * <p>Заводит владелец: филиал и склад — это про деньги и отчёты, а ячейку,
 * заведённую случайно, потом ищут по всему стеллажу. Смотреть может любой
 * вошедший — без списка складов не работает ни приёмка, ни продажа.
 */
@RestController
@RequestMapping("/api/organization")
public class OrganizationController {

    private static final String MANAGES = "hasAnyRole('OWNER','MANAGER')";

    private final OrganizationService organization;

    public OrganizationController(OrganizationService organization) {
        this.organization = organization;
    }

    @GetMapping("/branches")
    public List<OrganizationService.Branch> branches() {
        return organization.branches();
    }

    @PostMapping("/branches")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<OrganizationService.Branch> createBranch(
            @Valid @RequestBody NameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(organization.createBranch(request.name()));
    }

    @GetMapping("/warehouses")
    public List<OrganizationService.Warehouse> warehouses() {
        return organization.warehouses();
    }

    /** {@code branchId} можно не указывать, пока филиал один. */
    @PostMapping("/warehouses")
    @PreAuthorize(MANAGES)
    public ResponseEntity<OrganizationService.Warehouse> createWarehouse(
            @Valid @RequestBody WarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(organization.createWarehouse(request.branchId(), request.name()));
    }

    @GetMapping("/warehouses/{id}/cells")
    public List<OrganizationService.Cell> cells(@PathVariable long id) {
        return organization.cells(id);
    }

    /**
     * Заводит ячейки списком.
     *
     * <p>Стеллаж — это два-три десятка адресов подряд, и по одному их никто
     * заводить не станет: коды уедут в примечание, а поиск детали на полке
     * вернётся к памяти кладовщика.
     */
    @PostMapping("/warehouses/{id}/cells")
    @PreAuthorize(MANAGES)
    public ResponseEntity<List<OrganizationService.Cell>> createCells(
            @PathVariable long id, @Valid @RequestBody CellsRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(organization.createCells(id, request.codes(), request.zone()));
    }

    public record NameRequest(@NotBlank String name) {
    }

    public record WarehouseRequest(@NotBlank String name, Long branchId) {
    }

    public record CellsRequest(@NotEmpty List<String> codes, String zone) {
    }
}
