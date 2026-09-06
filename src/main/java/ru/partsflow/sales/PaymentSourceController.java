package ru.partsflow.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
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
 * REST источников платежей — экран «Настройки».
 *
 * <p>Чтение — любой вошедший: список нужен продавцу, чтобы выбрать способ
 * оплаты. Запись — владелец: способы приёма денег определяют, как разборка
 * сводит кассу, и это решение не продавца.
 */
@RestController
@RequestMapping("/api/payment-sources")
public class PaymentSourceController {

    private final PaymentSourceService sources;

    public PaymentSourceController(PaymentSourceService sources) {
        this.sources = sources;
    }

    @GetMapping
    public List<PaymentSourceService.PaymentSourceView> list() {
        return sources.list();
    }

    /**
     * Заводит источник.
     *
     * <p>Проверка чтением внутри сервиса ловит обычный повтор; на
     * одновременный отвечает здесь же нарушение уникального индекса —
     * тем же текстом, что и проактивная проверка, а не «Операция нарушает
     * целостность данных».
     */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<PaymentSourceService.PaymentSourceView> create(
            @Valid @RequestBody CreateRequest request) {
        try {
            PaymentSourceService.PaymentSourceView created =
                    sources.create(request.name(), request.sourceType());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    PaymentSourceService.duplicateMessage(requireStripped(request.name())));
        }
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('OWNER')")
    public PaymentSourceService.PaymentSourceView archive(@PathVariable Long id) {
        return sources.archive(id);
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasRole('OWNER')")
    public PaymentSourceService.PaymentSourceView unarchive(@PathVariable Long id) {
        return sources.unarchive(id);
    }

    private static String requireStripped(String name) {
        return name == null ? "" : name.strip();
    }

    /** @param sourceType одно из пяти значений схемы; пусто — «Не указан» */
    public record CreateRequest(@NotBlank String name, String sourceType) {
    }
}
