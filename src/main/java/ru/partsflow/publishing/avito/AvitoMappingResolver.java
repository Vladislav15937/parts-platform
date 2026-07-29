package ru.partsflow.publishing.avito;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Соответствие узлов нашего дерева категориям и параметрам Авито.
 *
 * <p>Живёт в таблице {@code catalog.marketplace_mapping}, а не в коде, намеренно:
 * требования площадки к категориям меняются, и правка не должна означать релиз.
 * Это самая частая причина отказов модерации, поэтому реакция должна быть
 * быстрой — правкой строки в базе.
 *
 * <p>Кэш локальный: справочник маленький и меняется редко.
 */
@Component
public class AvitoMappingResolver {

    private final JdbcTemplate jdbcTemplate;
    private final Map<Long, Mapping> cache = new ConcurrentHashMap<>();

    public AvitoMappingResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Mapping resolve(Long categoryId) {
        return cache.computeIfAbsent(categoryId, this::load);
    }

    public void invalidate() {
        cache.clear();
    }

    private Mapping load(Long categoryId) {
        return jdbcTemplate.query("""
                        SELECT external_category, params
                        FROM catalog.marketplace_mapping
                        WHERE marketplace = 'AVITO'
                          AND part_category_id = ?
                          AND valid_to IS NULL
                        LIMIT 1
                        """,
                rs -> rs.next()
                        ? new Mapping(rs.getString("external_category"), goodsTypeOf(rs.getString("params")))
                        : Mapping.fallback(),
                categoryId);
    }

    /**
     * TODO: разобрать jsonb параметров нормально (Jackson) и вернуть их все.
     * Пока извлекается только Goods Type, чтобы контур выгрузки работал целиком.
     */
    private String goodsTypeOf(String paramsJson) {
        if (paramsJson == null || !paramsJson.contains("goodsType")) {
            return "Запчасти";
        }
        int start = paramsJson.indexOf("goodsType");
        int colon = paramsJson.indexOf(':', start);
        int q1 = paramsJson.indexOf('"', colon);
        int q2 = paramsJson.indexOf('"', q1 + 1);
        return (q1 > 0 && q2 > q1) ? paramsJson.substring(q1 + 1, q2) : "Запчасти";
    }

    public record Mapping(String category, String goodsType) {

        /**
         * Категория без маппинга. Не бросаем исключение: одна незамапленная
         * позиция не должна валить выгрузку всего склада — она уедет
         * в общую категорию, а модерация покажет проблему точечно.
         */
        static Mapping fallback() {
            return new Mapping("Запчасти и аксессуары", "Запчасти");
        }
    }
}
