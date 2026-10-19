package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.shared.NotFound;

import java.time.Instant;
import java.util.List;

/**
 * «Двигатель важен» и список «Применимость подробно» (задача 0363, как у Bazon).
 *
 * <p><b>Признак принадлежит позиции, а не строке применимости.</b> У Bazon
 * «Двигатель не важен» стоит над всем списком машин позиции, и это же состояние —
 * «любой двигатель» из 0343: второго не заводить. Хранится в {@code part.engine_matters}
 * и правится <b>сущностью</b>, чтобы запись попала в историю позиции («Двигатель
 * важен: нет → да») слушателем журнала, а не отдельной строчкой кода.
 *
 * <p><b>Умолчание — «не важен».</b> Список видов деталей, где «важен» ставится сам
 * (ДВС, КПП, тросик газа), у Bazon снаружи не виден; он отдан обходу, а угадывать его
 * значило бы выдать догадку за справочник. Пока человек правит признак сам.
 */
@Service
public class ApplicabilityDetails {

    /** Строк на странице списка: тысячи позиций, одним ответом их не отдают. */
    static final int PAGE = 50;

    static final String NO_GENERATION_NOTE =
            "Применимость сама не поставилась: у машины-донора не указано поколение. "
                    + "Добавьте машину вручную.";

    private final JdbcTemplate jdbc;
    private final PartRepository parts;

    public ApplicabilityDetails(JdbcTemplate jdbc, PartRepository parts) {
        this.jdbc = jdbc;
        this.parts = parts;
    }

    /**
     * Признак и пояснение для карточки. Пояснение — когда применимости нет,
     * а причина известна: у донора позиции не указано поколение, и сама она
     * не поставилась. Без него «не задана» читалась бы как «забыли».
     */
    @Transactional(readOnly = true)
    public State stateOf(long partId) {
        List<State> found = jdbc.query("""
                SELECT p.engine_matters,
                       (SELECT count(*) FROM part_applicability a WHERE a.part_id = p.id) AS fits,
                       d.id AS donor_id, d.generation_id
                  FROM part p
                  LEFT JOIN donor d ON d.id = p.donor_id
                 WHERE p.id = ?""",
                (rs, i) -> new State(rs.getBoolean("engine_matters"),
                        rs.getLong("fits") == 0 && rs.getObject("donor_id") != null
                                && rs.getObject("generation_id") == null
                                ? NO_GENERATION_NOTE : null),
                partId);
        if (found.isEmpty()) {
            throw NotFound.PART.error(partId);
        }
        return found.get(0);
    }

    /** Ставит «важен / не важен». Сущностью — для истории позиции. */
    @Transactional
    public State setEngineMatters(long partId, boolean matters) {
        Part part = parts.findByIdForUpdate(partId)
                .orElseThrow(() -> NotFound.PART.error(partId));
        if (part.isEngineMatters() != matters) {
            part.setEngineMatters(matters);
            parts.saveAndFlush(part);
        }
        return stateOf(partId);
    }

    /**
     * «Применимость подробно»: позиции склада по номеру по убыванию, строка на машину
     * применимости; позиция без применимости — одна строка с пустой машиной
     * (список отвечает и на вопрос «у каких её нет»).
     */
    @Transactional(readOnly = true)
    public Page list(int page) {
        int offset = Math.max(page, 0) * PAGE;
        List<Row> rows = jdbc.query("""
                SELECT p.id, p.number, p.title, p.engine_matters,
                       b.name AS brand, m.name AS model, g.name AS generation,
                       a.year_from, a.year_to, tm.display_name AS set_by, a.set_at
                  FROM part p
                  LEFT JOIN part_applicability a ON a.part_id = p.id
                  LEFT JOIN catalog.brand b ON b.id = a.brand_id
                  LEFT JOIN catalog.model m ON m.id = a.model_id
                  LEFT JOIN catalog.generation g ON g.id = a.generation_id
                  LEFT JOIN tenant_member tm ON tm.id = a.set_by
                 WHERE p.product_line = 'PART'
                 ORDER BY p.number DESC, a.id
                 LIMIT ? OFFSET ?""",
                (rs, i) -> new Row(rs.getLong("id"), rs.getLong("number"), rs.getString("title"),
                        rs.getBoolean("engine_matters"), rs.getString("brand"),
                        rs.getString("model"), rs.getString("generation"),
                        (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to"),
                        rs.getString("set_by"),
                        // getObject в Instant драйвер Postgres не умеет — через Timestamp.
                        rs.getTimestamp("set_at") == null ? null
                                : rs.getTimestamp("set_at").toInstant()),
                PAGE, offset);
        Long total = jdbc.queryForObject("""
                SELECT count(*)
                  FROM part p
                  LEFT JOIN part_applicability a ON a.part_id = p.id
                 WHERE p.product_line = 'PART'""", Long.class);
        return new Page(rows, total == null ? 0 : total, PAGE);
    }

    /** @param note пояснение, почему применимость не поставилась сама; пусто — нечего сказать */
    public record State(boolean engineMatters, String note) {
    }

    public record Row(long partId, long number, String title, boolean engineMatters,
                      String brand, String model, String generation,
                      Integer yearFrom, Integer yearTo, String setBy, Instant setAt) {
    }

    public record Page(List<Row> rows, long total, int size) {
    }
}
