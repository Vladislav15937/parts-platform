package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Хранимый список кодов кузова своей машины позиции без донора —
 * {@code part.own_body_codes} (задача 0296, ответ владельца 26.10.2026:
 * «завести хранение списка кодов кузова в схеме»).
 *
 * <p><b>Зачем.</b> Считать список на лету значило считать его на каждой из
 * девяти тысяч позиций без донора, и дважды на запрос (счёт и страница):
 * отбор «Кузов = GRJ120» стоил 0,6 с против 0,4 с у отбора по марке.
 * Теперь колонка читается, а не считается.
 *
 * <p><b>Считает тот же SQL, что раньше показывал.</b> Выражение списка живёт
 * один раз — шаг 3 {@link CatalogService#OWN_VEHICLE}: какая машина своя,
 * какие строки, какое сужение до ряда из наименования. Здесь он только
 * запускается и результат записывается; копии правил в Java нет, и расхождения
 * «хранимое считает не так, как показывало» быть не может, пока стоит один
 * текст. Стережёт {@code OwnVehicleCodesTest}.
 *
 * <p><b>Чем платим, названо заранее</b> (образец — {@link PartChangeLog}).
 * Хранимое производное устаревает, если источник изменился, а пересчёт
 * не позвали. Источников три: строки применимости ({@link DromApplicabilityService},
 * {@link CatalogService} — заведение, снятие, разбор заголовков), наименование
 * ({@link PartService} при пересборке, {@link TitleRecount}) и донор у позиции
 * ({@link PartDonorChange}: у позиции с донором список пуст). Донор в чтении
 * побеждает и сам ({@code CASE WHEN p.donor_id IS NULL}), так что отставшая запись
 * у позиции с донором не видна. Страховка от забытого места:
 * «Разложить «Подходит»» ({@link DromApplicabilityService#applyAll}) пересчитывает
 * весь склад одним оператором. Триггера нет намеренно (корневой CLAUDE.md).
 *
 * <p>Запись только при отличии ({@code IS DISTINCT FROM}), без
 * {@code updated_at} и {@code part_change}: это пересчёт производного, а не
 * правка позиции.
 *
 * <p>Транзакция у вызывающего: аннотация {@code @Transactional} здесь —
 * ради search_path арендатора, а не ради границы.
 */
@Component
public class OwnVehicleCodes {

    /** Позиций в одном операторе: список в {@code ANY (?)} не растёт вместе со складом. */
    static final int CHUNK = 500;

    private final JdbcTemplate jdbc;

    public OwnVehicleCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Пересчёт для одной позиции. */
    @Transactional
    public void refresh(long partId) {
        refresh(List.of(partId));
    }

    /**
     * Пересчёт для позиций.
     *
     * @return сколько позиций сменили значение
     */
    @Transactional
    public int refresh(Collection<Long> partIds) {
        if (partIds == null || partIds.isEmpty()) {
            return 0;
        }
        List<Long> ids = partIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        int changed = 0;
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            changed += jdbc.update("""
                    UPDATE part t SET own_body_codes = u.codes
                      FROM (SELECT p.id, own.body_codes AS codes
                              FROM part p
                    """ + CatalogService.OWN_VEHICLE_OF_ROW + """

                             WHERE p.id = ANY (?)) u
                     WHERE t.id = u.id AND t.own_body_codes IS DISTINCT FROM u.codes""",
                    (Object) chunk.toArray(Long[]::new));
        }
        return changed;
    }

    /**
     * Пересчёт всего склада одним оператором — после разложения «Подходит»
     * и как страховка от забытого места.
     *
     * @return сколько позиций сменили значение
     */
    @Transactional
    public int refreshAll() {
        return jdbc.update("""
                UPDATE part t SET own_body_codes = u.codes
                  FROM (SELECT p.id, own.body_codes AS codes
                          FROM part p
                """ + CatalogService.OWN_VEHICLE + """

                       ) u
                 WHERE t.id = u.id AND t.own_body_codes IS DISTINCT FROM u.codes""");
    }
}
