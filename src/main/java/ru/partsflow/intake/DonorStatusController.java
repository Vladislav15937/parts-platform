package ru.partsflow.intake;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Настройка «Статусы машин» (задача 0364): список ведёт тот, кто ведёт
 * настройки компании, — право то же, что у вкладки «Настройки»
 * ({@code settings.company}). Выбирают статус в форме машины все, кто
 * машину заводит: значения приходят словарём формы
 * ({@code GET /api/intake/donor-vocabulary}), а не отсюда.
 */
@RestController
@RequestMapping("/api/intake/donor-statuses")
@PreAuthorize("hasAuthority('settings.company')")
public class DonorStatusController {

    private final DonorStatusService statuses;

    public DonorStatusController(DonorStatusService statuses) {
        this.statuses = statuses;
    }

    @GetMapping
    public List<DonorStatusService.StatusView> list() {
        return statuses.list();
    }

    @PostMapping
    public List<DonorStatusService.StatusView> create(@RequestBody NameRequest request) {
        return statuses.create(request.name());
    }

    @PutMapping("/{id}")
    public List<DonorStatusService.StatusView> rename(@PathVariable long id,
                                                      @RequestBody NameRequest request) {
        return statuses.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    public List<DonorStatusService.StatusView> delete(@PathVariable long id) {
        return statuses.delete(id);
    }

    public record NameRequest(String name) {
    }
}
