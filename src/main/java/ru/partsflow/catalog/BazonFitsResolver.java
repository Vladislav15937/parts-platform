package ru.partsflow.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Собирает строки «&lt;Вид&gt; подходит:» для позиции по справочнику {@code catalog.bazon_*}
 * (задача 0342): строка применимости позиции → поколение Bazon → кузова и двигатели.
 *
 * <p><b>Живёт одну сборку прайса и ходит по соединению сборки.</b> Прайс читает склад
 * курсором в транзакции, и {@code JdbcTemplate} из бина здесь ушёл бы в {@code public}
 * (ловушка корневого {@code CLAUDE.md}); каталог лежит в общей схеме {@code catalog} и
 * от арендатора не зависит, поэтому соединение сборки подходит. Ответы кэшируются:
 * позиций десятки тысяч, а поколений в справочнике — тысячи.
 *
 * <p><b>Выбор поколения.</b> Наше поколение — диапазон лет по записям Дрома, у Bazon —
 * поколение с номером, и на стыке годов подходят два (замер: 1 508 из 8 446 пар).
 * Берётся то, чьи годы пересекаются с годами нашего сильнее; поровну — более позднее
 * (то же правило, что у подбора поколения машины-донора). Это решение сборки, не схемы.
 *
 * <p><b>Поколение целиком.</b> Пока выбор кузовов и двигателей у позиции не хранится
 * (0343), в строку идут все названные кузова поколения и все его двигатели. Порядок —
 * по названию: порядка Bazon в справочнике нет.
 */
public final class BazonFitsResolver {

    /**
     * Строка применимости позиции, как её видит сборка. Модели нет — строки нет
     * вовсе: «любая машина марки» поколения не называет.
     *
     * @param modelId      {@code part_applicability.model_id}
     * @param generationId {@code part_applicability.generation_id}
     * @param label        метка «N поколение» (0297), если поколение не выбрано
     * @param yearFrom     годы строки — если поколение не выбрано
     */
    public record Row(Long modelId, Long generationId, String label, Integer yearFrom, Integer yearTo) {
    }

    /**
     * Итог по позиции.
     *
     * @param lines строки блока по порядку применимости, без повторов
     * @param gaps  словами: что не нашлось. Для человека, не для покупателя —
     *              в файл это не идёт
     */
    public record Result(List<String> lines, List<String> gaps) {
    }

    private record Outcome(BazonFits.Generation generation, String gap) {
    }

    private static final int OPEN_END = 9999;

    private final Connection connection;
    private final Map<Row, Outcome> outcomes = new HashMap<>();
    private final Map<Long, List<Candidate>> candidatesByModel = new HashMap<>();
    private final Map<Long, int[]> ourYears = new HashMap<>();

    private record Candidate(long id, String name, String years, String series, int from, Integer to,
                             String mark, String model) {
    }

    public BazonFitsResolver(Connection connection) {
        this.connection = connection;
    }

    /**
     * @param engineMatters {@code part.engine_matters}: «двигатель важен» — перечень
     *                      двигателей; нет — «любой двигатель» (0363: то же состояние, что
     *                      «любой двигатель» из 0343, второго не заводим)
     */
    public Result resolve(List<Row> rows, boolean engineMatters) {
        Set<String> lines = new LinkedHashSet<>();
        Set<String> gaps = new LinkedHashSet<>();
        for (Row row : rows) {
            if (row.modelId() == null) {
                continue;
            }
            Outcome outcome = outcomes.computeIfAbsent(row, this::resolveRow);
            if (outcome.gap() != null) {
                gaps.add(outcome.gap());
            }
            if (outcome.generation() != null) {
                String line = BazonFits.line(outcome.generation(), !engineMatters);
                if (line != null) {
                    lines.add(line);
                }
            }
        }
        return new Result(List.copyOf(lines), List.copyOf(gaps));
    }

    private Outcome resolveRow(Row row) {
        try {
            int[] wanted = wantedYears(row);
            List<Candidate> candidates = candidates(row.modelId());
            if (candidates.isEmpty()) {
                String model = modelName(row.modelId());
                return new Outcome(null, "В справочнике поколений Bazon нет модели «" + model
                        + "»: данных поколения нет");
            }
            Candidate chosen = wanted != null ? byYears(candidates, wanted) : byLabel(candidates, row.label());
            if (chosen == null) {
                if (wanted == null) {
                    return new Outcome(null, null); // поколение не выбрано — нечего собирать
                }
                String model = modelName(row.modelId());
                return new Outcome(null, "У модели «" + model
                        + "» нет поколения Bazon за эти годы: данных поколения нет");
            }
            BazonFits.Generation generation = load(chosen);
            if (generation.motors().isEmpty()) {
                return new Outcome(null, chosen.mark() + " " + chosen.model() + " " + chosen.name()
                        + ": данных поколения нет (в справочнике нет двигателей)");
            }
            return new Outcome(generation, null);
        } catch (SQLException e) {
            throw new IllegalStateException("Справочник применимости Bazon не прочитан", e);
        }
    }

