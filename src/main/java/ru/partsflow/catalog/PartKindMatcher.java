package ru.partsflow.catalog;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Поиск эталона в общем каталоге по написанию арендатора.
 *
 * <p>Автоматически сопоставляем только там, где ошибиться нельзя: точное
 * совпадение с названием эталона или с одним из его синонимов. «Запаска» →
 * «Запасное колесо» проходит, потому что «запаска» лежит в синонимах, а не
 * потому что похожа.
 *
 * <p><b>Похожесть в автосопоставление не идёт.</b> Триграммы дают правдоподобные
 * пары вроде «Кронштейн топливного фильтра» → «Фильтр топливный» — именно такая
 * склейка нашлась в живом справочнике Bazon. Одна такая ошибка уводит деталь
 * в чужую категорию, а оттуда — в отказ модерации на площадке. Поэтому похожие
 * эталоны возвращаются подсказками для человека, и решает он.
 */
@Component
public class PartKindMatcher {

    /**
     * Ниже этого порога подсказки не показываем: список из двадцати «может
     * быть, это» бесполезен, человек его просто закроет.
     */
    private static final double MIN_SIMILARITY = 0.3;

    private static final int MAX_SUGGESTIONS = 5;

    /**
     * Пометка о состоянии в конце написания: «Ступица (УЦЕНКА)», «Фара
     * (уценка)», «Лента Airbag(Дефект)».
     *
     * <p>Это не другая деталь, а та же самая с ценником: у переехавшего
     * клиента таких написаний пятьдесят четыре, и почти каждое — эталон,
     * который в справочнике уже есть. Заводить под них синонимы значит
     * дублировать половину справочника, а следующий клиент напишет
     * «(брак)» и получит стену нераспознанных заново.
     *
     * <p><b>Перечень закрытый, а не «всё в скобках».</b> В скобках бывает
     * и то, что деталь определяет: «Патрубок (верхний)» отбрасывать можно,
     * а вот выкусывать скобки вслепую — это ставка на то, что там никогда
     * не окажется существенного. Пометка состояния — известный список,
     * и он проверяем.
     */
    private static final Pattern CONDITION_MARKER = Pattern.compile(
            "\\s*\\(\\s*(уценк\\w*|б/?у|новый|новая|ориг\\w*|дефект\\w*|брак\\w*)"
                    + "\\s*!*\\s*\\)\\s*$",
            // UNICODE_CHARACTER_CLASS обязателен: без него \w в Java — это
            // латиница, и «уценка» обрывается на «уценк», а «А)» уже не подходит
            // под остаток выражения. Поймано тестом.
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private final EntityManager entityManager;

    public PartKindMatcher(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Точное совпадение с эталоном или его синонимом.
     *
     * <p>Со снятой пометкой состояния, если она есть: «Ступица (УЦЕНКА)» —
     * это ступица, а не отдельный вид детали.
     */
    public Optional<PartKind> findExact(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        Optional<PartKind> direct = findVerbatim(rawName);
        if (direct.isPresent()) {
            return direct;
        }
        String stripped = withoutConditionMarker(rawName);
        return stripped.equals(rawName) ? Optional.empty() : findVerbatim(stripped);
    }

    /** Написание без пометки состояния; без пометки возвращает как есть. */
    public static String withoutConditionMarker(String rawName) {
        return CONDITION_MARKER.matcher(rawName).replaceAll("").strip();
    }

    private Optional<PartKind> findVerbatim(String rawName) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT k.id, k.category_id, k.name
                          FROM catalog.part_kind k
                         WHERE k.is_active
                           AND (lower(btrim(k.name)) = lower(btrim(:name))
                                OR EXISTS (
                                    SELECT 1 FROM unnest(k.synonyms) AS s
                                     WHERE lower(btrim(s)) = lower(btrim(:name))))
                         ORDER BY k.id
                         LIMIT 1""")
                .setParameter("name", rawName)
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(toKind(rows.get(0)));
    }

    /** Эталон по идентификатору: нужен при ручном сопоставлении. */
    public Optional<PartKind> findById(Long partKindId) {
        if (partKindId == null) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT k.id, k.category_id, k.name
                          FROM catalog.part_kind k
                         WHERE k.id = :id""")
                .setParameter("id", partKindId)
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(toKind(rows.get(0)));
    }

