package ru.partsflow.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Справочник машин: марки, модели, поколения.
 *
 * <p>Живёт в общей схеме {@code catalog}, а не у арендатора: дерево машин
 * у всех одно, и держать его копию на каждого клиента значит поддерживать
 * пятьсот расходящихся справочников.
 *
 * <p>Схема в запросах указана явно. {@code search_path} стоит на арендаторе,
 * и без префикса запрос ушёл бы в его схему, где этих таблиц нет.
 */
@Service
public class VehicleCatalogService {

    private final JdbcTemplate jdbc;

    public VehicleCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Поиск марки.
     *
     * <p>Ищет и по латинскому написанию, и по русскому. Приёмщик набирает
     * «тойота», не переключая раскладку: на телефоне это лишнее действие
     * на каждой машине, а машин за смену десятки.
     */
    @Transactional(readOnly = true)
    public List<Brand> brands(String query, int limit) {
        String term = query == null ? "" : query.strip();
        if (term.isEmpty()) {
            return jdbc.query("""
                    SELECT id, slug, name, name_ru FROM catalog.brand
                     WHERE is_active ORDER BY name LIMIT ?""",
                    VehicleCatalogService::brand, limit);
        }
        return jdbc.query("""
                SELECT id, slug, name, name_ru FROM catalog.brand
                 WHERE is_active
                   AND (name ILIKE ? || '%' OR name_ru ILIKE ? || '%'
                        OR name ILIKE '%' || ? || '%')
                 ORDER BY
                    -- Совпадение с начала выше совпадения в середине: «мазда»
                    -- обязана дать Mazda первой строкой, а не после марки,
                    -- у которой это слово стоит в середине названия.
                    (name ILIKE ? || '%' OR name_ru ILIKE ? || '%') DESC, name
                 LIMIT ?""",
                VehicleCatalogService::brand, term, term, term, term, term, limit);
    }

    @Transactional(readOnly = true)
    public List<Model> models(long brandId, String query, int limit) {
        String term = query == null ? "" : query.strip();
        return jdbc.query("""
                SELECT id, slug, name, year_from, year_to FROM catalog.model
                 WHERE brand_id = ? AND is_active
                   AND (? = '' OR name ILIKE '%' || ? || '%')
                 ORDER BY (? <> '' AND name ILIKE ? || '%') DESC, name
                 LIMIT ?""",
                VehicleCatalogService::model, brandId, term, term, term, term, limit);
    }

    /**
     * Поколения модели, свежие сверху.
     *
     * <p>Свежие сверху не из вкусовщины: на разборку приезжают машины
     * последних поколений, и листать двадцать диапазонов снизу вверх
     * приёмщику пришлось бы на каждой второй.
     */
    @Transactional(readOnly = true)
    public List<Generation> generations(long modelId) {
        return jdbc.query("""
                SELECT id, name, year_from, year_to FROM catalog.generation
                 WHERE model_id = ? ORDER BY year_from DESC NULLS LAST""",
                (rs, i) -> new Generation(rs.getLong("id"), rs.getString("name"),
                        (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to")),
                modelId);
    }

    /**
     * Весь справочник одним куском — для предзагрузки на телефон.
     *
     * <p>Одним запросом, а не деревом по мере выбора: марку выбирают в ангаре,
     * где связи нет. Собирать справочник по клику значит получить неработающий
     * экран ровно там, где он нужен, а качать по модели — это четыре с половиной
     * тысячи запросов, из которых по плохой связи оборвётся любой.
     *
     * <p>Порядок здесь не задаётся: раскладывает и сортирует клиент. У него это
     * дешевле, чем гонять {@code ORDER BY} по двенадцати тысячам строк на каждое
     * обновление кэша.
     */
    @Transactional(readOnly = true)
    public Vehicles all() {
        List<Brand> brands = jdbc.query(
                "SELECT id, slug, name, name_ru FROM catalog.brand WHERE is_active",
                VehicleCatalogService::brand);

        List<ModelRow> models = jdbc.query(
                "SELECT id, brand_id, slug, name FROM catalog.model WHERE is_active",
                (rs, i) -> new ModelRow(rs.getLong("id"), rs.getLong("brand_id"),
                        rs.getString("slug"), rs.getString("name")));

        List<GenerationRow> generations = jdbc.query(
                "SELECT id, model_id, name, year_from, year_to FROM catalog.generation",
                (rs, i) -> new GenerationRow(rs.getLong("id"), rs.getLong("model_id"),
                        rs.getString("name"), (Integer) rs.getObject("year_from"),
                        (Integer) rs.getObject("year_to")));

        return new Vehicles(brands, models, generations);
    }

    private static Brand brand(ResultSet rs, int row) throws SQLException {
        return new Brand(rs.getLong("id"), rs.getString("slug"),
                rs.getString("name"), rs.getString("name_ru"));
    }

    private static Model model(ResultSet rs, int row) throws SQLException {
        return new Model(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
                (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to"));
    }

    public record Brand(Long id, String slug, String name, String nameRu) {
    }

    public record Model(Long id, String slug, String name, Integer yearFrom, Integer yearTo) {
    }

    public record Generation(Long id, String name, Integer yearFrom, Integer yearTo) {
    }

    /** Модель со ссылкой на марку: в пакетной выдаче дерево собирает клиент. */
    public record ModelRow(Long id, Long brandId, String slug, String name) {
    }

    public record GenerationRow(Long id, Long modelId, String name,
                                Integer yearFrom, Integer yearTo) {
    }

    public record Vehicles(List<Brand> brands, List<ModelRow> models,
                           List<GenerationRow> generations) {
    }
}
