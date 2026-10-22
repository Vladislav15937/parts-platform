package ru.partsflow.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Машина из общего справочника {@code catalog.bazon_*}: списки для выбора
 * (марка → модель → кузов → двигатель), слова и проверка согласованности
 * выбранного (задача 0377).
 *
 * <p>Справочник один на всех арендаторов, у позиции хранится только ссылка
 * ({@code part.bazon_*_id}, {@code tenant/096}). Согласованность — модель
 * принадлежит марке, кузов принадлежит поколению этой модели, двигатель
 * принадлежит марке — схема не держит (связь между строками разных таблиц
 * CHECK не выражает, триггеров не заводим), её проверяет {@link #check}
 * при каждой записи, и отказ идёт словами.
 *
 * <p>Запросы квалифицированы схемой {@code catalog} и от арендатора не зависят.
 */
@Service
public class BazonVehicles {

    private static final Logger log = LoggerFactory.getLogger(BazonVehicles.class);

    private final JdbcTemplate jdbc;

    public BazonVehicles(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Mark(long id, String name) {
    }

    public record Model(long id, String name) {
    }

    /**
     * Кузов в списке выбора. {@code name} — код («TNT31»), он бывает пуст
     * (437 кузовов в снимке без кода): тогда подписью служит пометка
     * {@code description}, а в слова названия кузов без кода не идёт.
     */
    public record Body(long id, String name, String description, String generation, String years) {
    }

    public record Motor(long id, String name, Integer volumeCc, String fuel) {
    }

    /** Выбранная машина позиции — ссылки на справочник; пусто — не выбрано. */
    public record Selection(Long markId, Long modelId, Long bodyId, Long motorId) {

        public boolean isEmpty() {
            return markId == null && modelId == null && bodyId == null && motorId == null;
        }
    }

    /** Слова выбранной машины: первое значение каждого поля, как в формуле названия. */
    public record Words(String brand, String model, String body, String engine) {
    }

    @Transactional(readOnly = true)
    public List<Mark> marks() {
        return jdbc.query("SELECT id, name FROM catalog.bazon_mark ORDER BY lower(name), id",
                (rs, i) -> new Mark(rs.getLong("id"), rs.getString("name")));
    }

    @Transactional(readOnly = true)
    public List<Model> models(long markId) {
        return jdbc.query("SELECT id, name FROM catalog.bazon_model WHERE bazon_mark_id = ?"
                        + " ORDER BY lower(name), id",
                (rs, i) -> new Model(rs.getLong("id"), rs.getString("name")), markId);
    }

    @Transactional(readOnly = true)
    public List<Body> bodies(long modelId) {
        return jdbc.query("""
                SELECT b.id, b.name, b.description, g.name AS generation, g.years
                  FROM catalog.bazon_body b
                  JOIN catalog.bazon_generation g ON g.id = b.bazon_generation_id
                 WHERE g.bazon_model_id = ?
                 ORDER BY g.year_from, g.id, b.name NULLS LAST, b.id""",
                (rs, i) -> new Body(rs.getLong("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("generation"),
                        rs.getString("years")), modelId);
    }

    @Transactional(readOnly = true)
    public List<Motor> motors(long markId) {
        return jdbc.query("""
                SELECT id, name, volume_cc, fuel FROM catalog.bazon_motor
                 WHERE bazon_mark_id = ? ORDER BY lower(name), id""",
                (rs, i) -> new Motor(rs.getLong("id"), rs.getString("name"),
                        (Integer) rs.getObject("volume_cc"), rs.getString("fuel")), markId);
    }

    /**
     * Слова выбранной машины. Записей, которых нет, не бывает после
     * {@link #check}; кузов без кода слова не даёт.
     */
    @Transactional(readOnly = true)
    public Words words(Selection s) {
        if (s == null || s.isEmpty()) {
            return new Words(null, null, null, null);
        }
        return new Words(
                s.markId() == null ? null : name("bazon_mark", s.markId()),
                s.modelId() == null ? null : name("bazon_model", s.modelId()),
                s.bodyId() == null ? null : name("bazon_body", s.bodyId()),
                s.motorId() == null ? null : name("bazon_motor", s.motorId()));
    }

    private String name(String table, long id) {
        List<String> found = jdbc.query("SELECT name FROM catalog." + table + " WHERE id = ?",
                (rs, i) -> rs.getString("name"), id);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Проверяет, что выбранное существует и согласовано: модель — из этой
     * марки, кузов — из этой модели, двигатель — этой марки. Выбор может
     * быть неполным (только марка, марка и модель…), но звено без опоры —
     * кузов без модели, модель или двигатель без марки — отказ: иначе
     * марка потерялась бы, а слова названия собрались бы из частей разных машин.
     *
     * @throws IllegalArgumentException словами, без номеров записей (номер — в лог)
     */
    @Transactional(readOnly = true)
    public void check(Selection s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        if ((s.modelId() != null || s.motorId() != null) && s.markId() == null) {
            throw new IllegalArgumentException("Модель и двигатель выбираются после марки:"
                    + " выберите марку машины");
        }
        if (s.bodyId() != null && s.modelId() == null) {
            throw new IllegalArgumentException("Кузов выбирается после модели:"
                    + " выберите модель машины");
        }
        if (s.markId() != null) {
            requireExists("bazon_mark", s.markId());
        }
        if (s.modelId() != null) {
            Long mark = parent("SELECT bazon_mark_id FROM catalog.bazon_model WHERE id = ?", s.modelId());
            if (mark == null) {
                throw unknown(s.modelId());
            }
            if (!mark.equals(s.markId())) {
                throw new IllegalArgumentException("Выбранная модель не относится к выбранной марке:"
                        + " выберите модель этой марки заново");
            }
        }
        if (s.bodyId() != null) {
            Long model = parent("""
                    SELECT g.bazon_model_id FROM catalog.bazon_body b
                      JOIN catalog.bazon_generation g ON g.id = b.bazon_generation_id
                     WHERE b.id = ?""", s.bodyId());
            if (model == null) {
                throw unknown(s.bodyId());
            }
            if (!model.equals(s.modelId())) {
                throw new IllegalArgumentException("Выбранный кузов не относится к выбранной модели:"
                        + " выберите кузов этой модели заново");
            }
        }
        if (s.motorId() != null) {
            Long mark = parent("SELECT bazon_mark_id FROM catalog.bazon_motor WHERE id = ?", s.motorId());
            if (mark == null) {
                throw unknown(s.motorId());
            }
            if (!mark.equals(s.markId())) {
                throw new IllegalArgumentException("Выбранный двигатель не относится к выбранной марке:"
                        + " выберите двигатель этой марки заново");
            }
        }
    }

    private Long parent(String sql, long id) {
        List<Long> found = jdbc.query(sql, (rs, i) -> rs.getLong(1), id);
        return found.isEmpty() ? null : found.get(0);
    }

    private void requireExists(String table, long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM catalog." + table + " WHERE id = ?",
                Integer.class, id);
        if (n == null || n == 0) {
            throw unknown(id);
        }
    }

    private IllegalArgumentException unknown(long id) {
        log.warn("Машина из справочника не найдена: запись {}", id);
        return new IllegalArgumentException("Такой записи в справочнике машин нет"
                + " — обновите страницу, список устарел");
    }
}
