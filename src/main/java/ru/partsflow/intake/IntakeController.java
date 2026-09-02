package ru.partsflow.intake;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.Part;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.QualityGrade;
import ru.partsflow.inventory.VerticalSide;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * REST приёмки. Арендатор берётся из контекста (см. {@code TenantFilter}),
 * в путях и телах его нет — иначе рано или поздно кто-то подставит чужой.
 *
 * <p>Приёмка партией, а не по одной детали: с одного донора снимают десятки
 * позиций подряд, и телефон в ангаре теряет связь между запросами. Один запрос
 * на партию — это одна повторяемая операция вместо тридцати незавершённых.
 */
@RestController
@RequestMapping("/api/intake")
public class IntakeController {

    private final IntakeService intake;
    private final IntakeReferenceService reference;
    private final DonorCostService costs;
    private final DonorDirectory donorDirectory;

    public IntakeController(IntakeService intake, IntakeReferenceService reference,
                            DonorCostService costs,
                            DonorDirectory donorDirectory) {
        this.intake = intake;
        this.reference = reference;
        this.costs = costs;
        this.donorDirectory = donorDirectory;
    }

    /**
     * Справочники для работы без связи: склады с ячейками, поставки, машины
     * в разборе, наименования для подсказок.
     *
     * <p>Один запрос, а не пять: телефон забирает это перед выходом к стеллажам,
     * и пять запросов по плохой связи означают пять шансов оборваться и пять
     * частично заполненных кэшей, из которых непонятно, можно ли работать.
     */
    @GetMapping("/reference")
    public IntakeReferenceService.Reference reference() {
        return reference.load();
    }

    // ---------- поставки ----------

