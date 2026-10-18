package ru.partsflow.inventory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.catalog.OemNumbers;
import ru.partsflow.platform.audit.AuditJournal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Поля позиции, которых нет в сущности {@link Part}: номер позиции, номер
 * товара, оригинальность, номер объявления Дрома, номер производителя
 * с кросс-номерами и машина, вписанная у позиции руками (задача 0302).
 *
 * <p><b>Почему мимо сущности.</b> {@code number} и {@code public_code} раздаёт
 * база умолчанием при вставке, а оригинальность и номер объявления пишет
 * импорт прямым SQL. Отобразив их в {@link Part} для записи, мы поставили бы
 * их в каждый {@code UPDATE} приёмки: поле, не перечитанное после вставки,
 * ушло бы туда пустым. Номера производителя лежат в своей таблице
 * {@code part_oem}, и сущности у неё нет вовсе.
 *
 * <p><b>Журнал пишется руками, через {@link AuditJournal}</b>, той же записью
 * таблицы {@code part}, что у слушателя: история позиции и журнал организации
 * читают одну таблицу и показывают эти поля рядом с остальными. Снимок несёт
 * только изменённые поля — у соседних колонок «было» и «стало» пусты
 * одинаково, и история их правкой не считает.
 *
 * <p><b>Решение владельца продукта 15.10.2026</b>: «Клиент может править
 * у себя абсолютно всё… Никаких замков и запретов». Отказ здесь только там,
 * где значение записать нельзя физически (пустой номер товара, буквы в номере
 * объявления, номер, занятый другой позицией), и всегда словами.
 */
@Component
public class PartIdentity {

    private final JdbcTemplate jdbc;
    private final AuditJournal journal;

