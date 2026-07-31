package ru.partsflow.publishing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST кабинетов площадок.
 *
 * <p>Только владелец: ключ кабинета — это доступ ко всем объявлениям клиента,
 * и заводить его продавцу незачем.
 *
 * <p>Ключ можно записать и заменить, но не прочитать. Отсутствие метода
 * «показать» здесь — часть защиты, а не недоделка.
 */
@RestController
@RequestMapping("/api/marketplace-accounts")
public class MarketplaceAccountController {

    private final MarketplaceAccountService accounts;

    public MarketplaceAccountController(MarketplaceAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<MarketplaceAccountService.Account> list() {
        return accounts.list();
    }

    /**
     * Заводит кабинет площадки.
     *
     * <p>До этого его можно было создать только запросом в базу — то есть
     * подключение выгрузки требовало руки с доступом к Postgres, при том
     * что ключ и ссылка на прайс давно заводились через API.
     */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<MarketplaceAccountService.Account> create(
            @Valid @RequestBody CreateRequest request) {

        MarketplaceAccountService.Account created = accounts.create(
                request.marketplace(), request.title(), request.settings());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}/credentials")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> setCredentials(@PathVariable Long id,
                                               @Valid @RequestBody CredentialsRequest request) {
        accounts.setCredentials(id, request.secret());
        return ResponseEntity.noContent().build();
    }

    /**
     * Заводит или меняет ссылку на прайс.
     *
     * <p>Возвращает полный путь — его владелец передаёт техспециалисту площадки.
     * Смена ссылки останавливает забор прайса, пока новую не пропишут, поэтому
     * это отдельное действие, а не побочный эффект сохранения настроек.
     */
    /**
     * Меняет отбор товара в выгрузку.
     *
     * <p>Выгрузок на одну площадку бывает несколько, и различаются они именно
     * отбором: у живого клиента пять прайсов на Дром по ценовым диапазонам,
     * у каждого своя цена размещения в кабинете площадки.
     */
    @PutMapping("/{id}/filter")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public MarketplaceAccountService.Account setFilter(@PathVariable Long id,
                                                       @RequestBody FilterRequest request) {
        return accounts.setFilter(id, request.priceFrom(), request.priceTo(),
                request.conditions(), request.warehouseIds(),
                request.kindIds(), request.kindsExcluded(),
                request.brandIds(), request.brandsExcluded());
    }

    /**
     * Сколько позиций попадёт в выгрузку с таким отбором — до сохранения.
     *
     * <p>Список по видам деталей на неразобранном справочнике даёт пустой
     * прайс, а площадка примет его молча: объявления пропадут вместе
     * с просмотрами, и узнают об этом через сутки.
     */
    @PostMapping("/filter/count")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public CountView count(@RequestBody FilterRequest request) {
        return new CountView(accounts.countMatching(
                request.priceFrom(), request.priceTo(),
                request.conditions(), request.warehouseIds(),
                request.kindIds(), request.kindsExcluded(),
                request.brandIds(), request.brandsExcluded()));
    }

    public record CountView(long parts) {
    }

    @PostMapping("/{id}/feed-url")
    @PreAuthorize("hasRole('OWNER')")
    public FeedUrlView rotateFeedUrl(@PathVariable Long id) {
        accounts.rotateFeedToken(id);
        return new FeedUrlView(feedPathOf(id));
    }

    /** Текущая ссылка на прайс. Пусто — ещё не заводили. */
    @GetMapping("/{id}/feed-url")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public FeedUrlView feedUrl(@PathVariable Long id) {
        return new FeedUrlView(feedPathOf(id));
    }

    private String feedPathOf(Long id) {
        return accounts.feedPath(id).orElse(null);
    }

    /**
     * @param settings настройки площадки в JSON. Для Дрома это
     *                 {@code {"packetId":"…"}} — номер прайс-листа из адреса
     *                 в кабинете клиента
     */
    public record CreateRequest(@NotBlank String marketplace,
                                @NotBlank String title,
                                String settings) {
    }

    public record CredentialsRequest(@NotBlank String secret) {
    }

    /** Пустое поле — «без ограничения», а не «ничего». */
    public record FilterRequest(java.math.BigDecimal priceFrom,
                                java.math.BigDecimal priceTo,
                                List<String> conditions,
                                List<Long> warehouseIds,
                                List<Long> kindIds,
                                boolean kindsExcluded,
                                List<Long> brandIds,
                                boolean brandsExcluded) {
    }

    public record FeedUrlView(String path) {
    }
}
