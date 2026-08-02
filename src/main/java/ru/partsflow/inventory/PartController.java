package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

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
    private final PartHistoryService history;

    public PartController(PartService partService, PartHistoryService history) {
        this.partService = partService;
        this.history = history;
    }

    /**
     * История позиции: правки и движения остатка.
     *
     * <p>Открыта всем вошедшим, а не только владельцу: «куда делась деталь»
     * спрашивает кладовщик, и отправлять его за ответом к владельцу — это
     * ответ через сутки. Деньги при этом отделены: себестоимость
     * и минимальная цена в ленту правок попадают только владельцу
     * и менеджеру, и не «скрываются на экране», а не уезжают с сервера.
     * Стережёт {@code PartHistoryHttpTest}.
     */
    @GetMapping("/{id}/history")
    public PartHistoryService.History history(@PathVariable long id) {
        String role = CurrentUser.require().role();
        boolean money = "OWNER".equals(role) || "MANAGER".equals(role);
        return history.of(id, money);
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

    /**
     * Правка нескольких карточек разом.
     *
     * <p>Меняется только то, что владелец тронул: у выбранных позиций заметки
     * разные, и «пустое значит очистить» стёрло бы их все одним нажатием.
     * Поэтому карта «поле → значение», а не запись со всеми полями, как
     * в правке одной карточки.
     *
     * <p>Роль та же, что у правки одной: здесь цена и себестоимость.
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public BulkResult updateAll(@Valid @RequestBody BulkRequest request) {
        return new BulkResult(partService.updateAll(
                request.partIds(), request.changes(), CurrentUser.memberId()));
    }

    /**
     * @param changes только тронутые поля: непереданное не меняется
     */
    public record BulkRequest(@NotEmpty List<Long> partIds,
                              @NotEmpty java.util.Map<String, Object> changes) {
    }

    public record BulkResult(int changed) {
    }

    /**
     * Карточка для правки: все поля формы, включая те, которых нет на витрине.
     *
     * <p><b>Форма не собирается из витринной строки.</b> Себестоимости
     * и минимальной цены там нет и быть не должно — витрину читают все
     * вошедшие, включая продавца и кладовщика, а закупочная цена не их дело.
     * Собери форму из того, что отдаёт витрина, — и сохранение стёрло бы
     * себестоимость у каждой правленой позиции. А она снимком уходит в сделку
     * и в отчёт окупаемости, то есть терялась бы навсегда.
     */
    @GetMapping("/{id}/editable")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public UpdateRequest editable(@PathVariable Long id) {
        return UpdateRequest.of(partService.require(id));
    }

    /**
     * Правка карточки товара.
     *
     * <p><b>Роль — владелец или менеджер.</b> Здесь цена, минимальная цена
     * и себестоимость: продавец, торгующийся с покупателем, не должен уметь
     * подвинуть себе нижнюю границу. Кладовщику править нечего — адрес полки
     * он меняет перемещением, а не карточкой.
     *
     * <p>PUT, а не PATCH: приходит вся форма разом, и пустое поле означает
     * «очищено». Патч, в котором отсутствие ключа значит «не трогать»,
     * не даёт стереть заметку.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public PartView update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return PartView.of(partService.update(id, request.toUpdate(), CurrentUser.memberId()));
    }

    /**
     * Тело правки карточки.
     *
     * <p>Заголовка, стороны и состояния тут нет намеренно: заголовок
     * собирается из них справочником, и правка руками разошлась бы с ним
     * при первом же пересопоставлении.
     */
    public record UpdateRequest(@PositiveOrZero BigDecimal price,
                                @PositiveOrZero BigDecimal minPrice,
                                @PositiveOrZero BigDecimal costPrice,
                                @PositiveOrZero BigDecimal installationPrice,
                                QualityGrade qualityGrade,
                                String description,
                                String note,
                                String textBlock,
                                String videoUrl,
                                String marking,
                                String manufacturer,
                                String color,
                                String section,
                                String barcode,
                                @PositiveOrZero BigDecimal weightKg,
                                @PositiveOrZero Integer lengthMm,
                                @PositiveOrZero Integer widthMm,
                                @PositiveOrZero Integer heightMm,
                                @PositiveOrZero Integer packageLengthMm,
                                @PositiveOrZero Integer packageWidthMm,
                                @PositiveOrZero Integer packageHeightMm,
                                @PositiveOrZero BigDecimal packageWeightKg,
                                Long storageCellId,
                                boolean published) {

        static UpdateRequest of(Part part) {
            return new UpdateRequest(part.getPrice(), part.getMinPrice(), part.getCostPrice(),
                    part.getInstallationPrice(), part.getQualityGrade(), part.getDescription(),
                    part.getNote(), part.getTextBlock(), part.getVideoUrl(), part.getMarking(),
                    part.getManufacturer(), part.getColor(), part.getSection(), part.getBarcode(),
                    part.getWeightKg(), part.getLengthMm(), part.getWidthMm(), part.getHeightMm(),
                    part.getPackageLengthMm(), part.getPackageWidthMm(), part.getPackageHeightMm(),
                    part.getPackageWeightKg(), part.getStorageCellId(), part.isPublished());
        }

        PartService.PartUpdate toUpdate() {
            return new PartService.PartUpdate(price, minPrice, costPrice, installationPrice,
                    qualityGrade, description, note, textBlock, videoUrl, marking, manufacturer,
                    color, section, barcode, weightKg, lengthMm, widthMm, heightMm,
                    packageLengthMm, packageWidthMm, packageHeightMm, packageWeightKg,
                    storageCellId, published);
        }
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