    public PartIdentity(JdbcTemplate jdbc, AuditJournal journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    /**
     * Что лежит у позиции сейчас.
     *
     * @param brand и остальные поля машины — только вписанное у позиции руками;
     *              пусто — показывается донор или применимость
     */
    public record Values(Long number, String code, String authenticity, String dromAdNumber,
                         String oem, String crosses,
                         String donorCode, String brand, String model, String generation,
                         String body, String engine, Integer year, String equipment) {
    }

    /**
     * Правка: {@code null} — поле не трогать (вкладка, открытая до выкладки,
     * этих ключей не шлёт, и стереть написанное ей нельзя), пустой текст —
     * очистить.
     */
    public record Edit(java.math.BigInteger number, String code, String authenticity, String dromAdNumber,
                       String oem, String crosses,
                       String donorCode, String brand, String model, String generation,
                       String body, String engine, String year, String equipment) {

        static Edit none() {
            return new Edit(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Values of(Long partId) {
        return jdbc.queryForObject("""
                SELECT p.number, p.public_code, p.authenticity, p.drom_ad_number,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       (SELECT string_agg(o.raw_number, ', ' ORDER BY o.id) FROM part_oem o
                         WHERE o.part_id = p.id AND NOT o.is_primary) AS crosses,
                       p.manual_donor_code, p.manual_brand, p.manual_model,
                       p.manual_generation, p.manual_body, p.manual_engine,
                       p.manual_year, p.manual_equipment
                  FROM part p WHERE p.id = ?""",
                (rs, i) -> new Values(rs.getLong("number"), rs.getString("public_code"),
                        rs.getString("authenticity"), rs.getString("drom_ad_number"),
                        rs.getString("oem"), rs.getString("crosses"),
                        rs.getString("manual_donor_code"), rs.getString("manual_brand"),
                        rs.getString("manual_model"), rs.getString("manual_generation"),
                        rs.getString("manual_body"), rs.getString("manual_engine"),
                        (Integer) rs.getObject("manual_year"), rs.getString("manual_equipment")),
                partId);
    }

    /**
     * Что изменилось. Прежние и новые значения номера производителя здесь
     * не нужны: с задачи 0304 правка номера названия позиции не трогает.
     *
     * @param changed изменилось ли хоть что-то
     */
    public record Applied(boolean changed) {
    }

    /**
     * Записывает тронутое.
     *
     * <p>Только внутри транзакции правки карточки ({@code PartService.update}):
     * журнал и запись обязаны попасть в базу вместе, а без транзакции
     * {@code JdbcTemplate} ушёл бы в {@code public}.
     *
     * @return что изменилось
     */
    @Transactional
    public Applied apply(Long partId, Edit edit, Long authorId) {
        Values was = of(partId);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        Long number = edit.number() == null ? null : numberOf(edit.number());
        if (number != null && !number.equals(was.number())) {
            claimNumber(partId, number);
            column("number", was.number(), number, before, after, sets, args);
        }
        if (edit.code() != null) {
            String code = edit.code().strip();
            if (code.isEmpty()) {
                throw new IllegalArgumentException("Номер товара не может быть пустым: по нему"
                        + " позицию находят на витрине, на этикетке и сканером");
            }
            if (!code.equals(was.code())) {
                requireFree(partId, "public_code", code, "Номер товара");
                column("public_code", was.code(), code, before, after, sets, args);
            }
        }
        text("authenticity", was.authenticity(), edit.authenticity(), before, after, sets, args);
        if (edit.dromAdNumber() != null) {
            String ad = dromAdNumber(edit.dromAdNumber());
            if (!Objects.equals(ad, was.dromAdNumber())) {
                if (ad != null) {
                    requireFree(partId, "drom_ad_number", ad, "Номер объявления Дрома");
                }
                column("drom_ad_number", was.dromAdNumber(), ad, before, after, sets, args);
            }
        }
        text("manual_donor_code", was.donorCode(), edit.donorCode(), before, after, sets, args);
        text("manual_brand", was.brand(), edit.brand(), before, after, sets, args);
        text("manual_model", was.model(), edit.model(), before, after, sets, args);
        text("manual_generation", was.generation(), edit.generation(), before, after, sets, args);
        text("manual_body", was.body(), edit.body(), before, after, sets, args);
        text("manual_engine", was.engine(), edit.engine(), before, after, sets, args);
        if (edit.year() != null) {
            Integer year = year(edit.year());
            if (!Objects.equals(year, was.year())) {
                column("manual_year", was.year(), year, before, after, sets, args);
            }
        }
        text("manual_equipment", was.equipment(), edit.equipment(), before, after, sets, args);

        if (!sets.isEmpty()) {
            args.add(authorId);
            args.add(partId);
            jdbc.update("UPDATE part SET " + String.join(", ", sets)
                    + ", updated_at = now(), updated_by = ? WHERE id = ?", args.toArray());
        }

        boolean oemChanged = applyOem(partId, was, edit, before, after);
        if (oemChanged && sets.isEmpty()) {
            jdbc.update("UPDATE part SET updated_at = now(), updated_by = ? WHERE id = ?",
                    authorId, partId);
        }

        Applied applied = new Applied(!after.isEmpty());
        if (after.isEmpty()) {
            return applied;
        }
        journal.changed("part", partId, before, after);
        return applied;
    }

    /**
     * Ответ на одновременную правку: соседняя позиция успела занять номер
     * между нашей проверкой и записью, и уникальный индекс отбил запись.
     * Читается новой транзакцией — прежняя помечена на откат, — и отвечает
     * теми же словами, что проверка до записи. Не нашлось — исходная ошибка
     * остаётся ошибкой.
     */
    @Transactional(readOnly = true)
    public void explainConflict(Long partId, Edit edit, String barcode, String legacyCode,
                                DataIntegrityViolationException original) {
        if (edit.number() != null && edit.number().signum() > 0
                && edit.number().compareTo(java.math.BigInteger.valueOf(NUMBER_MAX)) <= 0) {
            requireNumberFree(partId, edit.number().longValueExact());
        }
        if (edit.code() != null && !edit.code().isBlank()) {
            requireFree(partId, "public_code", edit.code().strip(), "Номер товара");
        }
        if (edit.dromAdNumber() != null && !edit.dromAdNumber().isBlank()) {
            String ad = dromAdNumber(edit.dromAdNumber());
            if (ad != null) {
                requireFree(partId, "drom_ad_number", ad, "Номер объявления Дрома");
            }
        }
        if (barcode != null && !barcode.isBlank()) {
            requireFree(partId, "barcode", barcode.strip(), "Штрихкод");
        }
        if (legacyCode != null && !legacyCode.isBlank()) {
            requireFree(partId, "legacy_code", legacyCode.strip(), "Старые данные");
        }
        throw original;
    }

    /**
     * Занятое значение называет позицию публичным кодом — по нему её находят
     * на витрине, — а не номером строки базы (правило корневого CLAUDE.md,
     * «Штрихкод, занятый другой позицией»).
     */
    void requireFree(Long partId, String column, String value, String label) {
        List<String> taken = jdbc.queryForList(
                "SELECT public_code FROM part WHERE " + column + " = ? AND id <> ?",
                String.class, value, partId);
        if (!taken.isEmpty()) {
            throw new IllegalStateException("%s «%s» уже стоит у позиции %s — найдите её"
                    .formatted(label, value, taken.get(0)) + " на витрине по этому коду");
        }
    }

    /**
     * Наибольший «№ позиции» — граница типа, а не запрет (задача 0303).
     *
     * <p>Колонка и последовательность — {@code bigint}, до 9 223 372 036 854 775 807,
     * но номер показывают экраны, а число в браузере точно только до
     * 2<sup>53</sup> − 1 = 9 007 199 254 740 991: всё, что больше, витрина,
     * карточка, продавец и файл покажут округлённым, то есть «позицией 347»
     * назовут другую. Значит настоящая граница — эта, и отказ за ней говорит
     * словами, почему.
     */
    static final long NUMBER_MAX = 9_007_199_254_740_991L;

    /**
     * Номер из запроса: целое больше нуля и не больше {@link #NUMBER_MAX}.
     * Приходит {@code BigInteger}, а не {@code Long}: номер за пределом
     * {@code long} иначе отбил бы разбор тела, и человек прочитал бы
     * «запрос не разобран» вместо причины.
     */
    private static Long numberOf(java.math.BigInteger number) {
        if (number.signum() <= 0) {
            throw new IllegalArgumentException("№ позиции — целое число больше нуля");
        }
        if (number.compareTo(java.math.BigInteger.valueOf(NUMBER_MAX)) > 0) {
            throw new IllegalArgumentException(("№ позиции %s больше наибольшего возможного"
                    + " (9 007 199 254 740 991): такое число экран не покажет точно,"
                    + " и позицию назовут другим номером").formatted(number));
        }
        return number.longValueExact();
    }

    /**
     * Занимает номер, вписанный руками, и двигает за ним нумерацию.
     *
     * <p><b>Предела «не дальше 100 000» больше нет</b> (задача 0303, решение
     * владельца продукта 15.10.2026: снять). Он стоял из-за скорости: PR 440
     * продвигал последовательность шагами {@code nextval}, и миллионы шагов
     * занимали минуты. Теперь сдвиг — одна инструкция {@code setval}; рабочей
     * роли на неё выдан {@code UPDATE} одной этой последовательности
     * ({@code SchemaGrants}).
     *
     * <p><b>Гонка с приёмкой закрыта блокировкой таблицы, а не надеждой.</b>
     * Номер выше выданных — это номер, который приёмка выдаст следующим.
     * Между проверкой «свободен» и сдвигом нумерации приёмка успела бы взять
     * его же, и одна из двух операций получила бы отказ уникального индекса —
     * хуже всего приёмка, которую телефон уведёт в «требует внимания».
     * {@code SHARE ROW EXCLUSIVE} на {@code part} пропускает чтение и ждёт
     * всех, кто в эту таблицу пишет: начатые приёмки дописываются и становятся
     * видны проверке, новые ждут конца правки (миллисекунды) и получают номер
     * уже после вписанного. Две такие правки тоже идут по очереди: вторая
     * увидит номер первой и ответит словами.
     *
     * <p>Номер не выше выданных блокировки не требует: нумерация его уже
     * прошла, и столкнуться с ним может только существующая позиция — её
     * ловит проверка, а одновременную — уникальный индекс ({@link #explainConflict}).
     */
    private void claimNumber(Long partId, long number) {
        if (number > lastIssued()) {
            lockNumbering();
            requireNumberFree(partId, number);
            // Под блокировкой нумерацию никто не двигает, но перечитать
            // дешевле, чем полагаться: setval назад раздал бы номера второй раз.
            jdbc.queryForObject("SELECT setval('part_number_seq', greatest(?,"
                    + " (SELECT last_value FROM part_number_seq)))", Long.class, number);
        } else {
            requireNumberFree(partId, number);
        }
    }

    private void lockNumbering() {
        try {
            jdbc.execute("LOCK TABLE part IN SHARE ROW EXCLUSIVE MODE");
        } catch (org.springframework.dao.PessimisticLockingFailureException busy) {
            // lock_timeout (5 с) — склад держит долгая запись: перенос, правка
            // списком. Повтор через минуту пройдёт; пятисотка увела бы искать
            // поломку сервера.
            throw new IllegalStateException("Нумерацию позиций сейчас занимает другая операция"
                    + " со складом (перенос или правка списком) — сохраните № позиции"
                    + " ещё раз через минуту", busy);
        }
    }

    private void requireNumberFree(Long partId, Long number) {
        List<String> taken = jdbc.queryForList(
                "SELECT public_code FROM part WHERE number = ? AND id <> ?",
                String.class, number, partId);
        if (!taken.isEmpty()) {
            throw new IllegalStateException(("№ позиции %d уже у позиции %s — найдите её"
                    + " на витрине по этому коду").formatted(number, taken.get(0)));
        }
    }

    private Long lastIssued() {
        return jdbc.queryForObject(
                "SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END"
                        + " FROM part_number_seq", Long.class);
    }

    /**
     * Номер производителя и каждый кросс-номер не длиннее, чем можно сохранить
     * ({@link OemNumbers#tooLong}, задача 0312) — отказ словами, 400.
     *
     * <p>Зовётся из {@code PartService.update} первым делом, до сверки чужой
     * правки (0313) и до любой записи: отказ здесь — про сам запрос, а не про
     * то, что успел сделать другой, и половины правки за собой не оставляет.
     */
    static void requireStorable(Edit edit) {
        if (edit == null) {
            return;
        }
        // Каждый номер поля отдельно (задача 0323): «A, B» — два номера, и предел
        // 0312 у каждого свой, а не у строки целиком.
        String problem = edit.oem() == null ? null : OemNumbers.primaryTooLong(edit.oem());
        if (problem == null && edit.crosses() != null) {
            for (String cross : OemNumbers.list(edit.crosses())) {
                problem = OemNumbers.tooLong(cross, true);
                if (problem != null) {
                    break;
                }
            }
        }
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    /**
     * Номер производителя — главная строка {@code part_oem}, кросс-номера —
     * остальные. Приведение тем же {@link OemNumbers#normalize}, что у приёмки
     * и поиска: разойдись они, номер переставал бы находиться.
     *
     * <p>Набор переписывается целиком, когда тронут хоть один из двух: номер,
     * ставший главным, мог стоять среди замен, и строка {@code (part_id,
     * normalized)} столкнулась бы сама с собой.
     */
    private boolean applyOem(Long partId, Values was, Edit edit,
                             Map<String, Object> before, Map<String, Object> after) {
        if (edit.oem() == null && edit.crosses() == null) {
            return false;
        }
        // У детали один номер производителя, остальное — кросс-номера (задача 0323):
        // «8414012530, 8414012531» — основной первый, второй встаёт кросс-номером.
        // Лишние номера идут первыми, перед набранными или хранимыми: они стояли
        // рядом с основным, и там их человек и вписал (решение исполнителя, п. 3).
        OemNumbers.Primary typed = edit.oem() == null ? null : OemNumbers.primary(edit.oem());
        String primary = typed == null ? was.oem() : typed.primary();
        // Нетронутое поле переписывается тем, что лежит, а не прочитанным
        // заново из склеенной строки: иначе правка одного номера производителя
        // привела бы к латинице и кросс-номера, которых человек не менял,
        // и история назвала бы правку, которой не было (задача 0309, п. 4).
        List<String> crosses = new ArrayList<>(typed == null ? List.of() : typed.crosses());
        crosses.addAll(edit.crosses() == null
                ? storedCrosses(partId) : OemNumbers.list(edit.crosses()));

        Set<String> seen = new LinkedHashSet<>();
        String primaryNormalized = primary == null ? null : OemNumbers.normalize(primary);
        if (primaryNormalized != null) {
            seen.add(primaryNormalized);
        }
        List<String> keptCrosses = new ArrayList<>();
        for (String cross : crosses) {
            String normalized = OemNumbers.normalize(cross);
            if (normalized != null && seen.add(normalized)) {
                keptCrosses.add(cross);
            }
        }
        String primaryShown = primaryNormalized == null ? null : primary;
        String crossesShown = keptCrosses.isEmpty() ? null : String.join(", ", keptCrosses);

        boolean changed = false;
        if (!Objects.equals(primaryShown, was.oem())) {
            before.put("oem", was.oem());
            after.put("oem", primaryShown);
            changed = true;
        }
        if (!Objects.equals(crossesShown, was.crosses())) {
            before.put("crosses", was.crosses());
            after.put("crosses", crossesShown);
            changed = true;
        }
        if (!changed) {
            return false;
        }
        jdbc.update("DELETE FROM part_oem WHERE part_id = ?", partId);
        if (primaryShown != null) {
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                    + " VALUES (?, ?, ?, true)", partId, primaryShown, primaryNormalized);
        }
        for (String cross : keptCrosses) {
            jdbc.update("INSERT INTO part_oem (part_id, raw_number, normalized, is_primary)"
                    + " VALUES (?, ?, ?, false)", partId, cross, OemNumbers.normalize(cross));
        }
        return true;
    }

    /** Кросс-номера позиции как лежат, в порядке заведения. */
    private List<String> storedCrosses(Long partId) {
        return jdbc.queryForList("SELECT raw_number FROM part_oem"
                + " WHERE part_id = ? AND NOT is_primary ORDER BY id", String.class, partId);
    }

    /** «№ 125696381» и «125696381» — одно; пусто — номера нет. */
    private static String dromAdNumber(String raw) {
        String stripped = raw.strip();
        if (stripped.startsWith("№")) {
            stripped = stripped.substring(1).strip();
        }
        if (stripped.isEmpty()) {
            return null;
        }
        if (!stripped.matches("[0-9]+")) {
            throw new IllegalArgumentException("Номер объявления Дрома — только цифры:"
                    + " №125696381 на карточке объявления и в конце адреса его страницы");
        }
        return stripped;
    }

    private static Integer year(String raw) {
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        try {
            int year = Integer.parseInt(stripped);
            if (year > 0 && year < 10_000) {
                return year;
            }
        } catch (NumberFormatException notANumber) {
            // ниже — тот же отказ словами
        }
        throw new IllegalArgumentException("Год выпуска — число, например 2007");
    }

    private static void text(String column, String was, String sent,
                             Map<String, Object> before, Map<String, Object> after,
                             List<String> sets, List<Object> args) {
        if (sent == null) {
            return;
        }
        String now = sent.strip().isEmpty() ? null : sent.strip();
        if (!Objects.equals(now, was)) {
            column(column, was, now, before, after, sets, args);
        }
    }

    private static void column(String column, Object was, Object now,
                               Map<String, Object> before, Map<String, Object> after,
                               List<String> sets, List<Object> args) {
        before.put(column, was);
        after.put(column, now);
        sets.add(column + " = ?");
        args.add(now);
    }
}