    /**
     * Похожие эталоны — подсказки человеку на экране нераспознанных.
     * Сортировка по убыванию похожести, чтобы верхний был лучшим.
     */
    public List<PartKind> suggest(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return List.of();
        }
        // Похожесть считается и по синонимам, а не по одному названию эталона.
        // Синонимы — это и есть живые написания, ради которых справочник
        // заведён, и близкое написание клиента похоже именно на них.
        //
        // Замерено на «тросик ручного тормоза» (379 карточек живого склада):
        // по названию «Трос ручника» даёт 0,296 — последнее место из четырёх,
        // ниже «Колодок стояночного тормоза» с 0,316, то есть в тройку
        // подсказок верный эталон не попадал вовсе. По синониму «трос ручного
        // тормоза» — 0,826, первое место с большим отрывом.
        //
        // Цена ошибки тут не в лишнем нажатии: человек, разбирающий тысячу
        // написаний подряд, выбирает из показанного, а показывались три
        // чужие детали. Сопоставив трос с колодками, он одним действием
        // переписывает сотни карточек, и назад это не откатывается.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT k.id, k.category_id, k.name
                          FROM catalog.part_kind k
                         CROSS JOIN LATERAL (
                             SELECT greatest(
                                 similarity(k.name, :name),
                                 coalesce((SELECT max(similarity(s, :name))
                                             FROM unnest(k.synonyms) s), 0)) AS score) m
                         WHERE k.is_active
                           AND m.score >= :threshold
                         ORDER BY m.score DESC, k.id
                         LIMIT :limit""")
                .setParameter("name", rawName)
                .setParameter("threshold", MIN_SIMILARITY)
                .setParameter("limit", MAX_SUGGESTIONS)
                .getResultList();

        return rows.stream().map(PartKindMatcher::toKind).toList();
    }

    /**
     * Поиск эталона по части названия — для ручного выбора, когда подсказок нет.
     *
     * <p>Подсказки идут по похожести строк, а человек ищет по смыслу: на «запаску»
     * похожего в справочнике нет вовсе, а нужное называется «Колесо запасное».
     * Без поиска остаётся листать почти две сотни эталонов, и разгребание
     * нераспознанных встанет на первом же непохожем написании.
     */
    public List<PartKind> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // Ищется и по синонимам: человек набирает то, как деталь зовут
        // на складе, а эталон называется иначе — «ручного тормоза» не входит
        // в «Трос ручника» ни одной буквой, зато входит в его синоним.
        // Без этого поиск не спасал ровно там, где подсказки промахнулись,
        // а он для того и заведён.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT k.id, k.category_id, k.name
                          FROM catalog.part_kind k
                         WHERE k.is_active
                           AND (k.name ILIKE '%' || :query || '%'
                                OR EXISTS (SELECT 1 FROM unnest(k.synonyms) s
                                            WHERE s ILIKE '%' || :query || '%'))
                         ORDER BY length(k.name), k.name
                         LIMIT :limit""")
                .setParameter("query", query.strip())
                .setParameter("limit", limit)
                .getResultList();

        return rows.stream().map(PartKindMatcher::toKind).toList();
    }

    /**
     * Сколько эталонов подошло всего — тем же условием, что и поиск.
     *
     * <p>Выдача обрезана, и разбирающий должен знать, что видит не всё:
     * по слову «датчик» эталонов 21 при пределе 20, и один не показан вовсе.
     * Разойдись условие со счётом — число врало бы ровно там, где на него
     * смотрят.
     */
    public long count(String query) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        Number found = (Number) entityManager.createNativeQuery("""
                        SELECT count(*)
                          FROM catalog.part_kind k
                         WHERE k.is_active
                           AND (k.name ILIKE '%' || :query || '%'
                                OR EXISTS (SELECT 1 FROM unnest(k.synonyms) s
                                            WHERE s ILIKE '%' || :query || '%'))""")
                .setParameter("query", query.strip())
                .getSingleResult();
        return found == null ? 0 : found.longValue();
    }

    private static PartKind toKind(Object[] row) {
        return new PartKind(
                ((Number) row[0]).longValue(),
                row[1] == null ? null : ((Number) row[1]).longValue(),
                (String) row[2]);
    }

    /** Эталонный вид запчасти из общего каталога. */
    /**
     * Все действующие виды деталей.
     *
     * <p>Без поиска и без предела: их сто семьдесят восемь, справочник
     * наполняется миграцией и меняется с релизом. Отдавать его целиком дешевле,
     * чем гонять поиск на каждую букву, а экрану отбора выгрузки нужны ещё
     * и названия уже выбранных — по поиску их не восстановить.
     */
    public List<PartKind> all() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT k.id, k.category_id, k.name
                          FROM catalog.part_kind k
                         WHERE k.is_active
                         ORDER BY k.name""")
                .getResultList();
        return rows.stream()
                .map(r -> new PartKind(((Number) r[0]).longValue(),
                        r[1] == null ? null : ((Number) r[1]).longValue(),
                        (String) r[2]))
                .toList();
    }

    public record PartKind(Long id, Long categoryId, String name) {
    }
}
