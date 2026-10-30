package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Позиции машины после правки самой машины (задача 0383): исправленный год,
 * кузов или двигатель доходят до названия, истории позиции и дельты Дрома.
 *
 * <p>До неё правка машины (0364) меняла поля донора, а названия её деталей
 * оставались прежними: колонка «Год выпуска» на складе говорила 2007, название
 * — 2008, и продавец называл клиенту по телефону старый год.
 *
 * <p><b>Пересборка — тем же {@link PartTitles#retitle}, что у правки карточки
 * и смены донора</b>, второй формулы нет. Поэтому «что из своего у позиции
 * остаётся своим» решает правило {@link PartVehicle} — по полю: вписанный руками
 * год ({@code manual_year}) или «Год выпуска» Bazon ({@code year_text}) сильнее
 * года машины, а вписанная модель года не закрывает (решение 2 из PR 440 —
 * «пустое своё — как у донора, по полю»). Самих {@code manual_*} правка машины
 * не касается: донор тот же, меняются его данные (в отличие от
 * {@link PartDonorChange}, где смена донора свою машину позиции снимает).
 *
 * <p><b>Отметка для дельты — по объявлению, а не по названию.</b> Хранимое
 * название в файл Дрома не идёт (там своя формула, {@code DromTitle}), поэтому
 * отмечаются позиции, у которых изменилось то, что объявление берёт у машины
 * ({@link #LISTING_VEHICLE}), — одним вызовом {@link PartChangeLog#changed}.
 */
@Service
public class DonorRetitle {

    /**
     * Что файл Дрома берёт у машины позиции: марка, модель, кузов, двигатель
     * и год — теми же выражениями {@link PartVehicle}, что {@code DromPriceGenerator}
     * ({@code CAR_*}, {@code TITLE_*}) и {@code DromTitle} (год — {@code year_text},
     * иначе число). Применимость сюда не входит: правка машины её не меняет.
     * Отсекаются только проданные и списанные: их нет ни в одном файле (0339),
     * и название у них пересобирается, а отметка не ставится. Это шире
     * {@code DromPriceGenerator.IN_FILE}: отложенная целиком, черновик не из
     * поставки в пути и позиция чужого склада отмечаются, хотя в файл данной
     * выгрузки могут не попасть. Сузить до {@code IN_FILE} нельзя одним
     * запросом — условие зависит от выгрузки (склады, «резерв», «ожидается»),
     * а отметка одна на позицию. Лишняя отметка безвредна: отправитель дельты
     * отбирает тем же {@code IN_FILE} по каждой выгрузке, и не попавшей
     * позиции слать нечего.
     * Копия выражений здесь, а не ссылка, потому что {@code publishing} зависит
     * от склада, а не наоборот; расхождение ловит {@code DonorRetitleTest} сверкой
     * самого файла до и после правки по каждому полю формы.
     */
    static final String LISTING_VEHICLE = "SELECT p.id,\n"
            + PartVehicle.brand("db.name") + " AS brand,\n"
            + PartVehicle.model("dm.name") + " AS model,\n"
            + PartVehicle.body("d.body_code, dg.code") + " AS body,\n"
            + PartVehicle.engine("d.engine_code, dmo.engine_code") + " AS engine,\n"
            + "coalesce(p.year_text, (" + PartVehicle.year("d.year") + ")::text) AS year\n"
            + """
              FROM part p
              JOIN donor d ON d.id = p.donor_id
              LEFT JOIN catalog.brand db ON db.id = d.brand_id
              LEFT JOIN catalog.model dm ON dm.id = d.model_id
              LEFT JOIN catalog.generation dg ON dg.id = d.generation_id
              LEFT JOIN catalog.modification dmo ON dmo.id = d.modification_id
             WHERE p.donor_id = ? AND p.product_line = 'PART'
               AND p.status NOT IN ('SOLD', 'WRITTEN_OFF')
             ORDER BY p.id""";

    private final PartRepository parts;
    private final PartTitles titles;
    private final PartChangeLog partChanges;
    private final JdbcTemplate jdbc;

    public DonorRetitle(PartRepository parts, PartTitles titles, PartChangeLog partChanges,
                        JdbcTemplate jdbc) {
        this.parts = parts;
        this.titles = titles;
        this.partChanges = partChanges;
        this.jdbc = jdbc;
    }

    /**
     * Берёт позиции машины под блокировку и запоминает, что их объявления
     * берут у неё сейчас. Звать до записи машины, в той же транзакции.
     *
     * <p>Все линии {@code PART}, включая проданные и списанные: название —
     * производное, как и у разового пересчёта ({@link TitleRecount}). Колёса
     * не берутся: их название собирается из размеров, а не из машины. Каждая —
     * под блокировкой строки в порядке возрастания номера, как у
     * {@link PartDonorChange}: правка той же детали и правка машины идут
     * по очереди и не ждут друг друга насмерть.
     */
    @Transactional
    public Snapshot lock(long donorId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM part WHERE donor_id = ? AND product_line = 'PART' ORDER BY id",
                Long.class, donorId);
        List<Part> locked = new ArrayList<>(ids.size());
        for (Long id : ids) {
            parts.findByIdForUpdate(id).ifPresent(locked::add);
        }
        return new Snapshot(donorId, locked, listing(donorId));
    }

    /**
     * Пересобирает названия запомненных позиций по уже записанной машине
     * и отмечает для дельты те, чьё объявление стало другим.
     *
     * <p>Название меняется сущностью: история позиции получает «Наименование:
     * … → …», автор — тот, кто правил машину. Отметка ставится в этой же
     * транзакции: откатится правка машины — откатится и она.
     *
     * @return сколько позиций переименовано
     */
    @Transactional
    public int retitle(Snapshot before, Long authorId) {
        int renamed = 0;
        for (Part part : before.parts()) {
            if (titles.retitle(part)) {
                part.touchedBy(authorId);
                renamed++;
            }
        }
        parts.flush();
        Map<Long, List<String>> after = listing(before.donorId());
        List<Long> changed = new ArrayList<>();
        before.listing().forEach((id, was) -> {
            if (!Objects.equals(was, after.get(id))) {
                changed.add(id);
            }
        });
        partChanges.changed(changed);
        return renamed;
    }

    private Map<Long, List<String>> listing(long donorId) {
        Map<Long, List<String>> found = new LinkedHashMap<>();
        jdbc.query(LISTING_VEHICLE, rs -> {
            found.put(rs.getLong("id"), java.util.Arrays.asList(rs.getString("brand"),
                    rs.getString("model"), rs.getString("body"), rs.getString("engine"),
                    rs.getString("year")));
        }, donorId);
        return found;
    }

    /** Позиции машины под блокировкой и то, что их объявления брали у неё до правки. */
    public record Snapshot(long donorId, List<Part> parts, Map<Long, List<String>> listing) {
    }
}
