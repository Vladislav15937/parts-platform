package ru.partsflow.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Справочник наименований арендатора.
 *
 * <p>Точка входа приёмки: приёмщик вводит написание, система находит или
 * заводит запись и пытается привязать эталон. Если не вышло — деталь всё равно
 * заводится, а наименование попадает в список нераспознанных. Останавливать
 * приёмку из-за несопоставленного названия нельзя: на складе стоит человек
 * с деталью в руках, а разгребать справочник будут вечером.
 */
@Service
public class PartNameService {

    private final PartNameRepository repository;
    private final PartKindMatcher matcher;

    public PartNameService(PartNameRepository repository, PartKindMatcher matcher) {
        this.repository = repository;
        this.matcher = matcher;
    }

    /**
     * Находит наименование или заводит новое, попутно пытаясь сопоставить эталон.
     *
     * <p>Счётчик использований растёт на каждое обращение — по нему видно,
     * какое из нераспознанных чинить раньше: то, под которым уже двести
     * позиций, а не то, что завели вчера.
     */
    @Transactional
    public PartName resolve(String rawName, Long authorId) {
        PartName existing = repository.findByNormalizedName(rawName).orElse(null);
        if (existing != null) {
            repository.incrementUsage(existing.getId());
            return existing;
        }

        PartName created = new PartName(rawName);
        created.setCreatedBy(authorId);
        matcher.findExact(rawName).ifPresent(
                kind -> created.matchTo(kind.id(), kind.categoryId(), true));

        PartName saved = repository.saveAndFlush(created);
        repository.incrementUsage(saved.getId());
        return saved;
    }

    /**
     * Ручное сопоставление с экрана нераспознанных.
     *
     * <p>Помечается как {@code MANUAL} и потом не пересчитывается: человек
     * смотрел на деталь, алгоритм — на строку.
     */
    @Transactional
    public PartName matchManually(Long partNameId, Long partKindId) {
        PartName partName = require(partNameId);
        PartKindMatcher.PartKind kind = matcher.findById(partKindId).orElseThrow(
                () -> new IllegalArgumentException("Эталон не найден: " + partKindId));

        partName.matchTo(kind.id(), kind.categoryId(), false);
        return repository.saveAndFlush(partName);
    }

    /** Снимает сопоставление: эталон оказался не тем. */
    @Transactional
    public PartName unmatch(Long partNameId) {
        PartName partName = require(partNameId);
        partName.unmatch();
        return repository.saveAndFlush(partName);
    }

    /**
     * Название для заголовка карточки: эталонное, если наименование сопоставлено,
     * иначе написание арендатора.
     *
     * <p>Смысл справочника — однородность склада. «фара лев.», «Фара левая перед»
     * и «фара L» должны дать один и тот же заголовок, а для этого заголовок
     * собирается из эталона. Пока эталона нет, берём написание как есть: короткий
     * неоднородный заголовок лучше отказа в приёмке, и он выправится сам, когда
     * наименование сопоставят.
     */
    @Transactional(readOnly = true)
    public String displayNameOf(PartName partName) {
        if (!partName.isMatched()) {
            return partName.getName();
        }
        return matcher.findById(partName.getPartKindId())
                .map(PartKindMatcher.PartKind::name)
                .orElse(partName.getName());
    }

    /**
     * Экран «нераспознанные» — тот самый список, который разгребают руками.
     *
     * <p>Сначала то, под чем больше позиций: разгребают его после импорта
     * склада, а там все написания заведены одной секундой, и «свежие сверху»
     * означает «в случайном порядке».
     */
    @Transactional(readOnly = true)
    public Page<PartName> unmatched(int page, int size) {
        return repository.findByMatchStatusOrderByUsageCountDescCreatedAtDescIdDesc(
                PartName.MatchStatus.UNMATCHED, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public long unmatchedCount() {
        return repository.countByMatchStatus(PartName.MatchStatus.UNMATCHED);
    }

    /** Подсказки для нераспознанного: решает человек, алгоритм только предлагает. */
    @Transactional(readOnly = true)
    public List<PartKindMatcher.PartKind> suggestionsFor(Long partNameId) {
        return matcher.suggest(require(partNameId).getName());
    }

    /** Поиск эталона руками, когда подсказки мимо. */
    @Transactional(readOnly = true)
    public List<PartKindMatcher.PartKind> searchKinds(String query, int limit) {
        return matcher.search(query, limit);
    }

    /** Все виды деталей: справочник статичный и маленький. */
    @Transactional(readOnly = true)
    public List<PartKindMatcher.PartKind> allKinds() {
        return matcher.all();
    }

    @Transactional(readOnly = true)
    public PartName require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Наименование не найдено: " + id));
    }

    /**
     * Пересчитывает счётчик использований по карточкам склада.
     *
     * <p>Внутри транзакции — иначе {@code search_path} не выставлен, запрос
     * уходит в {@code public} и падает на «relation part_name does not exist».
     * Ровно так и вышло, когда пересчёт стоял в контроллере.
     */
    @Transactional
    public int recountUsage() {
        return repository.recountUsage();
    }

    /**
     * Пересчитывает автосопоставления после пополнения общего каталога.
     *
     * <p>Каталог наполняется постепенно, и наименование, не нашедшее эталон
     * в марте, находит его в мае. Ручные сопоставления не трогаются.
     *
     * @return сколько наименований удалось сопоставить
     */
    @Transactional
    public int rematchUnmatched(int limit) {
        Page<PartName> batch = repository.findByMatchStatusOrderByCreatedAtDesc(
                PartName.MatchStatus.UNMATCHED, PageRequest.of(0, limit));

        int matched = 0;
        for (PartName partName : batch) {
            if (!partName.isReMatchable()) {
                continue;
            }
            var kind = matcher.findExact(partName.getName());
            if (kind.isPresent()) {
                partName.matchTo(kind.get().id(), kind.get().categoryId(), true);
                matched++;
            }
        }
        repository.flush();
        return matched;
    }
}
