package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST для запчастей. Арендатор берётся из контекста (см. {@code TenantFilter}),
 * в путях и телах запросов его нет — иначе рано или поздно кто-то подставит чужой.
 */
@RestController
@RequestMapping("/api/parts")
public class PartController {

    private final PartService partService;

    public PartController(PartService partService) {
        this.partService = partService;
    }

    @GetMapping("/search")
    public List<PartView> search(@RequestParam("q") String query,
                                 @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return partService.search(query, limit).stream().map(PartView::of).toList();
    }

    /**
     * Поиск для продавца: свободный остаток по складам.
     *
     * <p>Отдельно от {@code /search}: тот отдаёт карточку с общим остатком,
     * а продавать можно только то, что не обещано другому клиенту.
     */
    @GetMapping("/stock")
    public List<PartService.StockRow> stock(@RequestParam("q") String query,
                                            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return partService.searchAvailable(query, limit);
    }

    @GetMapping("/by-oem/{number}")
    public List<PartView> byOem(@PathVariable String number) {
        return partService.findByOem(number).stream().map(PartView::of).toList();
    }

    /**
     * Включить или выключить выгрузку на площадки.
     *
     * <p>Списком: после импорта склада исключений набирается несколько сотен.
     * По умолчанию принятая деталь публикуется — на разборке её для того
     * и снимают, а «не выгружать» это отметка руками для битых и отложенных
     * под заказ.
     */
    @PostMapping("/publication")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER','SELLER')")
    public PublicationResult setPublication(@Valid @RequestBody PublicationRequest request) {
        return new PublicationResult(
                partService.setPublished(request.partIds(), request.published()));
    }

    public record PublicationRequest(@NotEmpty List<Long> partIds, boolean published) {
    }

    public record PublicationResult(int changed) {
    }

    public record PartView(
            Long id,
            String publicCode,
            String title,
            BigDecimal price,
            BigDecimal qtyOnHand,
            PartStatus status
    ) {
        static PartView of(Part part) {
            return new PartView(part.getId(), part.getPublicCode(), part.getTitle(),
                    part.getPrice(), part.getQtyOnHand(), part.getStatus());
        }
    }
}
