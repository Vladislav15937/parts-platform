package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Как позицию называют в отказе, который собирает сервер:
 * «Фара левая Toyota Camry · контрактная · 8414012530».
 *
 * <p><b>Зачем номер.</b> Решение владельца продукта 18.10.2026 (задача 0320):
 * наименования и слова состояния мало — «Фара левая Toyota Camry · б/у» бывает
 * не одна, и продавец, получивший «нужно 2, свободно 1», снова ищет, какая
 * из двух кончилась. Запчасть опознают по номеру производителя.
 *
 * <p><b>Какой номер.</b> Только основной ({@code part_oem.is_primary}), в виде
 * хранения для показа ({@code raw_number}: {@code normalized} — ключ поиска,
 * человек его не писал). Основного нет — номера в тексте нет; кросс-номер
 * вместо основного не подставляется: это номер другой детали, подходящей
 * вместо этой, и он назвал бы не ту позицию.
 *
 * <p><b>Одно место.</b> До этой задачи четыре места звали
 * {@link PartCondition#named} каждое со своим запросом, и номер пришлось бы
 * дописывать четырежды — с неизбежным пятым, забывшим его. Перебор
 * {@code RefusalNamesPartWithOemTest.namedOnlyHere} держит
 * {@link PartCondition#named} за этим классом.
 *
 * <p>Запрос — только на пути отказа, и на отказ с несколькими позициями один
 * ({@link #of(Collection)}), а не по запросу на строку.
 */
@Service
public class PartNaming {

    private final JdbcTemplate jdbc;

    public PartNaming(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Основной номер производителя выражением SQL; {@code alias} — псевдоним
     * таблицы {@code part}. Для запросов, которые читают позицию сами (под
     * блокировкой), — чтобы номер приехал тем же запросом, а не вторым.
     * Колонка результата — {@value #OEM_COLUMN}.
     */
    public static String primaryOemSql(String alias) {
        return "(SELECT o.raw_number FROM part_oem o WHERE o.part_id = " + alias
                + ".id AND o.is_primary) AS " + OEM_COLUMN;
    }

    public static final String OEM_COLUMN = "primary_oem";

    /**
     * Название из строки, где есть {@code title}, {@code condition}
     * и {@link #OEM_COLUMN} ({@link #primaryOemSql}).
     */
    public static String named(ResultSet rs) throws SQLException {
        String condition = rs.getString("condition");
        return format(rs.getString("title"),
                condition == null ? null : PartCondition.valueOf(condition),
                rs.getString(OEM_COLUMN));
    }

    /**
     * «Наименование · состояние · номер»; пустое звено пропадает вместе
     * со своим разделителем: «Фара · 8414012530», «Фара · б/у», «Фара».
     */
    public static String format(String title, PartCondition condition, String oem) {
        String named = PartCondition.named(title, condition);
        return oem == null || oem.isBlank() ? named : named + " · " + oem.strip();
    }

    /** Одна позиция; {@code null}, если её нет. */
    @Transactional(readOnly = true)
    public String of(Long partId) {
        return of(java.util.List.of(partId)).get(partId);
    }

    /**
     * Позиции отказа одним запросом. Удалённой позиции в ответе нет —
     * как её назвать, решает вызывающий.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> of(Collection<Long> partIds) {
        Map<Long, String> names = new HashMap<>();
        if (partIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT p.id, p.title, p.condition, " + primaryOemSql("p")
                        + " FROM part p WHERE p.id = ANY (?)",
                rs -> {
                    names.put(rs.getLong("id"), named(rs));
                },
                (Object) partIds.stream().distinct().toArray(Long[]::new));
        return names;
    }
}
