package ru.partsflow.inventory;

import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/by-oem/{number}")
    public List<PartView> byOem(@PathVariable String number) {
        return partService.findByOem(number).stream().map(PartView::of).toList();
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