    @PostMapping("/supplies")
    public ResponseEntity<SupplyView> registerSupply(@Valid @RequestBody SupplyRequest request) {
        Supply supply = intake.registerSupply(
                request.kind(), request.number(), request.supplierName(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SupplyView.of(supply));
    }

    @PostMapping("/supplies/{id}/arrived")
    public SupplyView markArrived(@PathVariable Long id,
                                  @RequestParam(required = false) LocalDate on) {
        return SupplyView.of(intake.markSupplyArrived(id, on));
    }

    // ---------- доноры ----------

    @PostMapping("/donors")
    public ResponseEntity<DonorView> registerDonor(@Valid @RequestBody DonorRequest request) {
        Donor donor = new Donor(request.brandId());
        donor.setModelId(request.modelId());
        donor.setGenerationId(request.generationId());
        donor.setModificationId(request.modificationId());
        donor.setVin(request.vin());
        donor.setYear(request.year());
        donor.setColor(request.color());
        donor.setColorCode(request.colorCode());
        donor.setBodyCode(request.bodyCode());
        donor.setEngineCode(request.engineCode());
        donor.setMileageKm(request.mileageKm());
        donor.setSteering(request.steering());
        donor.setDriveType(request.driveType());
        donor.setTransmissionType(request.transmissionType());
        donor.setTransmissionModel(request.transmissionModel());
        donor.setNote(request.note());

        Donor saved = intake.registerDonor(donor, request.supplyId(), CurrentUser.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).body(DonorView.of(saved));
    }

    /**
     * Все машины, включая ещё не поставленные в разбор.
     *
     * <p>Справочник приёмки отдаёт только те, что в разборе, — и правильно
     * делает. Но заведённая машина должна быть видна тому, кто её завёл:
     * иначе она пропадает из системы до тех пор, пока кто-то не догадается
     * поставить её в разбор запросом к API.
     */
    @GetMapping("/donors")
    public List<DonorDirectory.Entry> donors() {
        return donorDirectory.all();
    }

    /**
     * Затраты по машине: сколько в неё вложено и из чего это сложилось.
     *
     * <p>Отсюда считает отчёт окупаемости. Пока писать сюда было нечем,
     * он честно показывал «вложено 0 ₽» — ему нечего было читать.
     */
    @GetMapping("/donors/{id}/costs")
    public List<DonorCostService.Cost> costs(@PathVariable Long id) {
        return costs.of(id);
    }

    @PostMapping("/donors/{id}/costs")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<DonorCostService.Cost> addCost(@PathVariable Long id,
                                               @Valid @RequestBody CostRequest request) {
        return costs.add(id, request.type(), request.amount(), request.incurredOn(),
                request.note(), CurrentUser.memberId());
    }

    @DeleteMapping("/donors/{id}/costs/{costId}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<DonorCostService.Cost> removeCost(@PathVariable Long id,
                                                  @PathVariable Long costId) {
        return costs.remove(id, costId);
    }

    /** @param incurredOn пусто — сегодня */
    public record CostRequest(@jakarta.validation.constraints.NotBlank String type,
                              @jakarta.validation.constraints.NotNull java.math.BigDecimal amount,
                              java.time.LocalDate incurredOn,
                              String note) {
    }

    /** Локация в логистической цепочке. Стадию разбора не двигает. */
    @PostMapping("/donors/{id}/location")
    public DonorView moveDonor(@PathVariable Long id, @RequestParam String location) {
        return DonorView.of(intake.moveDonor(id, location));
    }

    @PostMapping("/donors/{id}/dismantling")
    public DonorView startDismantling(@PathVariable Long id) {
        return DonorView.of(intake.startDismantling(id));
    }

    // ---------- приёмка деталей ----------

    @PostMapping("/receipts")
    public ResponseEntity<ReceiptView> receive(@Valid @RequestBody ReceiptRequest request) {
        List<IntakeService.ItemRequest> items = request.items().stream()
                .map(ItemRequest::toService)
                .toList();

        IntakeService.Receipt receipt;
        try {
            receipt = intake.receive(
                    request.warehouseId(), request.supplyId(), request.donorId(),
                    items, CurrentUser.memberId(), request.requestId());
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // Одновременный повтор: первый запрос успел вставить документ,
            // второй упёрся в уникальный ключ. Приёмка при этом прошла,
            // и очередь ждёт результат — иначе она уведёт запись
            // в «требует внимания», а приёмщик заведёт деталь второй раз.
            IntakeService.Receipt done = intake.replayAfterConflict(request.requestId());
            if (done == null) {
                throw conflict;
            }
            receipt = done;
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptView.of(receipt));
    }

    /**
     * Машины, пришедшие этой партией.
     *
     * <p>Отдаёт тот же список, что и экран машин, а не {@code DonorView}:
     * тот несёт внутренний код, которого владелец никогда не видел.
     */
    @GetMapping("/supplies/{id}/donors")
    public List<DonorDirectory.Entry> donorsOfSupply(@PathVariable Long id) {
        return donorDirectory.ofSupply(id);
    }

    // ---------- запросы ----------

    public record SupplyRequest(Supply.SupplyKind kind,
                                @NotBlank String number,
                                String supplierName) {
    }

    public record DonorRequest(@NotNull Long brandId,
                               Long modelId,
                               Long generationId,
                               Long modificationId,
                               String vin,
                               Short year,
                               String color,
                               String colorCode,
                               /* Как написано в документах: «ACV40», «2AZ-FE».
                                  По ним подбирают деталь по телефону, и они
                                  же уходят в прайс отдельными тегами. */
                               String bodyCode,
                               String engineCode,
                               Integer mileageKm,
                               Donor.Steering steering,
                               Donor.DriveType driveType,
                               Donor.TransmissionType transmissionType,
                               String transmissionModel,
                               String note,
                               Long supplyId) {
    }

    public record ReceiptRequest(
            // Склад обязателен: без него приход некуда положить, а остаток
            // по складам — то, на что продавец смотрит в первую очередь.
            @NotNull Long warehouseId,
            Long supplyId,
            /* Донор необязателен: контрактные детали приходят контейнером напрямую. */
            Long donorId,
            @NotEmpty List<@Valid ItemRequest> items,
            /* Ключ запроса от клиента: повтор офлайн-очереди не должен создать
               вторую партию. Генерируется при постановке в очередь и не меняется
               при повторах. */
            @NotBlank String requestId) {
    }

    /**
     * Позиция приёмки. Наименования карточки здесь нет намеренно — его собирает
     * {@code PartTitleGenerator} из вида детали, машины и сторон.
     */
    public record ItemRequest(@NotBlank String rawName,
                              BigDecimal quantity,
                              @Positive BigDecimal price,
                              BigDecimal costPrice,
                              Long cellId,
                              LateralSide sideLr,
                              LongitudinalSide sideFr,
                              VerticalSide sideUd,
                              PartCondition condition,
                              QualityGrade qualityGrade,
                              String manufacturer,
                              String oemNumber,
                              String marking,
                              String note) {

        IntakeService.ItemRequest toService() {
            return new IntakeService.ItemRequest(
                    rawName,
                    quantity == null ? BigDecimal.ONE : quantity,
                    price, costPrice, cellId,
                    sideLr, sideFr, sideUd, condition, qualityGrade,
                    manufacturer, oemNumber, marking, note);
        }
    }

    // ---------- ответы ----------

    public record SupplyView(Long id, Supply.SupplyKind kind, String number,
                             Supply.SupplyStatus status, LocalDate arrivedOn) {

        static SupplyView of(Supply supply) {
            return new SupplyView(supply.getId(), supply.getKind(), supply.getNumber(),
                    supply.getStatus(), supply.getArrivedOn());
        }
    }

    public record DonorView(Long id, String publicCode, Donor.DonorStatus status,
                            String location, Long supplyId) {

        static DonorView of(Donor donor) {
            return new DonorView(donor.getId(), donor.getPublicCode(), donor.getStatus(),
                    donor.getLocation(), donor.getSupplyId());
        }
    }

    public record ReceiptView(Long documentId, Long documentNumber, List<PartView> parts) {

        static ReceiptView of(IntakeService.Receipt receipt) {
            return new ReceiptView(
                    receipt.document().getId(),
                    receipt.document().getNumber(),
                    receipt.parts().stream().map(PartView::of).toList());
        }
    }

    /**
     * Ответ намеренно несёт собранный заголовок и признак сопоставления:
     * приёмщик должен видеть, что система поняла из его написания, и что
     * наименование ушло в нераспознанные.
     */
    public record PartView(Long id, String publicCode, String title, BigDecimal price,
                           boolean nameMatched) {

        static PartView of(Part part) {
            return new PartView(part.getId(), part.getPublicCode(), part.getTitle(),
                    part.getPrice(), part.getCategoryId() != null);
        }
    }
}
