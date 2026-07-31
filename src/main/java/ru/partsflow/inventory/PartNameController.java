package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.catalog.PartKindMatcher;
import ru.partsflow.catalog.PartName;
import ru.partsflow.catalog.PartNameService;

import java.time.Instant;
import java.util.List;

/**
 * Разбор нераспознанных наименований.
 *
 * <p>Написание, не совпавшее с эталоном ни точно, ни по синониму, приёмку
 * не останавливает: на складе стоит человек с деталью в руках. Карточка
 * заводится с написанием как есть и без категории, а наименование ложится
 * в этот список. Разгребают его вечером — и после импорта склада, когда таких
 * набирается сразу сотня.
 *
 * <p><b>Лежит в inventory, хотя справочник — это catalog.</b> Сопоставление
 * здесь не заканчивается: за ним идёт правка карточек, а они принадлежат
 * складу. Обратное направление (catalog знает про склад) завело бы цикл между
 * модулями ради одного экрана.
 *
 * <p>Роль — владелец или менеджер: сопоставление меняет заголовки всех позиций
 * под этим написанием разом, и приёмщику такое давать незачем.
 */
@RestController
@RequestMapping("/api/part-names")
public class PartNameController {

    private static final String MANAGES = "hasAnyRole('OWNER','MANAGER')";

    /**
     * Сколько написаний пересопоставляем за раз.
     *
     * <p>У переехавшего клиента их больше тысячи, и обход всех в одном
     * запросе держал бы соединение минуты. Предел означает «повторите,
     * если осталось», а не «остальные пропали».
     */
    private static final int REMATCH_LIMIT = 5_000;

    /** Больше не помещается на экран телефона, а разгребают список по одному. */
    private static final int KIND_SEARCH_LIMIT = 20;

    private final PartNameService partNames;
    private final PartService parts;

    public PartNameController(PartNameService partNames, PartService parts) {
        this.partNames = partNames;
        this.parts = parts;
    }

    /**
     * Нераспознанные, сначала самые ходовые.
     *
     * <p>Общее число отдаётся вместе со страницей: владельцу надо понимать,
     * разгребать ли это сейчас или звать помощника — «12» и «1 200» требуют
     * разных решений.
     */
    @GetMapping("/unmatched")
    public UnmatchedPage unmatched(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {

        var found = partNames.unmatched(page, size);
        // Образец заголовка едет вместе со страницей: он нужен на каждой
        // строке, и запрос на строку превратил бы разбор в двадцать обращений
        // к базе на экран.
        var samples = parts.sampleTitles(
                found.getContent().stream().map(PartName::getId).toList());
        return new UnmatchedPage(
                found.getContent().stream()
                        .map(name -> NameView.of(name, samples.get(name.getId())))
                        .toList(),
                found.getTotalElements());
    }

    /** Похожие эталоны. Решает человек — алгоритм только предлагает. */
    @GetMapping("/{id}/suggestions")
    public List<KindView> suggestions(@PathVariable Long id) {
        return partNames.suggestionsFor(id).stream().map(KindView::of).toList();
    }

    /**
     * Поиск эталона руками.
     *
     * <p>Подсказки идут по похожести строк, а «запаска» не похожа на «Колесо
     * запасное» ничем. Без поиска разбор встанет на первом же таком написании.
     */
    /**
     * Весь справочник видов деталей.
     *
     * <p>Отдельно от поиска: экрану отбора выгрузки нужны названия уже
     * выбранных видов, а поиском их не восстановить — по идентификатору
     * он не ищет. Справочник статичный, сто семьдесят восемь строк.
     */
    /**
     * Пересопоставить нераспознанные по нынешнему справочнику.
     *
     * <p>Справочник видов деталей растёт с релизом, а написания клиента
     * заведены раньше: наименование, не нашедшее эталон в марте, находит его
     * в мае. До этого дотянуться до пересчёта можно было только импортом,
     * то есть у клиента, который уже переехал, пополнение справочника
     * не меняло ничего — он продолжал видеть ту же стену нераспознанных.
     *
     * <p>Ручные сопоставления не трогаются: человек уже решил, и его решение
     * важнее совпадения строк.
     *
     * <p>Следом доводятся карточки: сопоставление, не тронувшее ни одной,
     * чинит будущее и оставляет склад как был.
     */
    @PostMapping("/rematch")
    @PreAuthorize(MANAGES)
    public RematchResult rematch() {
        int matched = partNames.rematchUnmatched(REMATCH_LIMIT);
        return new RematchResult(matched, parts.applyMatchedNames());
    }

    /**
     * @param matched сколько написаний нашли эталон
     * @param updated сколько карточек получили категорию и эталонный заголовок
     */
    public record RematchResult(int matched, int updated) {
    }

    @GetMapping("/kinds/all")
    public List<KindView> allKinds() {
        return partNames.allKinds().stream().map(KindView::of).toList();
    }

    @GetMapping("/kinds")
    public List<KindView> kinds(@RequestParam("q") String query) {
        return partNames.searchKinds(query, KIND_SEARCH_LIMIT).stream()
                .map(KindView::of).toList();
    }

    /**
     * Сопоставляет написание с эталоном и доводит карточки, заведённые под ним.
     *
     * <p>Отвечает числом исправленных позиций: без него владелец не отличит
     * «сопоставил» от «ничего не произошло», а вся работа экрана именно в них.
     */
    @PostMapping("/{id}/match")
    @PreAuthorize(MANAGES)
    public MatchView match(@PathVariable Long id, @Valid @RequestBody MatchRequest request) {
        PartService.MatchResult result = parts.applyMatch(id, request.partKindId());
        return new MatchView(NameView.of(result.partName()), result.updated());
    }

    /**
     * Снимает сопоставление: эталон оказался не тем.
     *
     * <p>Заголовки уже исправленных карточек назад не откатываются: обратная
     * подмена вернула бы «фару лев.» и тем, кого правили руками после
     * сопоставления. Наименование просто возвращается в список.
     */
    @PostMapping("/{id}/unmatch")
    @PreAuthorize(MANAGES)
    public NameView unmatch(@PathVariable Long id) {
        return NameView.of(partNames.unmatch(id));
    }

    public record MatchRequest(@NotNull Long partKindId) {
    }

    public record UnmatchedPage(List<NameView> items, long total) {
    }

    /**
     * @param usageCount сколько позиций заведено под этим написанием — по нему
     *                   видно, что чинить раньше
     */
    public record NameView(Long id, String name, String matchStatus, Long partKindId,
                           Long categoryId, int usageCount, Instant createdAt,
                           String sampleTitle) {

        static NameView of(PartName partName) {
            return of(partName, null);
        }

        static NameView of(PartName partName, String sampleTitle) {
            return new NameView(partName.getId(), partName.getName(),
                    partName.getMatchStatus().name(), partName.getPartKindId(),
                    partName.getCategoryId(), partName.getUsageCount(),
                    partName.getCreatedAt(), sampleTitle);
        }
    }

    public record KindView(Long id, Long categoryId, String name) {

        static KindView of(PartKindMatcher.PartKind kind) {
            return new KindView(kind.id(), kind.categoryId(), kind.name());
        }
    }

    /** @param updated сколько карточек получили категорию и эталонный заголовок */
    public record MatchView(NameView partName, int updated) {
    }
}
