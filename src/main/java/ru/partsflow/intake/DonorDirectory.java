package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Список машин целиком — для экрана машин, а не для приёмки.
 *
 * <p><b>Отличается от справочника приёмки составом, и это не дублирование.</b>
 * Приёмщику показывают только то, что в разборе: деталь, снятая с машины,
 * которую ещё везут, — это ошибка выбора, а не работа. Владельцу нужны все:
 * купленную машину он ставит в разбор, и за неё же платит эвакуатору
 * до того, как с неё снимут первую деталь.
 */
@Service
public class DonorDirectory {

    private final JdbcTemplate jdbc;

    public DonorDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Внутри транзакции обязательно: {@code search_path} выставляет провайдер
     * соединений Hibernate, и запрос снаружи уходит в {@code public}.
     */
    @Transactional(readOnly = true)
    public List<Entry> all() {
        return jdbc.query("""
                SELECT d.id, d.public_code, d.vin, d.year, d.status, d.note,
                       b.name AS brand, m.name AS model
                  FROM donor d
                  LEFT JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                 ORDER BY d.id DESC""",
                (rs, i) -> new Entry(
                        rs.getLong("id"),
                        rs.getString("public_code"),
                        rs.getString("brand"),
                        rs.getString("model"),
                        rs.getObject("year") == null ? null : rs.getInt("year"),
                        rs.getString("vin"),
                        rs.getString("status"),
                        rs.getString("note")));
    }

    public record Entry(long id, String publicCode, String brand, String model,
                        Integer year, String vin, String status, String note) {
    }
}
