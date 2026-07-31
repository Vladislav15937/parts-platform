package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.inventory.PartTitleGenerator;

import java.util.List;

/**
 * Достаёт из донора части заголовка: марку, модель, кузов, двигатель, год.
 *
 * <p>Донор хранит ссылки в общий каталог, а заголовок собирается из названий,
 * поэтому нужен один запрос с join'ами. Отдельный класс, а не метод в сервисе:
 * это единственное место, где приёмка вообще заглядывает в каталог машин.
 *
 * <p><b>Пустые поля — нормальный режим, а не ошибка.</b> Каталог марок
 * и моделей ещё не наполнен, и на неполных данных заголовок получится короче:
 * «Фара левая перед. лев. (б/у) 8414012530» вместо полного. Это лучше, чем
 * отказать в приёмке, — генератор заголовка сознательно терпит null во всех
 * частях, кроме вида детали.
 */
@Component
public class DonorVehicleResolver {

    private final JdbcTemplate jdbc;

    public DonorVehicleResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return части заголовка или {@code null}, если донора нет — контрактная деталь */
    public PartTitleGenerator.VehicleTitlePart resolve(Long donorId) {
        if (donorId == null) {
            return null;
        }
        List<PartTitleGenerator.VehicleTitlePart> found = jdbc.query("""
                SELECT b.name  AS brand,
                       m.name  AS model,
                       g.code  AS body_code,
                       mo.engine_code,
                       d.year
                  FROM donor d
                  LEFT JOIN catalog.brand b        ON b.id = d.brand_id
                  LEFT JOIN catalog.model m        ON m.id = d.model_id
                  LEFT JOIN catalog.generation g   ON g.id = d.generation_id
                  LEFT JOIN catalog.modification mo ON mo.id = d.modification_id
                 WHERE d.id = ?""",
                (rs, i) -> new PartTitleGenerator.VehicleTitlePart(
                        rs.getString("brand"),
                        rs.getString("model"),
                        rs.getString("body_code"),
                        rs.getString("engine_code"),
                        rs.getObject("year") == null ? null : rs.getInt("year")),
                donorId);

        return found.isEmpty() ? null : found.get(0);
    }
}
