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
 * REST справочника источников сделок — экран «Настройки».
 *
 * <p>Отдельно от {@code GET /api/deals/sources}: тот отдаёт только активные
 * источники для выпадающего списка при продаже, а здесь — полный список
 * с архивными для таблицы настроек, плюс заведение и архивация. Чтение —
 * любой вошедший, запись — владелец, как и у источников платежей.
 */
@RestController
@RequestMapping("/api/deal-sources")
public class DealSourceController {

    private final DealSourceService sources;

    public DealSourceController(DealSourceService sources) {
        this.sources = sources;
    }

    @GetMapping
    public List<DealSourceService.DealSourceEntryView> list() {
        return sources.list();
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<DealSourceService.DealSourceEntryView> create(
            @Valid @RequestBody CreateRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(sources.create(request.name()));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    DealSourceService.duplicateMessage(requireStripped(request.name())));
        }
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('OWNER')")
    public DealSourceService.DealSourceEntryView archive(@PathVariable Long id) {
        return sources.archive(id);
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasRole('OWNER')")
    public DealSourceService.DealSourceEntryView unarchive(@PathVariable Long id) {
        return sources.unarchive(id);
    }

    private static String requireStripped(String name) {
        return name == null ? "" : name.strip();
    }

    public record CreateRequest(@NotBlank String name) {
    }
}
