package ru.partsflow.platform.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Настройки компании — экран «Настройки», владелец.
 *
 * <p><b>Читает тоже только владелец</b>, в отличие от справочников источников
 * рядом: те продавец выбирает при каждой оплате, а срок резерва он не выбирает
 * никогда — сервер подставляет его сам. Критерий приёмки задачи 0049 говорит
 * прямо: «роли, кроме владельца, настройку не видят и не правят», и открытое
 * чтение было бы ровно тем, что задача запрещает.
 *
 * <p>PUT, а не PATCH: настройка отправляется целиком той же формой, которая
 * её показала.
 */
@RestController
@RequestMapping("/api/company/settings")
public class CompanySettingsController {

    private static final String OWNER = "hasRole('OWNER')";

    private final CompanySettingsService settings;

    public CompanySettingsController(CompanySettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize(OWNER)
    public CompanySettingsService.CompanySettings read() {
        return settings.read();
    }

    @PutMapping
    @PreAuthorize(OWNER)
    public CompanySettingsService.CompanySettings write(@Valid @RequestBody UpdateRequest request) {
        return settings.update(request.reservationDays());
    }

    /**
     * Число, а не строка с единицей: «дня» — слово экрана, а не значение.
     * Границы проверяет служба, чтобы одно и то же правило не разъехалось
     * между аннотацией и {@code CHECK} в схеме.
     */
    public record UpdateRequest(@NotNull Integer reservationDays) {
    }
}
