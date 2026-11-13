package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Номер донора: какой подставить в форму и какой выдать машине (задача 0364).
 *
 * <p><b>«Следующий» — это наибольший плюс один, а не очередное значение
 * последовательности.</b> Номер, вписанный руками в форме или пришедший
 * переносом с Bazon, последовательность не двигает (tenant/108): вписали
 * 700 — последовательность всё ещё на 513, и следующая машина без номера
 * получила бы 513, а через двести машин упёрлась бы в занятый 700.
 * Подвинуть последовательность нечем: {@code setval} требует права
 * {@code UPDATE}, а у рабочей роли оно есть только на нумерацию позиций
 * ({@code SchemaGrants}). Поэтому номер выдаётся выражением
 * {@code greatest(nextval, max + 1)} — последовательность при этом не
 * отстаёт от выданного больше, чем на вписанные руками, а заведение
 * прежним образом (умолчание колонки) берёт её значение как раньше.
 *
 * <p><b>Под рекомендательной блокировкой на схему</b>, а не проверкой
 * чтением: «наибольший плюс один» два одновременных заведения читают
 * одинаково, и проигравшего отбил бы уникальный индекс словами «нарушение
 * целостности». Блокировка транзакционная — сессионная протекла бы через
 * PgBouncer, как {@code SET search_path}. Держится она до коммита
 * вызывающего, поэтому метод {@code MANDATORY}: без внешней транзакции
 * номер освободился бы раньше, чем машина с ним записана.
 */
@Component
public class DonorNumbers {

    private static final String LOCK =
            "SELECT pg_advisory_xact_lock(hashtext(current_schema() || '.donor_number'))";

    private final JdbcTemplate jdbc;

    public DonorNumbers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Номер для машины.
     *
     * @param wanted  вписанный человеком; {@code null} — выдать следующий
     * @param donorId машина, которой номер уже принадлежит (правка), —
     *                свой номер свободен для неё самой; {@code null} при заведении
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long assign(Long wanted, Long donorId) {
        jdbc.query(LOCK, rs -> null);
        if (wanted == null) {
            return jdbc.queryForObject("""
                    SELECT greatest(nextval('donor_number_seq'),
                                    coalesce((SELECT max(number) FROM donor), 0) + 1)""", Long.class);
        }
        if (wanted <= 0) {
            throw new IllegalArgumentException("Номер донора — целое число больше нуля");
        }
        Boolean taken = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM donor WHERE number = ? AND id IS DISTINCT FROM ?)",
                Boolean.class, wanted, donorId);
        if (Boolean.TRUE.equals(taken)) {
            throw new IllegalStateException("Номер донора " + wanted
                    + " уже у другой машины — выберите другой или оставьте предложенный");
        }
        return wanted;
    }

    /**
     * Какой номер подставить в форму заведения.
     *
     * <p>Подсмотр, а не выдача: последовательность не двигается, номер
     * никому не обещан. Две формы, открытые разом, покажут одно число, и
     * вторая при сохранении получит отказ «уже у другой машины» — поэтому
     * форма шлёт номер, только если человек его изменил, а иначе сервер
     * выдаёт следующий сам.
     */
    @Transactional(readOnly = true)
    public long peek() {
        return jdbc.queryForObject("""
                SELECT greatest(CASE WHEN s.is_called THEN s.last_value + 1 ELSE s.last_value END,
                                coalesce((SELECT max(number) FROM donor), 0) + 1)
                  FROM donor_number_seq s""", Long.class);
    }
}
