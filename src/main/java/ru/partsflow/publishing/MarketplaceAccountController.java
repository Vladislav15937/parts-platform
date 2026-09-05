package ru.partsflow.publishing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Адрес, по которому ячейка видна снаружи.
     *
     * <p>Тот же, из которого собираются ссылки на снимки внутри прайса,
     * и по той же причине: за терминатором адрес в запросе — это внутреннее
     * имя контейнера, и техспециалист площадки получил бы
     * {@code http://app:8080/…}. Из запроса строится только в разработке,
     * где настройки нет.
     */
    private final String publicUrl;

    public MarketplaceAccountController(MarketplaceAccountService accounts,
                                        @Value("${app.public-url:}") String publicUrl) {
        this.accounts = accounts;
        this.publicUrl = publicUrl;
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
                request.marketplace(), request.title(), request.settings(),
                request.productLine());
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
                request.brandIds(), request.brandsExcluded(),
                request.columns(), request.words());
    }

    /**
     * Меняет настройки сборки прайса: наценку на прайс-лист и округление.
     *
     * <p>Отдельным путём от отбора: отбор говорит, <b>что</b> уедет
     * в этот прайс-лист, настройки — <b>каким</b> оно уедет. Площадка берёт
     * комиссию, и продавцы закладывают её в цену объявления — у живого
     * клиента на прайсе Авито стоит −20 %; на складе и у продавца цена
     * при этом остаётся прежней.
     *
     * <p>Управляющему открыто наравне с владельцем, как и отбор: цена
     * размещения на площадке — его повседневная работа, а доступа
     * к кабинету это не даёт.
     */
    @PutMapping("/{id}/settings")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public MarketplaceAccountService.Account setSettings(@PathVariable Long id,
                                                         @RequestBody FeedSettings settings) {
        return accounts.setSettings(id, settings);
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
                request.brandIds(), request.brandsExcluded(),
                request.productLine(), request.columns(), request.words()));
    }

    public record CountView(long parts) {
    }

    @PostMapping("/{id}/feed-url")
    @PreAuthorize("hasRole('OWNER')")
    public FeedUrlView rotateFeedUrl(@PathVariable Long id, HttpServletRequest request) {
        accounts.rotateFeedToken(id);
        return view(feedPathOf(id), request);
    }

    /** Текущая ссылка на прайс. Пусто — ещё не заводили. */
    @GetMapping("/{id}/feed-url")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public FeedUrlView feedUrl(@PathVariable Long id, HttpServletRequest request) {
        return view(feedPathOf(id), request);
    }

    /**
     * Путь и полный адрес.
     *
     * <p>Полный — потому что его передают человеку на той стороне: инструкция
     * так и говорит, «передать ссылку техспециалисту площадки». Отдавая один
     * путь, мы заставляли владельца дописывать домен руками, а специалист,
     * получив {@code /feeds/drom/…}, не сходит по нему никуда. Домен сервер
     * знает: тот же {@code app.public-url} уже подставляется в ссылки
     * на снимки внутри самого прайса.
     *
     * <p>Путь оставлен: по нему прайс скачивается с того же источника,
     * где открыт экран, и в разработке это единственный работающий адрес.
     */
    private FeedUrlView view(String path, HttpServletRequest request) {
        if (path == null) {
            return new FeedUrlView(null, null);
        }
        String origin = publicUrl == null || publicUrl.isBlank()
                ? request.getRequestURL().toString().replaceFirst("/api/.*$", "")
                : publicUrl.replaceFirst("/+$", "");
        return new FeedUrlView(path, origin + path);
    }

    private String feedPathOf(Long id) {
        return accounts.feedPath(id).orElse(null);
    }

    /**
     * @param settings настройки площадки в JSON. Для Дрома это
     *                 {@code {"packetId":"…"}} — номер прайс-листа из адреса
     *                 в кабинете клиента
     */
    /** @param productLine «PART» или «WHEEL»; пусто — запчасти */
    public record CreateRequest(@NotBlank String marketplace,
                                @NotBlank String title,
                                String settings,
                                String productLine) {
    }

    public record CredentialsRequest(@NotBlank String secret) {
    }

    /** Пустое поле — «без ограничения», а не «ничего». */
    /**
     * @param productLine чем торгует выгрузка. Без него счётчик считал
     *                   запчасти всегда: у выгрузки колёс он обещал 35 835
     *                   позиций там, где уезжало 60 — то есть врал в шестьсот
     *                   раз ровно в ту сторону, ради которой заведён.
     *                   Пусто — запчасти, как было
     */
    public record FilterRequest(java.math.BigDecimal priceFrom,
                                java.math.BigDecimal priceTo,
                                List<String> conditions,
                                List<Long> warehouseIds,
                                List<Long> kindIds,
                                boolean kindsExcluded,
                                List<Long> brandIds,
                                boolean brandsExcluded,
                                String productLine,
                                /**
                                 * Отбор по колонкам склада: точное равенство,
                                 * «колонка → значение». Список колонок закрыт
                                 * сервером, неизвестное имя отвергается.
                                 */
                                java.util.Map<String, String> columns,
                                /** То же вхождением: набранное руками. */
                                java.util.Map<String, String> words) {
    }

    /**
     * @param path путь на том же источнике, откуда открыт экран
     * @param url  полный адрес — его и передают площадке
     */
    public record FeedUrlView(String path, String url) {
    }
}