    /** Годы нашего поколения, а если оно не выбрано — годы самой строки; иначе {@code null}. */
    private int[] wantedYears(Row row) throws SQLException {
        if (row.generationId() != null) {
            int[] years = ourYears(row.generationId());
            if (years != null) {
                return years;
            }
        }
        if (row.yearFrom() != null) {
            return new int[] {row.yearFrom(), row.yearTo() != null ? row.yearTo() : OPEN_END};
        }
        return null;
    }

    private int[] ourYears(long generationId) throws SQLException {
        if (ourYears.containsKey(generationId)) {
            return ourYears.get(generationId);
        }
        int[] years = null;
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT year_from, year_to FROM catalog.generation WHERE id = ?")) {
            st.setLong(1, generationId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && rs.getObject("year_from") != null) {
                    Integer to = rs.getObject("year_to", Integer.class);
                    years = new int[] {rs.getInt("year_from"), to != null ? to : OPEN_END};
                }
            }
        }
        ourYears.put(generationId, years);
        return years;
    }

    private static Candidate byYears(List<Candidate> candidates, int[] wanted) {
        Candidate best = null;
        int bestOverlap = 0;
        for (Candidate c : candidates) {
            int overlap = Math.min(wanted[1], c.to() != null ? c.to() : OPEN_END)
                    - Math.max(wanted[0], c.from()) + 1;
            if (overlap <= 0) {
                continue;
            }
            if (best == null || overlap > bestOverlap
                    || (overlap == bestOverlap && c.from() > best.from())) {
                best = c;
                bestOverlap = overlap;
            }
        }
        return best;
    }

    private static Candidate byLabel(List<Candidate> candidates, String label) {
        if (label == null) {
            return null;
        }
        Candidate found = null;
        for (Candidate c : candidates) {
            if (c.name().equals(label)) {
                if (found != null) {
                    return null; // два «2 поколение» у одной модели Bazon — не угадываем
                }
                found = c;
            }
        }
        return found;
    }

    private List<Candidate> candidates(long modelId) throws SQLException {
        List<Candidate> cached = candidatesByModel.get(modelId);
        if (cached != null) {
            return cached;
        }
        List<Candidate> found = new ArrayList<>();
        try (PreparedStatement st = connection.prepareStatement("""
                SELECT g.id, g.name, g.years, g.series, g.year_from, g.year_to, m.name AS mark, bm.name AS model
                  FROM catalog.bazon_generation g
                  JOIN catalog.bazon_model bm ON bm.id = g.bazon_model_id
                  JOIN catalog.bazon_mark m ON m.id = bm.bazon_mark_id
                 WHERE bm.model_id = ?""")) {
            st.setLong(1, modelId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    found.add(new Candidate(rs.getLong("id"), rs.getString("name"), rs.getString("years"),
                            rs.getString("series"), rs.getInt("year_from"),
                            rs.getObject("year_to", Integer.class), rs.getString("mark"),
                            rs.getString("model")));
                }
            }
        }
        candidatesByModel.put(modelId, found);
        return found;
    }

    private BazonFits.Generation load(Candidate c) throws SQLException {
        List<String> bodies = new ArrayList<>();
        try (PreparedStatement st = connection.prepareStatement("""
                SELECT name FROM (SELECT DISTINCT name FROM catalog.bazon_body
                                   WHERE bazon_generation_id = ? AND name IS NOT NULL) b
                 ORDER BY name COLLATE "C\"""")) {
            st.setLong(1, c.id());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    bodies.add(rs.getString(1));
                }
            }
        }
        List<BazonFits.Motor> motors = new ArrayList<>();
        try (PreparedStatement st = connection.prepareStatement("""
                SELECT mo.name, mo.volume_cc, mo.fuel
                  FROM catalog.bazon_generation_motor gm
                  JOIN catalog.bazon_motor mo ON mo.id = gm.bazon_motor_id
                 WHERE gm.bazon_generation_id = ?
                 ORDER BY mo.name COLLATE "C", mo.bazon_id""")) {
            st.setLong(1, c.id());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    motors.add(new BazonFits.Motor(rs.getString("name"),
                            rs.getObject("volume_cc", Integer.class), rs.getString("fuel")));
                }
            }
        }
        return new BazonFits.Generation(c.mark(), c.model(), c.name(), c.years(), c.series(), bodies, motors);
    }

    /** Марка и модель нашего справочника — для слов о том, чего не нашлось. */
    private String modelName(long modelId) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(
                "SELECT b.name || ' ' || m.name FROM catalog.model m JOIN catalog.brand b ON b.id = m.brand_id WHERE m.id = ?")) {
            st.setLong(1, modelId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : "модель не найдена";
            }
        }
    }
}
