package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.util.List;

/**
 * Шины и диски: своя вкладка, свои свойства, общий склад с запчастями.
 *
 * <p>Заводятся комплектом — на разборке снимают четыре колеса разом,
 * и повторять двенадцать полей четырежды никто не станет. Продаются
 * поштучно: запаску берут по одной.
 */
@RestController
@RequestMapping("/api/wheels")
public class WheelController {

    private static final String INTAKES = "hasAnyRole('OWNER','MANAGER','STOREKEEPER')";

    private final WheelService wheels;

    public WheelController(WheelService wheels) {
        this.wheels = wheels;
    }

    @GetMapping
    public List<WheelService.WheelRow> list(@RequestParam(defaultValue = "200") int limit) {
        return wheels.list(Math.min(limit, 500));
    }

    @PostMapping("/sets")
    @PreAuthorize(INTAKES)
    public ResponseEntity<WheelService.Created> createSet(@Valid @RequestBody SetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wheels.createSet(
                new WheelService.WheelRequest(
                        request.kind(), request.diameter(),
                        request.tyreWidth(), request.tyreHeight(), request.construction(),
                        request.tyreType(), request.season(), request.wearMm(),
                        request.madeYear(),
                        request.discType(), request.discWidth(), request.offsetMm(),
                        request.boltPattern(), request.hubBore(),
                        request.brand(), request.model(),
                        request.price(), request.costPrice(), request.condition()),
                request.quantity(), request.warehouseId(), CurrentUser.memberId()));
    }

    /**
     * @param quantity сколько колёс в комплекте: обычно четыре, но продают
     *                 и одну запаску
     * @param wearMm   остаток протектора в миллиметрах
     */
    public record SetRequest(@NotBlank String kind,
                             @NotNull Long warehouseId,
                             int quantity,
                             BigDecimal diameter,
                             Integer tyreWidth, Integer tyreHeight, String construction,
                             String tyreType, String season, BigDecimal wearMm,
                             Integer madeYear,
                             String discType, BigDecimal discWidth, Integer offsetMm,
                             String boltPattern, BigDecimal hubBore,
                             String brand, String model,
                             BigDecimal price, BigDecimal costPrice, String condition) {
    }
}
