package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping
    public ResponseEntity<PartView> intake(@Valid @RequestBody IntakeRequest request) {
        Part part = new Part(request.categoryId(), request.title(), request.price());
        part.setDescription(request.description());
        part.setDonorId(request.donorId());
        if (request.condition() != null) {
            part.setCondition(request.condition());
        }

        Part saved = partService.intake(
                part,
                request.quantity() == null ? BigDecimal.ONE : request.quantity(),
                request.storageCellId());

        return ResponseEntity.status(HttpStatus.CREATED).body(PartView.of(saved));
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

    public record IntakeRequest(
            @NotNull Long categoryId,
            @NotBlank String title,
            String description,
            Long donorId,
            PartCondition condition,
            @Positive BigDecimal price,
            BigDecimal quantity,
            Long storageCellId
    ) {
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
