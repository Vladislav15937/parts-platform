package ru.partsflow.inventory;

import ru.partsflow.shared.PartNumberQuery;
import ru.partsflow.shared.SupplyKinds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.partsflow.catalog.VehicleWords;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Витрина склада: таблица товаров, как её видит владелец.
 *
 * <p>Отдельно от поиска продавца ({@code PartService.searchAvailable}) и это
 * не дублирование. Продавец ищет, что можно продать прямо сейчас, и ему нужны
 * пять полей и свободный остаток. Владелец смотрит склад целиком: двадцать
 * с лишним колонок, сортировка по любой, постранично по тридцать пять тысяч
 * позиций. Один запрос на оба сценария был бы плох для обоих.
 *
 * <p><b>Колонки складов не фиксированы.</b> У клиента их два — «Ткацкая»
 * и «54 YARD», — у другого будет пять, и остаток по каждому складу идёт
 * своей колонкой. Поэтому склады отдаются отдельным списком, а остатки
 * строки — картой «склад → количество».
 *
 * <p><b>Сортировка задаётся именем колонки, а не строкой в SQL.</b>
 * Подставлять в {@code ORDER BY} то, что пришло из запроса, — это внедрение
 * SQL; поэтому имя ищется в белом списке, а не экранируется.
 */
@Service
public class CatalogService {

    /**
     * Условие на приведённый номер производителя (задача 0306) — одно на витрину
     * и продавца; аргумент — {@link #oemNormalizedArg}. Номер записан
     * «ab-123 456x», покупатель называет «AB123456X»: подстрокой по набранному
     * не найти, по приведённому — да. Ключ состоит из одних [A-Z0-9], поэтому
     * знаков шаблона в нём не бывает; идёт по триграммному {@code part_oem_trgm}.
     */
    static final String OEM_NORMALIZED = "normalized LIKE ?";

    /** Ветка {@code UNION} с {@link #OEM_NORMALIZED} — как {@code PartNumberQuery.UNION_BRANCH}. */
    static final String OEM_NORMALIZED_BRANCH =
            "\n                          UNION SELECT part_id FROM part_oem WHERE " + OEM_NORMALIZED;

    /** Подстрока по приведённому виду: «123» находит и «AB1234», как и по набранному. */
    static String oemNormalizedArg(String key) {
        return "%" + key + "%";
    }


    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    /**
     * Наименование позиции с приведёнными к латинице смешанными словами (задача 0298):
     * слово, где есть и латинская, и кириллическая буква, читается латиницей, чисто русское
     * остаётся как есть. Список двойников один с {@code MixedAlphabet}. Дешёвая проверка
     * впереди (латинская буква вплотную к кириллической) отсекает почти все наименования,
     * поэтому разбор по словам идёт только там, где он что-то меняет. Длина не меняется,
     * и позиции в {@code strpos} годятся для среза исходного наименования.
     */
    private static final String MIXED_TITLE = "CASE WHEN op.title ~ '[A-Za-z][А-Яа-яЁё]|[А-Яа-яЁё][A-Za-z]'"
            + " THEN (SELECT string_agg(CASE WHEN w.s ~ '[A-Za-z]' AND w.s ~ '[А-Яа-яЁё]'"
            + " THEN translate(w.s, '" + ru.partsflow.catalog.MixedAlphabet.SQL_CYRILLIC
            + "', '" + ru.partsflow.catalog.MixedAlphabet.SQL_LATIN + "')"
            + " ELSE w.s END, '' ORDER BY w.n)"
            + " FROM (SELECT m.g[1] AS s, m.n"
            + " FROM regexp_matches(op.title,"
            + " '[A-Za-z0-9А-Яа-яЁё]+|[^A-Za-z0-9А-Яа-яЁё]+', 'g') WITH ORDINALITY AS m(g, n)) w)"
            + " ELSE op.title END";

    /**
     * Своя машина позиции без донора — та, деталью которой запчасть
     * и является, из её применимости (задача 0290).
     *
     * <p><b>Своя — значит названная в наименовании клиента, а не любая
     * из применимости.</b> Решение владельца продукта, 14.10.2026, дословно:
     * «Только ту марку модель которой и является запчасть. Для всего
     * остального есть применимость» ({@code tasks/0290}, раздел «Вопрос
     * владельцу — что показывать, когда у позиции несколько машин»).
     * На Дроме у «Карданного вала … Toyota Land Cruiser Prado 120» блок
     * «Подходит» называет Lexus GX400, 4Runner и Hilux Surf, а самого Prado
     * может не назвать вовсе: колонка «Марка» с Lexus была бы неправдой
     * о детали, а «Lexus, Toyota +2» — перечнем, за которым есть карточка.
     *
     * <p>Поэтому из строк применимости берутся только те, чья марка стоит
     * в наименовании, и из них — названная раньше; модель — та, что тоже
     * стоит в наименовании (при одном месте — длиннейшая: «Land Cruiser»
     * и «Land Cruiser Prado» начинаются с одной буквы). Марка названа,
     * а модель нет («Блок управления зеркалами Toyota», «Land Сruiser»
     * с кириллической «С») — показывается одна марка: что деталь от Toyota,
     * клиент сказал сам, а какой модели — нет.
     *
     * <p><b>Одним проходом по применимости, а не подзапросом на позицию.</b>
     * Первая редакция была {@code LEFT JOIN LATERAL} на каждую строку склада:
     * сортировка по марке на складе в 35 841 позицию стоила 3,4 с через HTTP —
     * девять тысяч маленьких группировок. Один {@code DISTINCT ON} по всей
     * применимости — около 140 мс в базе (замер в PR задачи 0290). Строку
     * страницы та же запись украшает сужённой до позиции
     * ({@link #OWN_VEHICLE_OF_ROW}): текст один, сужение — подставкой.
     *
     * <p>Сравнение — вхождением без учёта регистра, а не целым словом:
     * строки применимости и так ставит разбор наименования или карточки,
     * то есть «Kia» внутри «Kiabi» сюда не доедет, а регулярное выражение
     * на каждую строку склада стоило бы заметно дороже.
     *
     * <p><b>Поколение и годы — только если у своей машины поколение одно.</b>
     * У 1 107 из 2 897 позиций перенесённого клиента блок называет у той же
     * модели два-три поколения, и «Prado 120» с блоком «4 поколение
     * 2017–2024; рестайлинг 2013–2017» — обычное дело: деталь подходит
     * к нескольким, а снята с одной. Первое по порядку блока было бы
     * догадкой, выданной за факт; пусто с подсказкой «поколений несколько»
     * — правда. При одном поколении блок совпадает с наименованием в 975
     * случаях из 981 (замер по файлу клиента: «Prado 120» → «3 поколение»).
     *
     * <p><b>И одно поколение — только если его кузова того же ряда, что номер
     * в наименовании</b> (задача 0294). Шесть из 981 не совпали, и это не шум:
     * Дром складывает ряды одной модели в один блок, и «Втулка … Prado 120»
     * с единственным поколением 2009 года (кузова GRJ150) показала бы чужое
     * поколение как факт. Поэтому, если сразу после модели в наименовании
     * стоит отдельный номер из двух-трёх цифр ({@code title_body}: «Prado 120»,
     * но не «RX350» — там число и есть модель, и не «120/150» — это не один
     * кузов), он сверяется с кузовами из примечания строки поколения
     * («Кузова: GRJ150W, …», пишет разбор блока, 0289). Ряд — номер без
     * последней цифры: 120 и 121 — ряд «12», 95 и 90 — «9».
     *
     * <p><b>Показывается только подтверждённое (задача 0299, принцип
     * владельца 14.10.2026: «если я не уверен я не хочу ставить, только при
     * уверенности»).</b> {@code body_confirmed} — {@code true}, только когда
     * среди кузовов есть код того же ряда. Кузовов в примечании нет вовсе —
     * сверять не с чем, {@code false}, поколение пусто (PR 419 ставил здесь
     * правило 0290). Двузначный номер сокращением трёхзначного не считается:
     * «Prado 12» — ряд «1», с кузовами 120 не совпадает. Номера в наименовании
     * нет — {@code body_confirmed} пуст, правило 0290 как было: принцип на
     * такие строки эта задача не распространяет.
     *
     * <p><b>Рестайлинги одного поколения Дрома — одно поколение (задача 0297,
     * ответ владельца «целиком»).</b> Справочник делит поколение Дрома на
     * строки рестайлингов, и «поколений три» у «4 поколение; рестайлинг;
     * 2-й рестайлинг» было бы неправдой. Если все строки поколения своей
     * машины несут одну метку Дрома ({@code generation_label}, её пишет
     * разбор блока словами клиента) — {@code one_drom}: колонка показывает
     * метку, годы от первой строки до последней. Строка без метки или две
     * метки — как прежде. Номер кузова в наименовании сверяется с каждой
     * строкой до объединения ({@code bodies_each}).
     *
     * <p><b>Запись — три шага, вложенных снизу вверх, и это ради цены,
     * а не порядка.</b> Шаг 1 — своя машина ({@code DISTINCT ON}
     * по применимости, 0290); шаг 2 — номер кузова из наименования; шаг 3 —
     * сверка его с кузовами. Шаги 2 и 3 идут один раз на позицию над уже
     * выбранной машиной: то же выражение на каждой строке применимости
     * удваивало проход по складу (228 → 431 мс в базе на 35 841 позиции,
     * замер в PR задачи 0294), а над выбранной стоит около 40 мс. Следующие
     * правки своей машины (поколение по коду кузова, рестайлинг) ложатся
     * сюда же — колонкой шага 2–3 или своим шагом сверху, а не выражением
     * на строку шага 1.
     *
     * <p><b>Донор побеждает:</b> позиции с донором в подзапрос не входят
     * ({@code op.donor_id IS NULL}), и колонки берут его машину, как до задачи.
     *
     * <p>Позиция названа {@code p}, машина донора — {@code b}, {@code m},
     * {@code g}, как в {@link #JOINS}.
     */
    private static final String OWN_VEHICLE_OF = """

              LEFT JOIN %s(
                -- Шаг 3 (0294, 0299): подтверждён ли номер кузова из наименования
                -- кузовами поколения своей машины. Номера нет — пусто (правило
                -- 0290); номер есть, а кузовов нет — не подтверждён.
                -- Сверяется каждая строка поколения отдельно, до объединения
                -- рестайлингов (0297): строка без кузовов или с чужим рядом
                -- не подтверждает и всё поколение Дрома. При одной строке это
                -- то же, что прежде.
                SELECT n.*,
                       CASE WHEN n.title_body IS NOT NULL
                            THEN (SELECT coalesce(bool_and(e.b ~ ('(^|, )[^,0-9]*'
                                                                  || left(n.title_body, -1)
                                                                  || '[0-9]([^0-9]|$)')), false)
                                    FROM unnest(n.bodies_each) e(b))
                       END AS body_confirmed
                  FROM (
                -- Шаг 2 (0294): номер кузова сразу после модели. «Prado 120» → 120;
                -- «RX350», «Prado120», «Prado 120/150» → пусто. Проверка
                -- сопоставлением, а цифры — строковыми функциями: substring
                -- с группой захвата стоил в Postgres вдесятеро дороже
                -- (62 мс против 5 на 9 417 позициях, замер в PR задачи 0294).
                SELECT o.*,
                       -- Поколение и годы, которые колонка покажет: поколение Дрома
                       -- целиком (годы от первой строки до последней) или, без
                       -- метки, единственная строка справочника, как в 0290.
                       CASE WHEN o.one_drom THEN o.drom_generation
                            ELSE o.catalog_generation END AS generation,
                       CASE WHEN o.one_drom THEN o.year_from_min
                            ELSE o.year_from_max END AS year_from,
                       CASE WHEN o.one_drom AND o.up_to_now THEN NULL
                            ELSE o.year_to_max END AS year_to,
                       CASE WHEN o.after_model ~ '^ +[0-9]{2,3}([^[:alnum:]/]|$)'
                            THEN left(ltrim(o.after_model),
                                      CASE WHEN strpos('0123456789',
                                                       substr(ltrim(o.after_model), 3, 1)) > 0
                                           THEN 3 ELSE 2 END)
                       END AS title_body
                  FROM (
                -- Шаг 1 (0290): своя машина — строки применимости, названные в наименовании.
                  SELECT DISTINCT ON (x.part_id) x.part_id, x.brand, x.model,
                         count(DISTINCT (x.generation_id, x.year_from, x.year_to))
                             FILTER (WHERE x.model IS NOT NULL
                                       AND (x.generation_id IS NOT NULL
                                            OR x.year_from IS NOT NULL)) AS spans,
                         -- Все строки поколения своей машины несут одну метку
                         -- Дрома («4 поколение», 0297): рестайлинги, которые наш
                         -- справочник держит разными строками, — одно поколение.
                         -- Строка без метки (до 0297, руками) — сомнение, не голос «за».
                         coalesce(count(DISTINCT x.generation_label)
                                      FILTER (WHERE x.model IS NOT NULL
                                                AND (x.generation_id IS NOT NULL
                                                     OR x.year_from IS NOT NULL)) = 1
                                  AND bool_and(x.generation_label IS NOT NULL)
                                      FILTER (WHERE x.model IS NOT NULL
                                                AND (x.generation_id IS NOT NULL
                                                     OR x.year_from IS NOT NULL)), false)
                             AS one_drom,
                         max(x.generation) FILTER (WHERE x.model IS NOT NULL) AS catalog_generation,
                         max(x.generation_label) FILTER (WHERE x.model IS NOT NULL) AS drom_generation,
                         max(x.year_from) FILTER (WHERE x.model IS NOT NULL) AS year_from_max,
                         min(x.year_from) FILTER (WHERE x.model IS NOT NULL) AS year_from_min,
                         max(x.year_to) FILTER (WHERE x.model IS NOT NULL) AS year_to_max,
                         -- «н.в.» у последнего рестайлинга — и у поколения целиком.
                         bool_or(x.up_to_now) FILTER (WHERE x.model IS NOT NULL) AS up_to_now,
                         -- Кузова строк поколения своей машины («GRJ150W, GRJ151W»), 0294.
                         string_agg(x.bodies, ', ')
                             FILTER (WHERE x.model IS NOT NULL
                                       AND (x.generation_id IS NOT NULL
                                            OR x.year_from IS NOT NULL)) AS bodies,
                         -- Те же кузова построчно — сверка 0294 идёт по каждой строке.
                         array_agg(coalesce(x.bodies, ''))
                             FILTER (WHERE x.model IS NOT NULL
                                       AND (x.generation_id IS NOT NULL
                                            OR x.year_from IS NOT NULL)) AS bodies_each,
                         -- Наименование после своей модели — для шага 2.
                         substr(max(x.title), min(x.model_at) FILTER (WHERE x.model IS NOT NULL)
                                              + length(x.model)) AS after_model,
                         bool_or(x.is_verified) AS verified
                    FROM (SELECT a.part_id, ab.name AS brand, op.title,
                                 CASE WHEN strpos(lower(op.title), lower(am.name)) > 0
                                      THEN am.name END AS model,
                                 CASE WHEN starts_with(a.note, 'Кузова: ')
                                      THEN substr(a.note, 9) END AS bodies,
                                 strpos(lower(op.title), lower(ab.name)) AS brand_at,
                                 strpos(lower(op.title), lower(am.name)) AS model_at,
                                 a.generation_id, ag.name AS generation, a.generation_label,
                                 -- Годы клиента «2013–н.в.»: справочник подставил бы свой год конца.
                                 a.year_from IS NOT NULL AND a.year_to IS NULL AS up_to_now,
                                 coalesce(a.year_from, ag.year_from) AS year_from,
                                 coalesce(a.year_to, ag.year_to) AS year_to,
                                 a.is_verified
                            FROM part_applicability a
                            -- Наименование со смешанными словами приведено к латинице (0298):
                            -- «Сruiser» с кириллической «С» — слово Land Cruiser. Считается
                            -- один раз на позицию, а не на каждую строку её применимости
                            -- (четыре с лишним на позицию): OFFSET 0 не даёт планировщику
                            -- подставить выражение в каждое место чтения — замер в записи
                            -- памятки (подстановка удваивала проход по складу).
                            JOIN (SELECT op.id, §t§ AS title
                                    FROM part op
                                   WHERE op.donor_id IS NULL%s
                                  OFFSET 0) op ON op.id = a.part_id
                            JOIN catalog.brand ab ON ab.id = a.brand_id
                            LEFT JOIN catalog.model am ON am.id = a.model_id
                            LEFT JOIN catalog.generation ag ON ag.id = a.generation_id
                           WHERE strpos(lower(op.title), lower(ab.name)) > 0) x
                   GROUP BY x.part_id, x.brand, x.model
                   ORDER BY x.part_id, min(x.brand_at), x.model IS NULL,
                            min(x.model_at) FILTER (WHERE x.model IS NOT NULL),
                            length(x.model) DESC
                          ) o) n) own ON own.part_id = p.id""".replace("§t§", MIXED_TITLE);

    /** Своя машина каждой позиции склада — для отбора, сортировки, счёта и файла. */
    static final String OWN_VEHICLE = OWN_VEHICLE_OF.formatted("", "");

    /**
     * Своя машина одной позиции — та же запись, сужённая до строки: ею
     * украшают пятьдесят позиций страницы после того, как отбор и порядок
     * их выбрали. Проход по всей применимости ради пятидесяти строк стоил
     * бы страницы по умолчанию втрое (замер в PR задачи 0290).
     */
    private static final String OWN_VEHICLE_OF_ROW =
            OWN_VEHICLE_OF.formatted("LATERAL ", " AND op.id = p.id");

    /*
     * Колонки машины: у позиции с донором — его машина, без донора — своя
     * машина из применимости. Одно выражение на колонку, её отбор, сортировку,
     * список значений, скачанный файл и отбор выгрузки — образец
     * {@link #QUALITY_GRADE}: копия разошлась бы на букву, и выбранное
     * из списка перестало бы находить. Подзапрос {@code own} у позиции
     * с донором пуст, поэтому {@code coalesce} и есть «донор побеждает».
     */
    static final String VEHICLE_BRAND = PartVehicle.brand("b.name, own.brand");
    static final String VEHICLE_MODEL = PartVehicle.model("m.name, own.model");
    /*
     * Вписанное у позиции руками (задача 0302) побеждает и донора, и
     * применимость: правка «Марки» в карточке меняет эту позицию, а не машину
     * и не соседние детали с неё. Пусто — как раньше, донор или своя машина.
     */
    static final String VEHICLE_BODY = PartVehicle.body("d.body_code");
    static final String VEHICLE_ENGINE = PartVehicle.engine("d.engine_code");
    static final String VEHICLE_YEAR = PartVehicle.year("d.year");
    static final String DONOR_CODE = PartVehicle.donorCode("d.legacy_code, d.public_code");
    /**
     * Когда поколение своей машины показывается: оно одно (0290) и, если
     * в наименовании стоит номер кузова, кузова поколения его подтверждают
     * (0294, 0299). {@code body_confirmed} пуст, когда номера нет, — тогда
     * правило 0290 как было; {@code false} — номер не подтверждён (кузова
     * другого ряда или их нет вовсе). Одно условие на три колонки — иначе
     * поколение и годы разошлись бы на первой правке.
     */
    private static final String OWN_GENERATION_SHOWN =
            "(own.spans = 1 OR own.one_drom) AND own.body_confirmed IS NOT FALSE";
    /** Поколение одно, но номер кузова в наименовании им не подтверждён — для подсказки. */
    private static final String OWN_GENERATION_UNCONFIRMED =
            "(own.spans = 1 OR own.one_drom) AND NOT own.body_confirmed";
    /**
     * Показано поколение Дрома, собранное из нескольких строк справочника
     * (рестайлингов, 0297), — для подсказки: годы клетки шире любой из строк.
     */
    private static final String OWN_GENERATION_MERGED =
            "own.spans > 1 AND " + OWN_GENERATION_SHOWN;
    // Вписанное руками поколение — текст человека целиком: годы справочника
    // к нему не дописываются (они были бы годами чужого поколения).
    static final String VEHICLE_GENERATION = PartVehicle.generation(
            "g.name, CASE WHEN " + OWN_GENERATION_SHOWN + " THEN own.generation END");
    static final String VEHICLE_YEAR_FROM =
            "CASE WHEN p.manual_generation IS NULL THEN coalesce(g.year_from, CASE WHEN "
            + OWN_GENERATION_SHOWN + " THEN own.year_from END) END";
    static final String VEHICLE_YEAR_TO =
            "CASE WHEN p.manual_generation IS NULL THEN coalesce(g.year_to, CASE WHEN "
            + OWN_GENERATION_SHOWN + " THEN own.year_to END) END";

    /**
     * Марка и модель позиции номерами справочника — для подбора по машине
     * и его списка: своя, вписанная в карточке, иначе донора (задача 0303).
     */
    static final String VEHICLE_BRAND_ID = PartVehicle.brandId("d.brand_id");
    static final String VEHICLE_MODEL_ID = PartVehicle.modelId("d.model_id", "d.brand_id");

    /**
     * Откуда машина в колонках — для подсказки клетки: машинный разбор
     * не должен читаться как подтверждённый факт.
     */
    static final String VEHICLE_SOURCE = "CASE WHEN p.manual_brand IS NOT NULL"
            + " OR p.manual_model IS NOT NULL OR p.manual_generation IS NOT NULL THEN 'MANUAL'"
            + " WHEN p.donor_id IS NOT NULL THEN 'DONOR'"
            + " WHEN own.brand IS NULL THEN NULL"
            + " WHEN own.verified THEN 'VERIFIED' ELSE 'PARSED' END";

    /**
     * Коды кузова, по которым поколение своей машины выведено проходом
     * «Подходит» (задача 0295), — для подсказки клетки: заявление клиента
     * и наш вывод владелец должен различать. Подзапрос на строку страницы,
     * а не шаг своей машины: нужен только пятидесяти показанным позициям.
     * Строка с пометкой ставится только своей машине и только когда другого
     * поколения у неё нет, так что найденная — та самая, что в колонке.
     */
    private static final String GENERATION_BY_BODY =
            "(SELECT substr(a.note, strpos(a.note, '; " + BodyGeneration.MARK + "') + "
            + ("; " + BodyGeneration.MARK).length() + ")"
            + " FROM part_applicability a"
            + " WHERE a.part_id = p.id AND p.donor_id IS NULL AND a.generation_id IS NOT NULL"
            + " AND strpos(a.note, '; " + BodyGeneration.MARK + "') > 0"
            + " ORDER BY a.id LIMIT 1)";

    /**
     * Разрешённые сортировки: имя из запроса → выражение SQL.
     *
     * <p>Белый список, а не экранирование: {@code ORDER BY} не принимает
     * параметр, и любая подстановка пришедшего текста — дыра.
     */
    private static final Map<String, String> SORTS = Map.ofEntries(
            // Порядок по умолчанию, и он же единственный осмысленный при
            // открытии склада: номер позиции растёт вместе с заведением,
            // то есть «что принято раньше, то выше». До задачи 0060 умолчанием
            // был `code` — шесть случайных байт, — и владелец видел случайную
            // полусотню из тридцати пяти тысяч, новую при каждом заходе.
            Map.entry("number", "p.number"),
            Map.entry("code", "p.public_code"),
            Map.entry("title", "p.title"),
            Map.entry("price", "p.price"),
            Map.entry("brand", VEHICLE_BRAND),
            Map.entry("model", VEHICLE_MODEL),
            Map.entry("year", VEHICLE_YEAR),
            Map.entry("qty", "p.qty_on_hand"),
            Map.entry("created", "p.created_at"),
            Map.entry("manufacturer", "p.manufacturer"),
            Map.entry("section", "p.section"));

    /**
     * Порядок строк с вторичным ключом.
     *
     * <p>Без него сортировка по неуникальной колонке — а такие все, кроме
     * номера товара, — не задаёт полного порядка: строки с одинаковой ценой
     * база вправе вернуть в любой последовательности, и между запросами
     * двух соседних страниц эта последовательность меняется. Часть позиций
     * тогда попадает на обе страницы, ровно столько же не попадает никуда.
     *
     * <p>Замерено на живом складе: поиск «фара» отдаёт 744 позиции, и, пройдя
     * все пятнадцать страниц по сортировке «цена», владелец видит 744 строки,
     * из которых уникальных только 721. Двадцать три позиции показаны дважды,
     * двадцать три не показаны вовсе — и узнать об этом по экрану нельзя
     * никак. Выгрузка идёт тем же порядком, значит и в файле их не будет.
     *
     * <p>У вкладки колёс это сделано с самого начала: там {@code ORDER BY}
     * заканчивается на {@code p.id DESC}.
     */
    private static String orderBy(String sort, boolean descending) {
        // Сортировка не названа — умолчание, а не отказ: {@code Map.ofEntries}
        // не принимает null даже на чтение и отвечает на него
        // NullPointerException, то есть пятисоткой на витрине.
        String column = sort == null ? "p.number" : SORTS.getOrDefault(sort, "p.number");
        String direction = descending ? " DESC" : " ASC";
        // Вторичный ключ не нужен только тем колонкам, что и так уникальны:
        // внутреннему номеру и номеру позиции. Остальным он обязателен.
        return column.equals("p.id") || column.equals("p.number")
                ? column + direction
                : column + direction + ", p.id" + direction;
    }

    private static final Map<String, String> SIDE_FR =
            Map.of("FRONT", "Перед.", "REAR", "Задн.", "BOTH", "Перед., задн.");

    private static final Map<String, String> SIDE_LR =
            Map.of("LEFT", "Лев.", "RIGHT", "Прав.", "BOTH", "Лев., прав.");

    private final JdbcTemplate jdbc;

    private final PartChangeLog partChanges;

    private final VehicleWords vehicleWords;

    /**
     * Применимость из карточки правится прямым SQL; добавление и снятие
     * машины пишутся в журнал здесь (задача 0325).
     */
    private final ru.partsflow.platform.audit.AuditJournal journal;

    public CatalogService(JdbcTemplate jdbc, PartChangeLog partChanges,
                          VehicleWords vehicleWords, ru.partsflow.platform.audit.AuditJournal journal) {
        this.journal = journal;
        this.partChanges = partChanges;
        this.jdbc = jdbc;
        this.vehicleWords = vehicleWords;
    }

    /**
     * Страница витрины.
     *
     * @param query       поиск по номеру товара, наименованию или номеру детали
     * @param withReserved показывать ли отложенное; выключено — только свободное
     * @param withMissing  показывать ли то, чего нет на складе. Проданное
     *                     не исчезает из системы, и владелец иногда смотрит
     *                     именно его — но по умолчанию склад это то, что лежит
     * @param warehouseIds пусто — все склады
     */
    @Transactional(readOnly = true)
    public Page list(String query, boolean withReserved, boolean withMissing,
                     List<Long> warehouseIds, Vehicle vehicle,
                     Map<String, String> columns, Map<String, String> words,
                     String sort, boolean descending, int page, int size) {
        return list(query, withReserved, withMissing, warehouseIds, vehicle,
                columns, words, sort, descending, page, size, null);
    }

    /**
     * @param after код товара последней строки предыдущей страницы: тогда
     *              следующая берётся не отступом, а от него
     */
    // Транзакция обязательна и здесь, а не только у соседней перегрузки:
    // search_path выставляет провайдер соединений Hibernate внутри неё,
    // и JdbcTemplate, вызванный снаружи, уходит в public — «relation part
    // does not exist» на витрине при полностью целых данных. Поймано
    // репетицией восстановления ячейки: там витрина отвечала пятисоткой,
    // а поиск продавца, который идёт через репозиторий, работал.
    @Transactional(readOnly = true)
    public Page list(String query, boolean withReserved, boolean withMissing,
                     List<Long> warehouseIds, Vehicle vehicle,
                     Map<String, String> columns, Map<String, String> words,
                     String sort, boolean descending, int page, int size, String after) {

        Filter filter = filterOf(query, withReserved, withMissing, warehouseIds, vehicle,
                columns, words);
        StringBuilder where = filter.where();
        List<Object> args = filter.args();

        return finish(where, args, sort, descending, page, size, after);
    }

    /**
     * Условие отбора — одно на страницу и на выгрузку.
     *
     * <p>Разное условие означало бы, что скачанный файл не совпадает с тем,
     * что владелец видел на экране, — а он именно это и проверяет, скачивая.
     */
    private Filter filterOf(String query, boolean withReserved, boolean withMissing,
                            List<Long> warehouseIds) {
        return filterOf(query, withReserved, withMissing, warehouseIds, null, null, null);
    }

    /**
     * Отбор по машине: «покажи, что подходит к этой».
     *
     * <p><b>Ищется по двум признакам сразу.</b> Первый — машина, с которой
     * деталь снята: у переехавшего клиента это единственное, что есть, —
     * заявленной применимости у него ноль строк на двадцать шесть тысяч
     * деталей с донором. Второй — сама применимость, если её заполнили
     * в приёмке. Искать только по второму значило бы показать переехавшему
     * пустоту, только по первому — потерять деталь, которая подходит
     * не только к своей машине.
     *
     * <p>Кузов и двигатель сравниваются по вхождению: у клиента в этих полях
     * лежит «ACA33», а у другой машины той же модели — «ACA38», и точное
     * равенство отсекло бы половину подходящего.
     */
    private Filter filterOf(String query, boolean withReserved, boolean withMissing,
                            List<Long> warehouseIds, Vehicle vehicle,
                            Map<String, String> columns, Map<String, String> words) {
        StringBuilder where = new StringBuilder(" WHERE p.product_line = 'PART'");
        List<Object> args = new ArrayList<>();

        if (query != null && !query.isBlank()) {
            // «камри» приводится к «Camry» до всего остального: в заголовке
            // латиница, а владелец ищет то же слово, что и продавец.
            query = vehicleWords.translate(query);
            // Номер товара, наименование и номер детали — три способа, которыми
            // владелец ищет одно и то же. Спрашивать, что именно он ввёл,
            // значит заставить его выбирать вкладку перед каждым поиском.
            // Четыре способа спросить одно и то же: номер товара, наименование,
            // слово в любом падеже и номер производителя. Морфология тут
            // обязательна — подстрокой «фара» не находит «фары», а спрашивают
            // именно так; пока условие было только подстрочным, владелец видел
            // 521 позицию там, где продавец по тому же слову находил 739.
            //
            // UNION, а не OR, и это не вкусовщина. С OR планировщик берёт
            // индекс, только если проиндексированы все ветки, и всё равно
            // мажет с кардинальностью '%фара%' — на живом складе он уходил
            // в полный перебор 35 841 строки: 744 мс на запрос и до 4,3 секунды
            // под нагрузкой. С UNION каждая ветка идёт своим индексом.
            // Замерено: 744 мс → 38 мс.
            //
            // Пятая ветка — номер позиции, и она точная (задача 0064). Номер
            // завели затем, чтобы его называли вслух, а найти по нему было
            // нельзя: «посмотри позицию 347» собеседник мог только долистать.
            // Ветка появляется, только когда весь запрос — число: «фара 347»
            // это по-прежнему текст. Подстрочного сравнения тут быть не может,
            // иначе «347» притащило бы 1347, 3470 и 2347 — шум того же рода,
            // от которого номер и спасает.
            Long number = PartNumberQuery.parse(query);
            // Номер производителя в любом написании (задача 0306): запрос
            // приводится тем же OemNumbers.normalize, что и запись, и ищется
            // в part_oem.normalized подстрокой. Не похож на номер — ветки нет.
            String oemKey = ru.partsflow.catalog.OemNumbers.searchKey(query);
            // Публичный код при запросе-номере сравнивается точно (задача 0263):
            // двенадцать случайных знаков содержат «347» у каждой четырёхсотой
            // записи, и подстрока приносила бы чужие позиции рядом с названной.
            where.append("\n\n                     AND p.id IN (\n"
                    + "                         SELECT id FROM part WHERE "
                    + PartNumberQuery.publicCodeMatch("public_code", number != null));
            where.append("""

                          UNION SELECT id FROM part WHERE title ILIKE ?
                          UNION SELECT id FROM part
                                 WHERE to_tsvector('russian', coalesce(title, '') || ' '
                                           || coalesce(description, '') || ' '
                                           || coalesce(marking, ''))
                                       @@ plainto_tsquery('russian', ?)
                          UNION SELECT part_id FROM part_oem WHERE raw_number ILIKE ?"""
                    + (oemKey == null ? "" : OEM_NORMALIZED_BRANCH)
                    + (number == null ? ""
                            : PartNumberQuery.UNION_BRANCH + PartNumberQuery.DROM_AD_BRANCH)
                    + ")");
            String like = "%" + query.strip() + "%";
            String codeArg = PartNumberQuery.publicCodeArg(query, number != null);
            args.add(codeArg);
            args.add(like);
            args.add(query.strip());
            args.add(like);
            if (oemKey != null) {
                args.add(oemNormalizedArg(oemKey));
            }
            if (number != null) {
                args.add(number);
                // Номер объявления Дрома (задача 0274): те же цифры, но текстом.
                args.add(codeArg);
            }
        }
        if (!withMissing) {
            where.append(" AND p.qty_on_hand > 0");
        }
        if (!withReserved) {
            // «Не показывать отложенное» прячет то, что лежит, но обещано
            // другому, — и только это. Позиции без остатка вовсе им управлять
            // не должно: за них отвечает «отображать отсутствующие», иначе
            // два независимых флажка молча складываются в один.
            where.append("""

                     AND (p.qty_on_hand = 0
                          OR EXISTS (SELECT 1 FROM part_stock s
                                      WHERE s.part_id = p.id AND s.qty - s.qty_reserved > 0))""");
        }
        if (warehouseIds != null && !warehouseIds.isEmpty()) {
            where.append("""

                     AND EXISTS (SELECT 1 FROM part_stock s
                                  WHERE s.part_id = p.id AND s.qty > 0
                                    AND s.warehouse_id = ANY (?))""");
            args.add(warehouseIds.toArray(Long[]::new));
        }

        if (vehicle != null && vehicle.brandId() != null) {
            // Машина позиции — своя, вписанная в карточке, иначе донора (задача
            // 0303, PartVehicle): исправленная на Lexus деталь с Camry находится
            // подбором «Lexus», а не «Toyota». Применимость — «что подходит» —
            // остаётся второй половиной, как была.
            StringBuilder donor = new StringBuilder(VEHICLE_BRAND_ID + " = ?");
            List<Object> donorArgs = new ArrayList<>();
            donorArgs.add(vehicle.brandId());
            if (vehicle.modelId() != null) {
                donor.append(" AND " + VEHICLE_MODEL_ID + " = ?");
                donorArgs.add(vehicle.modelId());
            }
            if (vehicle.body() != null && !vehicle.body().isBlank()) {
                donor.append(" AND " + VEHICLE_BODY + " ILIKE ?");
                donorArgs.add("%" + vehicle.body().strip() + "%");
            }
            if (vehicle.engine() != null && !vehicle.engine().isBlank()) {
                donor.append(" AND " + VEHICLE_ENGINE + " ILIKE ?");
                donorArgs.add("%" + vehicle.engine().strip() + "%");
            }

            StringBuilder declared = new StringBuilder("a.brand_id = ?");
            List<Object> declaredArgs = new ArrayList<>();
            declaredArgs.add(vehicle.brandId());
            if (vehicle.modelId() != null) {
                declared.append(" AND a.model_id = ?");
                declaredArgs.add(vehicle.modelId());
            }

            where.append("\n AND ((").append(donor)
                    .append(") OR EXISTS (SELECT 1 FROM part_applicability a")
                    .append(" WHERE a.part_id = p.id AND ").append(declared).append("))");
            args.addAll(donorArgs);
            args.addAll(declaredArgs);
        }
        // Отбор колонками: выбранное из списка ищется точно, вбитое руками —
        // вхождением. Разные вопросы, и разводить их надо здесь, а не гадать
        // по виду значения.
        if (columns != null) {
            for (var entry : columns.entrySet()) {
                String expression = columnExpression(entry.getKey());
                String value = entry.getValue();
                // Пустое значение — «условие не задано», а не «равно пустоте»:
                // отбор по нему не находит ничего, и у выгрузки это пустой
                // прайс, который площадка примет молча вместе с потерей
                // объявлений. «Не заполнено» спрашивают отдельным пунктом
                // («—пусто—»), и он выше по коду.
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (EMPTY.equals(value)) {
                    where.append(" AND coalesce(").append(expression).append(", '') = ''");
                } else if (PRESENT.equals(value)) {
                    where.append(" AND coalesce(").append(expression).append(", '') <> ''");
                } else {
                    where.append(" AND ").append(expression).append(" = ?");
                    args.add(value);
                }
            }
        }
        if (words != null) {
            for (var entry : words.entrySet()) {
                // Пустое «содержит» — не «подходит любое»: `ILIKE '%%'`
                // отбрасывает незаполненные, то есть тихо сужает выдачу там,
                // где владелец ничего не спрашивал.
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                where.append(" AND ").append(columnExpression(entry.getKey())).append(" ILIKE ?");
                args.add("%" + entry.getValue().strip() + "%");
            }
        }

        return new Filter(where, args);
    }

    /**
     * Отбор колонками как подзапрос — для тех, кто отбирает не витрину.
     *
     * <p><b>Зачем наружу.</b> Прайс площадки отбирался шестью условиями,
     * зашитыми колонками таблицы, и каждое седьмое означало миграцию, правку
     * генератора, счётчика и экрана — то есть релиз вместо действия
     * владельца. Витрина к этому времени умеет отбирать по двадцати девяти
     * колонкам, и заводить рядом второй список выражений значило бы получить
     * две правды об одном и том же: они разошлись бы на первой же правке,
     * и разошлись бы молча.
     *
     * <p>Поэтому выгрузка спрашивает условие здесь и подставляет его
     * подзапросом по номерам позиций. Выражения, белый список колонок
     * и разбор «пусто / не пусто» остаются в одном месте, а генератору
     * не нужно знать ни про соединения витрины, ни про её алиасы.
     *
     * @return пусто, если условий нет — тогда прайс отдаёт всё, что прошло
     *         остальные его отборы
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ColumnFilter> columnFilter(Map<String, String> columns,
                                                         Map<String, String> words) {
        boolean empty = (columns == null || columns.isEmpty())
                && (words == null || words.isEmpty());
        if (empty) {
            return java.util.Optional.empty();
        }
        Filter filter = filterOf(null, true, true, List.of(), null, columns, words);
        return java.util.Optional.of(new ColumnFilter(
                "p.id IN (SELECT p.id" + joins(filter.where()) + filter.where() + ")",
                filter.args()));
    }

    /**
     * @param sql  готовое условие вида {@code p.id IN (…)}
     * @param args его параметры по порядку
     */
    public record ColumnFilter(String sql, List<Object> args) {
    }

    /**
     * Оценка состояния словами — одно выражение на всех, кто её показывает
     * и по ней отбирает.
     *
     * <p>Читателей теперь двое: колонка «Оценка состояния» витрины и отбор
     * поиска продавца (задача 0010). Копия здесь означала бы две правды
     * об одном и том же: разойдись они на букву — выбранное из списка
     * значение перестало бы находить что-либо, а причина была бы видна
     * только в SQL. Ровно так уже расходились словари состояний сделки
     * и белые списки свойств колеса.
     *
     * <p>Позиция названа {@code p} — выражение годится любому запросу,
     * где алиас таблицы {@code part} тот же.
     *
     * <p><b>Слова берутся у {@link QualityGrade}, а не пишутся здесь.</b>
     * Пока они были написаны здесь, выражение переводило
     * {@code EXCELLENT/GOOD/FAIR/POOR} — четыре значения, которых
     * {@code part_quality_grade_ck} не допускает, — и {@code CASE} всегда
     * уходил в запасную ветку по {@code p.condition}: в колонке «Оценка
     * состояния» стояло «б/у», и его же предлагал список отбора. Запасной
     * ветки больше нет: пустая оценка — это «не оценена» (задача 0033).
     */
    public static final String QUALITY_GRADE = QualityGrade.sqlLabel("p");

    /**
     * Состояние словами — то же выражение и там, где по нему отбирают,
     * и там, где его показывают.
     *
     * <p>Отдельно от оценки состояния выше: «б/у» и «отличное» — разные
     * вопросы. Читателей двое: отбор витрины и отчёт по проданным позициям
     * (задача 0054), которому слово нужно и на экране, и в скачанном файле —
     * код {@code USED} там означал бы утечку внутреннего представления
     * в таблицу, которую открывают в Excel и читают глазами.
     *
     * <p>Позиция названа {@code p}, как и у {@link #QUALITY_GRADE}.
     *
     * <p><b>Слова берутся у {@link PartCondition}, а не пишутся здесь</b>
     * (задача 0039). Пока они стояли здесь, тот же {@code CASE} был написан
     * ещё и у вкладки колёс, а словарей на фронтенде было два: четвёртое
     * значение пришлось бы дописать в пять мест, и забытое пятое молча
     * показало бы пустую клетку в колонке «Состояние».
     */
    public static final String CONDITION = PartCondition.sqlLabel("p");

    /** Незаполненное поле и «заполнено хоть чем-то» — тоже ответы на вопрос. */
    public static final String EMPTY = "\u2014пусто\u2014";
    public static final String PRESENT = "\u2014не пусто\u2014";

    /**
     * По каким колонкам отбор вообще делается.
     *
     * <p><b>Зачем наружу.</b> Экран открывал меню отбора у любой колонки —
     * в том числе у превью, состояния, комплектации и себестоимости, которых
     * в этом списке нет. Список значений приезжал отказом, отбор не применялся,
     * и оба отказа проглатывались молча: владелец нажимал «Состояние → новое»,
     * видел те же тридцать пять тысяч строк и делал единственный возможный
     * вывод — что весь склад новый либо что отбор сломан.
     *
     * <p>Отдаём сам список, а не повторяем его на клиенте: два списка
     * разошлись бы на первой же новой колонке, и разошлись бы молча.
     */
    public static Set<String> filterableColumns() {
        return FILTERS.keySet();
    }

    /**
     * Неназванная колонка — это «отбор по ней не делается», а не пятисотка.
     *
     * <p>{@code Map.ofEntries} неизменяема и на чтении по {@code null}-ключу
     * бросает {@code NullPointerException}, а не отвечает «нет такого», —
     * то есть проверка строкой ниже до этого не доживает, и вместо своего
     * объяснения наружу уехала бы «Внутренняя ошибка». Отказ обязан
     * объяснять: офлайн-очередь читает 5xx как повод повторять, а человек
     * идёт искать поломку сервера.
     *
     * <p>Видимость пакетная, а не приватная, ради теста: снаружи {@code null}
     * сюда не приходит — параметр запроса обязателен, — и позвать метод так
     * может только он.
     */
    static String columnExpression(String column) {
        String expression = column == null ? null : FILTERS.get(column);
        if (expression == null) {
            throw new IllegalArgumentException("По этой колонке отбор не делается: " + column);
        }
        return expression;
    }

    /**
     * Различные значения колонки — то, из чего владелец выбирает отбор.
     *
     * <p>Считаются по всему складу, а не по текущей выдаче: список, который
     * сужается от уже поставленных фильтров, не даёт снять один и поставить
     * другой — выбранного значения в нём уже нет.
     *
     * <p>Ограничение в тысячу значений — не косметика: по «Наименованию»
     * у переехавшего клиента их больше тысячи, и список, в котором надо
     * прокрутить тысячу строк, бесполезен ровно так же, как пустой. Для таких
     * колонок есть ввод своего слова.
     */
    @Transactional(readOnly = true)
    public List<String> values(String column) {
        String expression = columnExpression(column);
        // Соединения те же, что у страницы (`JOINS`), а не своя копия: у копии
        // не было своей машины из применимости, и список марок молчал бы
        // о марках, которые колонка уже показывает (задача 0290).
        return jdbc.queryForList("SELECT DISTINCT " + expression + " AS value"
                        + joins(expression)
                        + " WHERE p.product_line = 'PART' AND " + expression + " IS NOT NULL"
                        + " AND " + expression + " <> ''"
                        + " ORDER BY value LIMIT 1000",
                String.class);
    }

    /**
     * Колонка → выражение, дающее ровно то, что видно на экране.
     *
     * <p>Белый список, а не подстановка пришедшего имени: это и защита
     * от внедрения SQL, и единственный способ не разойтись со списком
     * значений — разойдись они, выбранное из списка не находило бы ничего.
     */
    private static final Map<String, String> FILTERS = Map.ofEntries(
            Map.entry("code", "p.public_code"),
            Map.entry("title", "p.title"),
            Map.entry("partName", "pn.name"),
            Map.entry("quality", QUALITY_GRADE),
            // Состояние отбирается отдельно от оценки: «б/у» и «отличное» —
            // разные вопросы, и на вкладке колёс отбор по состоянию есть
            // с самого начала, как и в отборе выгрузок. На витрине его
            // не было вовсе — то есть «покажи всё новое» владелец задать
            // не мог, хотя колонка перед глазами.
            Map.entry("condition", CONDITION),
            // Машина позиции: донора, а без него — своя из применимости
            // (задача 0290). Те же выражения, что у колонки и сортировки.
            Map.entry("brand", VEHICLE_BRAND),
            Map.entry("model", VEHICLE_MODEL),
            Map.entry("generation", VEHICLE_GENERATION),
            Map.entry("body", VEHICLE_BODY),
            Map.entry("engine", VEHICLE_ENGINE),
            Map.entry("year", VEHICLE_YEAR + "::text"),
            Map.entry("sideFr", "CASE p.side_fr WHEN 'FRONT' THEN 'Перед.'"
                    + " WHEN 'REAR' THEN 'Задн.' WHEN 'BOTH' THEN 'Перед., задн.' END"),
            Map.entry("sideLr", "CASE p.side_lr WHEN 'LEFT' THEN 'Лев.'"
                    + " WHEN 'RIGHT' THEN 'Прав.' WHEN 'BOTH' THEN 'Лев., прав.' END"),
            Map.entry("donor", DONOR_CODE),
            Map.entry("price", "trim_scale(p.price)::text"),
            Map.entry("installation", "trim_scale(p.installation_price)::text"),
            Map.entry("color", "p.color"),
            Map.entry("description", "p.description"),
            Map.entry("manufacturer", "p.manufacturer"),
            Map.entry("marking", "p.marking"),
            Map.entry("note", "p.note"),
            Map.entry("section", "p.section"),
            // Тем же выражением, каким колонка выводится: разойдись они —
            // выбранное из списка значение не находило бы ничего.
            Map.entry("supply", SupplyKinds.sqlLabelWithArrival("s")),
            Map.entry("published", "CASE WHEN p.is_published THEN 'Везде' ELSE 'Нет' END"),
            Map.entry("barcode", "p.barcode"),
            Map.entry("legacy", "p.legacy_code"),
            Map.entry("weight", "trim_scale(p.weight_kg)::text"),
            Map.entry("updatedBy", "(SELECT tm.display_name FROM tenant_member tm"
                    + " WHERE tm.id = p.updated_by)"),
            Map.entry("priceChangedBy", "(SELECT tm.display_name FROM tenant_member tm"
                    + " WHERE tm.id = p.price_changed_by)"));

    private record Filter(StringBuilder where, List<Object> args) {
    }

    /**
     * Машины, к которым на складе что-то есть, — с числом деталей.
     *
     * <p><b>Список свой, а не весь справочник.</b> В каталоге четыре с половиной
     * тысячи моделей, из них на складе лежат детали от полутора сотен: подбор
     * по общему справочнику — это выбрать модель и получить пустую таблицу,
     * ничего не сказав о том, почему пусто. Число рядом отвечает сразу.
     *
     * <p>Строк тут по числу разобранных машин, то есть сотни, — уровни кузова
     * и двигателя экран складывает сам, вторым запросом их брать незачем.
     *
     * <p><b>Машины донора и машины применимости — вместе (задача 0290).</b>
     * Отбор подбора с самого начала объединяющий (донор <i>или</i> строка
     * {@code part_applicability}), а список, из которого его выбирают, шёл
     * только от донора: у клиента, перенесённого с Дрома, где донора нет
     * ни у одной позиции, он был пуст целиком — на ПРОМ {@code []}.
     *
     * <p><b>Число рядом с моделью обязано совпасть с тем, что найдёт отбор.</b>
     * Поэтому позиция считается в модели один раз: если её донор — эта же
     * марка и модель, строка применимости на ту же машину не добавляет её
     * второй раз (она уже в строке донора с его кузовом и двигателем), а
     * несколько поколений одной модели в применимости — одна позиция,
     * а не три. Кузова и двигателя у применимости нет, её строка — без них.
     *
     * <p><b>Число рядом с маркой экран сложением не получит:</b> деталь,
     * подходящая к Prado, 4Runner и Hilux Surf, — одна позиция Toyota, а не
     * три. Его считает сервер — {@code brandParts}, столько, сколько найдёт
     * отбор по одной марке.
     */
    @Transactional(readOnly = true)
    public List<VehicleOption> vehicles() {
        return jdbc.query("""
                WITH fits AS (
                    SELECT p.id AS part_id,
                           """ + VEHICLE_BRAND_ID + " AS brand_id, "
                           + VEHICLE_MODEL_ID + " AS model_id, "
                           + VEHICLE_BODY + " AS body_code, "
                           + VEHICLE_ENGINE + " AS engine_code" + """

                      FROM part p
                      LEFT JOIN donor d ON d.id = p.donor_id
                     WHERE p.product_line = 'PART'
                       -- Машина позиции: донора или вписанная в карточке (0303).
                       AND (p.donor_id IS NOT NULL OR p.manual_brand IS NOT NULL)
                    UNION
                    SELECT a.part_id, a.brand_id, a.model_id, NULL, NULL
                      FROM part_applicability a
                      JOIN part p ON p.id = a.part_id
                      LEFT JOIN donor d ON d.id = p.donor_id
                     WHERE p.product_line = 'PART'
                       AND NOT coalesce(""" + VEHICLE_BRAND_ID + " = a.brand_id AND "
                           + VEHICLE_MODEL_ID + " IS NOT DISTINCT FROM a.model_id, false)" + """

                ), brands AS (
                    SELECT brand_id, count(DISTINCT part_id) AS parts FROM fits GROUP BY brand_id
                )
                SELECT b.id AS brand_id, b.name AS brand, m.id AS model_id, m.name AS model,
                       f.body_code, f.engine_code, count(*) AS parts, br.parts AS brand_parts
                  FROM fits f
                  JOIN brands br ON br.brand_id = f.brand_id
                  JOIN catalog.brand b ON b.id = f.brand_id
                  LEFT JOIN catalog.model m ON m.id = f.model_id
                 GROUP BY b.id, b.name, m.id, m.name, f.body_code, f.engine_code, br.parts
                 ORDER BY b.name, m.name""",
                (rs, i) -> new VehicleOption(rs.getLong("brand_id"), rs.getString("brand"),
                        (Long) rs.getObject("model_id"), rs.getString("model"),
                        rs.getString("body_code"), rs.getString("engine_code"),
                        rs.getLong("parts"), rs.getLong("brand_parts")));
    }

    /**
     * Марка, модель, кузов и двигатель одной разобранной машины.
     *
     * @param parts      позиций в этой строке; по модели строки складываются
     *                   без повтора (позиция стоит в одной строке модели)
     * @param brandParts позиций у марки целиком — не сумма строк: деталь,
     *                   подходящая к трём моделям марки, в ней одна
     */
    public record VehicleOption(Long brandId, String brand, Long modelId, String model,
                                String body, String engine, long parts, long brandParts) {
    }

    /**
     * Проставляет применимость по машинам, названным в заголовках.
     *
     * <p>У переехавшего клиента четверть склада без донора: это детали,
     * подходящие к нескольким машинам, и машины перечислены прямо
     * в наименовании — записать их было больше некуда. Подбор по машине
     * их не находит вовсе.
     *
     * <p><b>Отметка `is_verified` остаётся снятой.</b> Это разобранное
     * машиной, а не подтверждённое человеком, и различать их надо: правка
     * в карточке ставит отметку, а повторный разбор чужого не трогает.
     *
     * @return сколько позиций получили применимость и сколько строк добавлено
     */
    @Transactional
    public Parsed applyFromTitles() {
        TitleApplicability.Dictionary dictionary = new TitleApplicability.Dictionary(
                jdbc.query("SELECT id, name FROM catalog.brand",
                        (rs, i) -> new TitleApplicability.Brand(rs.getLong(1), rs.getString(2))),
                jdbc.query("SELECT id, brand_id, name FROM catalog.model",
                        (rs, i) -> new TitleApplicability.Model(
                                rs.getLong(1), rs.getLong(2), rs.getString(3))));

        List<long[]> pairs = new ArrayList<>();
        int[] parts = {0};
        jdbc.query("SELECT id, title FROM part WHERE title IS NOT NULL", rs -> {
            long partId = rs.getLong(1);
            List<TitleApplicability.Vehicle> found =
                    TitleApplicability.parse(rs.getString(2), dictionary);
            if (!found.isEmpty()) {
                parts[0]++;
                for (TitleApplicability.Vehicle vehicle : found) {
                    pairs.add(new long[]{partId, vehicle.brandId(), vehicle.modelId()});
                }
            }
        });

        int added = 0;
        for (long[] pair : pairs) {
            added += jdbc.update("""
                    INSERT INTO part_applicability (part_id, brand_id, model_id, is_verified)
                    VALUES (?, ?, ?, false)
                    ON CONFLICT DO NOTHING""", pair[0], pair[1], pair[2]);
        }
        log.info("Применимость из заголовков: позиций {}, строк добавлено {}", parts[0], added);
        return new Parsed(parts[0], added);
    }

    /**
     * @param parts сколько позиций назвали машину в заголовке
     * @param added сколько строк применимости добавлено — повтор не дублирует
     */
    public record Parsed(int parts, int added) {
    }

    /**
     * Добавляет применимость руками — из карточки. Отметка подтверждения стоит.
     *
     * @param setBy кто добавил (сотрудник вошедшего запроса); пусто — неизвестно
     */
    @Transactional
    public boolean addApplicability(long partId, long brandId, Long modelId, Long setBy) {
        return insertApplicability(partId, brandId, modelId, null, null, null, true, setBy) != null;
    }

    /**
     * Применимость позиции, заведённой из донора (задача 0363, «как у Bazon»): марка,
     * модель и <b>поколение донора</b>, годы поколения. Звать из приёмки и заведения
     * ожидаемого — одним путём с карточкой, второго пути записи применимости нет.
     *
     * <p>Поколения у донора нет — ничего не пишется и об этом говорится словами
     * ({@link #applicabilityState}): выбирать поколение по марке и модели значило бы
     * угадывать, а угаданное в «Поиске по применимости» читается как факт.
     *
     * <p>Строка разобранная, не подтверждённая человеком ({@code is_verified = false}):
     * её поставила машина, и повторный разбор наименований или блока «Подходит»
     * различает такие строки с подтверждёнными. Кузова поколения не пишутся: в нашем
     * справочнике у поколения один код, а список кузовов — справочник Bazon (0343).
     *
     * @return {@code true} — строка заведена; {@code false} — у донора нет поколения
     *         либо такая строка у позиции уже есть
     */
    @Transactional
    public boolean applyFromDonor(long partId, long donorId, Long setBy) {
        List<Map<String, Object>> donor = jdbc.queryForList("""
                SELECT d.brand_id, d.model_id, d.generation_id, g.year_from, g.year_to
                  FROM donor d
                  LEFT JOIN catalog.generation g ON g.id = d.generation_id
                 WHERE d.id = ?""", donorId);
        if (donor.isEmpty() || donor.get(0).get("generation_id") == null) {
            return false;
        }
        Map<String, Object> d = donor.get(0);
        return insertApplicability(partId, ((Number) d.get("brand_id")).longValue(),
                d.get("model_id") == null ? null : ((Number) d.get("model_id")).longValue(),
                ((Number) d.get("generation_id")).longValue(),
                (Number) d.get("year_from"), (Number) d.get("year_to"), false, setBy) != null;
    }

    /**
     * Единственное место, где в {@code part_applicability} пишут из карточки и из приёмки.
     * Без собственной аннотации намеренно: журнал требует транзакцию вызывающего.
     *
     * @return номер заведённой строки; {@code null} — строка уже была (подтверждённая
     *         повторно, если {@code verified}) и новой не появилось
     */
    private Long insertApplicability(long partId, long brandId, Long modelId, Long generationId,
                                     Number yearFrom, Number yearTo, boolean verified, Long setBy) {
        // xmax = 0 у вставленной строки и не 0 у обновлённой: подтверждение
        // уже заведённой машины — не заведение, и журнал его так не называет.
        // Автор и момент пишутся только вставкой: подтверждая готовую строку,
        // человек не становится тем, кто её «установил».
        String conflict = verified
                ? "ON CONFLICT (part_id, brand_id, model_id, generation_id, modification_id)"
                  + " DO UPDATE SET is_verified = true"
                : "ON CONFLICT DO NOTHING";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id,"
                        + " year_from, year_to, is_verified, set_by, set_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, now()) " + conflict
                        + " RETURNING id, (xmax = 0) AS inserted",
                partId, brandId, modelId, generationId, yearFrom, yearTo, verified, setBy);
        boolean inserted = !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("inserted"));
        Long id = rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
        if (inserted) {
            journal.created("part_applicability", id, applicabilitySnapshot(id));
        }
        // Марка и модель уезжают в прайс отдельными тегами: у контрактной
        // детали они берутся именно отсюда. Прежний триггер этого не ловил
        // вовсе — на part_applicability его не было. Отметка нужна и когда строку
        // только подтвердили; у новой позиции из приёмки она есть и без нас.
        if (verified) {
            partChanges.changed(partId);
        }
        return rows.isEmpty() ? null : id;
    }

    @Transactional
    public void removeApplicability(long partId, long applicabilityId) {
        // Снимок до удаления: после него машину назвать будет не по чему.
        List<Map<String, Object>> was = jdbc.queryForList("""
                SELECT 1 FROM part_applicability WHERE id = ? AND part_id = ? FOR UPDATE""",
                applicabilityId, partId);
        if (!was.isEmpty()) {
            Map<String, Object> snapshot = applicabilitySnapshot(applicabilityId);
            jdbc.update("DELETE FROM part_applicability WHERE id = ? AND part_id = ?",
                    applicabilityId, partId);
            journal.deleted("part_applicability", applicabilityId, snapshot);
        }
        partChanges.changed(partId);
    }

    /**
     * Строка применимости для журнала: позиция и машина словами — снятую
     * машину иначе назвать будет не по чему.
     */
    private Map<String, Object> applicabilitySnapshot(long applicabilityId) {
        return jdbc.queryForMap("""
                SELECT a.part_id,
                       concat_ws(' ', b.name, m.name, g.name) AS vehicle
                  FROM part_applicability a
                  JOIN catalog.brand b ON b.id = a.brand_id
                  LEFT JOIN catalog.model m ON m.id = a.model_id
                  LEFT JOIN catalog.generation g ON g.id = a.generation_id
                 WHERE a.id = ?""", applicabilityId);
    }

    /**
     * Заявленная применимость позиции — для карточки.
     *
     * <p>У переехавшего клиента она пуста: на прогонном арендаторе ноль строк
     * при тридцати пяти тысячах позиций. Карточка это и показывает — «не
     * задана», а не пустой список без объяснения: подбор по машине у такой
     * позиции работает только от донора, и если донора тоже нет, найти её
     * по машине нельзя вовсе.
     */
    @Transactional(readOnly = true)
    public List<Applicability> applicabilityOf(long partId) {
        return jdbc.query("""
                SELECT a.id, a.is_verified, b.name AS brand, m.name AS model,
                       g.name AS generation, a.year_from, a.year_to,
                       tm.display_name AS set_by, a.set_at
                  FROM part_applicability a
                  LEFT JOIN tenant_member tm ON tm.id = a.set_by
                  JOIN catalog.brand b ON b.id = a.brand_id
                  LEFT JOIN catalog.model m ON m.id = a.model_id
                  LEFT JOIN catalog.generation g ON g.id = a.generation_id
                 WHERE a.part_id = ?
                 ORDER BY b.name, m.name""",
                (rs, i) -> new Applicability(rs.getLong("id"), rs.getBoolean("is_verified"),
                        rs.getString("brand"), rs.getString("model"), rs.getString("generation"),
                        (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to"),
                        rs.getString("set_by"),
                        // Момент из timestamptz читается через getTimestamp (getObject
                        // в Instant драйвер не умеет). NULL — установлена до задачи 0363.
                        rs.getTimestamp("set_at") == null ? null
                                : rs.getTimestamp("set_at").toInstant()),
                partId);
    }

    /** Строка применимости: к какой машине заявлена деталь. */
    public record Applicability(long id, boolean verified, String brand, String model,
                                String generation, Integer yearFrom, Integer yearTo,
                                String setBy, Instant setAt) {
    }

    /** Машина, к которой подбирают деталь. Пустая марка — отбора нет. */
    public record Vehicle(Long brandId, Long modelId, String body, String engine) {
    }

    /**
     * Соединения отбора — те же для страницы и для правки по отбору.
     *
     * <p>Условие ссылается на марку, модель и написание вида детали, то есть
     * на соседние таблицы: собранное без них, оно не выполнится вовсе.
     * Константа, а не копия в каждом запросе, по той же причине, по которой
     * отбор один: разойдясь, они дали бы правку не того, что владелец видел.
     */
    private static final String JOINS = """

              FROM part p
              LEFT JOIN donor d ON d.id = p.donor_id
              LEFT JOIN catalog.brand b ON b.id = d.brand_id
              LEFT JOIN catalog.model m ON m.id = d.model_id
              LEFT JOIN catalog.generation g ON g.id = d.generation_id
              LEFT JOIN catalog.modification mo ON mo.id = d.modification_id
              LEFT JOIN supply s ON s.id = d.supply_id
              LEFT JOIN part_name pn ON pn.id = p.part_name_id""";

    /**
     * Соединения под запрос: своя машина позиции из применимости — только
     * тогда, когда запрос её читает.
     *
     * <p>Подзапрос {@link #OWN_VEHICLE} проходит всю применимость склада
     * (у переехавшего клиента — сорок тысяч строк), и счёт склада без отбора
     * по машине платил бы за него ни за что. Признак — алиас {@code own.}
     * в тексте запроса: выражения колонок машины ({@link #VEHICLE_BRAND}
     * и соседи) его содержат, и забыть соединение для них нельзя.
     */
    private static String joins(CharSequence... uses) {
        for (CharSequence use : uses) {
            if (use != null && use.toString().contains("own.")) {
                return JOINS + OWN_VEHICLE;
            }
        }
        return JOINS;
    }

    /**
     * Номера всех позиций отбора, а не одной страницы.
     *
     * <p><b>Зачем.</b> Правка списком берёт то, что владелец отметил на
     * экране, а на экране пятьдесят строк. После переезда без колонки
     * «Выгружать» включить публикацию надо всему складу — у живого клиента
     * это 35 841 позиция, то есть семьсот семнадцать страниц по полсотни,
     * причём выделение сбрасывается при переходе на следующую. Прайс при
     * этом уезжает пустым, и площадка молча не заводит ни одного объявления.
     * Возможность была написана — {@code updateAll} принимает хоть весь
     * склад и проходит его за двадцать секунд, — и не было только способа
     * дотянуться до неё с экрана.
     *
     * <p>Отбор тот же, что у страницы и у выгрузки: правка обязана тронуть
     * ровно то, что владелец видел, — иначе он правит одно, а меняется
     * другое, и заметить это можно только пересчётом склада.
     *
     * <p>Порядок задан намеренно: без него база вправе вернуть строки
     * как попало, и «изменено 35 841» перестанет быть воспроизводимым
     * при разборе того, что именно уехало.
     */
    @Transactional(readOnly = true)
    public List<Long> idsMatching(String query, boolean withReserved, boolean withMissing,
                                  List<Long> warehouseIds, Vehicle vehicle,
                                  Map<String, String> columns, Map<String, String> words) {
        Filter filter = filterOf(query, withReserved, withMissing, warehouseIds, vehicle,
                columns, words);
        return jdbc.queryForList("SELECT p.id" + joins(filter.where()) + filter.where()
                        + " ORDER BY p.id",
                Long.class, filter.args().toArray());
    }

    /**
     * Курсор страницы числом, а не текстом.
     *
     * <p>Приходит он строкой — параметр один на все сортировки, — и мусор
     * в нём означает не отказ, а обычную страницу отступом: курсор это
     * ускорение, а не условие отбора, и уронить из-за него витрину значит
     * променять быстрый путь на отсутствующий.
     */
    private static Long cursorNumber(String after) {
        try {
            return Long.valueOf(after.strip());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Page finish(StringBuilder where, List<Object> args, String sort,
                        boolean descending, int page, int size, String after) {
        // Счёт берёт свою машину из применимости, только если по ней отбирают:
        // на число строк она не влияет (соединение левое и не больше строки).
        Long total = jdbc.queryForObject("SELECT count(*)" + joins(where) + where, Long.class,
                args.toArray());

        String order = orderBy(sort, descending);
        List<Object> pageArgs = new ArrayList<>(args);

        // Отступом или от последней строки предыдущей страницы.
        //
        // OFFSET заставляет базу прочитать и выбросить всё, что до него:
        // на складе в 35 841 позицию семисотая страница обходилась в 748 мс
        // против 82 мс на первой — замерено. От курсора это стоит столько же,
        // сколько первая страница.
        //
        // Только для сортировки по номеру товара, и это не лень: остальные
        // колонки бывают пустыми, а по колонке с NULL курсор не построить —
        // сравнение с NULL не отвечает ни «больше», ни «меньше», и страница
        // молча теряла бы строки. Листают подряд как раз склад целиком,
        // то есть в порядке по умолчанию; отсортировав по цене, владелец
        // смотрит верхушку, а не идёт вглубь.
        String pageWhere = "";
        if (after != null && !after.isBlank() && "code".equals(sort)) {
            pageWhere = " AND p.public_code " + (descending ? "<" : ">") + " ?";
            pageArgs.add(after);
        } else if (after != null && !after.isBlank() && "number".equals(sort)) {
            // Курсор по номеру позиции — тот же приём и по той же причине,
            // что и по номеру товара: колонка уникальна и без пустых значений,
            // значит следующая страница берётся от последней строки предыдущей,
            // а не отступом. Порядок по умолчанию теперь этот, и подряд листают
            // как раз его.
            Long from = cursorNumber(after);
            if (from != null) {
                pageWhere = " AND p.number " + (descending ? "<" : ">") + " ?";
                pageArgs.add(from);
            }
        }
        pageArgs.add(size);
        pageArgs.add(pageWhere.isEmpty() ? (long) page * size : 0L);

        // Сначала пятьдесят номеров — отбором и порядком, и своя машина
        // из применимости здесь участвует, только если по ней отбирают или
        // сортируют. Потом строки для них: колонки, подзапросы снимков, номеров
        // и своя машина считаются на пятьдесят позиций, а не на склад (задача
        // 0290). Порядок снаружи повторён тем же выражением: он полный
        // (последним ключом идёт p.id), значит строки встают так же.
        String pageIds = "WITH page AS (SELECT p.id" + joins(where, order) + where + pageWhere
                + " ORDER BY " + order + " LIMIT ? OFFSET ?)\n";

        List<Row> rows = jdbc.query(pageIds + """
                SELECT p.id, p.number, p.public_code, p.title, p.quality_grade, p.condition,
                       """ + VEHICLE_BRAND + " AS brand, " + VEHICLE_MODEL + " AS model, "
                       + VEHICLE_GENERATION + " AS generation, "
                       + VEHICLE_YEAR_FROM + " AS year_from, "
                       + VEHICLE_YEAR_TO + " AS year_to, "
                       + VEHICLE_SOURCE + " AS vehicle_source, own.spans::int AS vehicle_spans,"
                       + " CASE WHEN " + OWN_GENERATION_UNCONFIRMED + " THEN own.title_body END"
                       + " AS vehicle_title_body,"
                       + " CASE WHEN " + OWN_GENERATION_UNCONFIRMED + " THEN own.bodies END"
                       + " AS vehicle_block_bodies,"
                       + " coalesce(" + OWN_GENERATION_MERGED + ", false) AS vehicle_generation_merged,"
                       + """
                       """ + VEHICLE_BODY + " AS body_code, " + VEHICLE_ENGINE + " AS engine_code, "
                       + VEHICLE_YEAR + " AS year, " + DONOR_CODE + " AS donor_code," + """
                       p.price, p.installation_price, p.color, p.description, p.note,
                       p.manufacturer, p.marking, p.section, p.side_lr, p.side_fr,
                       p.qty_on_hand, p.is_published, p.barcode, p.legacy_code,
                       p.drom_ad_number, p.authenticity,
                       p.video_url, p.text_block,
                       p.weight_kg, p.length_mm, p.width_mm, p.height_mm,
                       p.package_weight_kg, p.package_length_mm,
                       p.package_width_mm, p.package_height_mm,
                       p.created_at, p.updated_at, p.price_changed_at,
                       pn.name AS part_name,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.updated_by)      AS updated_by_name,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.price_changed_by) AS price_changed_by_name,
                       (SELECT count(*) FROM part_photo ph2
                         WHERE ph2.part_id = p.id AND ph2.status = 'PROCESSED')         AS photo_count,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       (SELECT string_agg(o.raw_number, ', ' ORDER BY o.id) FROM part_oem o
                         WHERE o.part_id = p.id AND NOT o.is_primary) AS crosses,
                       -- Адрес полки. Код ячейки не доезжал до витрины вовсе:
                       -- на экране был виден только `section` — текстовое поле,
                       -- которое человек пишет руками, — а `storage_cell`,
                       -- который наполняют приёмка и сканер, не показывался
                       -- нигде, кроме ленты правок. То есть узнать, где деталь
                       -- лежит сейчас, можно было только если её когда-то
                       -- переставляли. Колонки две и обе нужны: у переехавшего
                       -- клиента заполнена «Секция», у заведённого у нас —
                       -- «Ячейка».
                       (SELECT c.code FROM storage_cell c
                         WHERE c.id = p.storage_cell_id) AS cell_code,
                       """ + GENERATION_BY_BODY + " AS generation_by_body," + """
                       -- Поставка и комплектация — для карточки позиции.
                       -- Читаются вместе со строкой: карточка открывается
                       -- по нажатию и второй запрос ради двух полей не делает.
                       """
                       + SupplyKinds.sqlLabelWithArrival("s") + " AS supply," + """
                       coalesce(p.manual_equipment, nullif(concat_ws(', ',
                           CASE d.steering WHEN 'RIGHT' THEN 'правый руль'
                                           WHEN 'LEFT' THEN 'левый руль' END,
                           CASE d.transmission_type WHEN 'AT' THEN 'АКПП'
                                                    WHEN 'MT' THEN 'МКПП'
                                                    WHEN 'CVT' THEN 'вариатор'
                                                    WHEN 'AMT' THEN 'робот' END,
                           CASE d.drive_type WHEN 'FWD' THEN 'передний'
                                             WHEN 'RWD' THEN 'задний'
                                             WHEN 'AWD' THEN 'полный' END,
                           d.transmission_model,
                           d.equipment_code), '')) AS equipment,
                       -- Только подтверждённый снимок: у оборванной загрузки
                       -- и у неподтверждённой записи файла в хранилище нет,
                       -- и в колонке «Превью» висит битая картинка. Прайс
                       -- и карточка это уже фильтруют, витрины — нет,
                       -- и поймано это глазами, а не тестом.
                       (SELECT ph.s3_key FROM part_photo ph
                         WHERE ph.part_id = p.id AND ph.status = 'PROCESSED'
                         ORDER BY ph.is_main DESC, ph.sort_order,
                               ph.id LIMIT 1) AS photo_key
                """ + JOINS + OWN_VEHICLE_OF_ROW
                + " WHERE p.id IN (SELECT id FROM page) ORDER BY " + order,
                (rs, i) -> new Row(
                        rs.getLong("id"), rs.getLong("number"),
                        rs.getString("public_code"), rs.getString("title"),
                        rs.getString("quality_grade"), rs.getString("condition"),
                        rs.getString("brand"), rs.getString("model"),
                        rs.getString("generation"),
                        (Integer) rs.getObject("year_from"), (Integer) rs.getObject("year_to"),
                        rs.getString("body_code"), rs.getString("engine_code"),
                        (Integer) rs.getObject("year"),
                        rs.getString("donor_code"),
                        rs.getBigDecimal("price"), rs.getBigDecimal("installation_price"),
                        rs.getString("color"), rs.getString("description"), rs.getString("note"),
                        rs.getString("manufacturer"), rs.getString("marking"),
                        rs.getString("section"), rs.getString("cell_code"),
                        rs.getString("side_lr"), rs.getString("side_fr"),
                        rs.getBigDecimal("qty_on_hand"),
                        rs.getString("oem"), rs.getString("crosses"), rs.getString("photo_key"),
                        rs.getString("supply"), rs.getString("equipment"),
                        rs.getString("part_name"),
                        (Boolean) rs.getObject("is_published"),
                        rs.getString("barcode"),
                        rs.getString("legacy_code"),
                        rs.getString("drom_ad_number"),
                        rs.getString("authenticity"),
                        rs.getString("video_url"), rs.getString("text_block"),
                        rs.getBigDecimal("weight_kg"),
                        dimensions(rs.getObject("length_mm", Integer.class),
                                rs.getObject("width_mm", Integer.class),
                                rs.getObject("height_mm", Integer.class)),
                        dimensions(rs.getObject("package_length_mm", Integer.class),
                                rs.getObject("package_width_mm", Integer.class),
                                rs.getObject("package_height_mm", Integer.class)),
                        rs.getBigDecimal("package_weight_kg"),
                        instant(rs, "created_at"),
                        instant(rs, "updated_at"),
                        rs.getString("updated_by_name"),
                        instant(rs, "price_changed_at"),
                        rs.getString("price_changed_by_name"),
                        rs.getInt("photo_count"),
                        rs.getString("vehicle_source"),
                        (Integer) rs.getObject("vehicle_spans"),
                        rs.getString("vehicle_title_body"),
                        bodyNumbers(rs.getString("vehicle_block_bodies")),
                        rs.getString("generation_by_body"),
                        rs.getBoolean("vehicle_generation_merged"),
                        Map.of()),
                pageArgs.toArray());

        return new Page(total == null ? 0 : total, withStock(rows));
    }

    /**
     * Габариты одной строкой «120×80×45».
     *
     * <p>Тремя колонками они не показываются: по отдельности «длина 120»
     * не отвечает ни на один вопрос, а вместе отвечают на единственный —
     * влезет ли в коробку. Пусто, если не заполнено ни одно измерение.
     */
    /**
     * Момент из timestamptz.
     *
     * <p>Через {@code getObject(..., Instant.class)} нельзя: драйвер Postgres
     * такое преобразование не умеет и отвечает «conversion to class
     * java.time.Instant from timestamptz not supported» — вся витрина
     * при этом падает 409-м. Поймано живым запросом, тесты витрины
     * этих колонок не касались.
     */
    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final java.util.regex.Pattern BODY_NUMBER =
            java.util.regex.Pattern.compile("(?<![0-9])[0-9]{2,3}(?![0-9])");

    /**
     * Номера кузовов блока для подсказки клетки (задача 0294): «GRJ150W,
     * GRJ151W, KDJ150» → «150, 151». Человек сверяет их с номером
     * в наименовании, а не коды целиком — двадцать кодов одного ряда
     * в подсказке не прочесть.
     */
    static String bodyNumbers(String bodies) {
        if (bodies == null) {
            return null;
        }
        var numbers = new java.util.TreeSet<Integer>();
        BODY_NUMBER.matcher(bodies).results()
                .forEach(found -> numbers.add(Integer.parseInt(found.group())));
        if (numbers.isEmpty()) {
            return null;
        }
        return numbers.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String dimensions(Integer length, Integer width, Integer height) {
        if (length == null && width == null && height == null) {
            return null;
        }
        return "%s×%s×%s".formatted(
                length == null ? "?" : length,
                width == null ? "?" : width,
                height == null ? "?" : height);
    }

    /**
     * Остатки по складам — одним запросом на страницу, а не на строку.
     *
     * <p>Тридцать пять тысяч позиций показываются постранично, но пятьдесят
     * запросов на страницу — это пятьдесят обращений к базе там, где хватает
     * одного.
     */
    private List<Row> withStock(List<Row> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Long[] ids = rows.stream().map(Row::id).toArray(Long[]::new);
        Map<Long, Map<Long, BigDecimal>> byPart = new java.util.HashMap<>();
        jdbc.query("""
                SELECT part_id, warehouse_id, sum(qty) AS qty FROM part_stock
                 WHERE part_id = ANY (?) GROUP BY part_id, warehouse_id""",
                rs -> {
                    byPart.computeIfAbsent(rs.getLong("part_id"), key -> new java.util.HashMap<>())
                            .put(rs.getLong("warehouse_id"), rs.getBigDecimal("qty"));
                },
                (Object) ids);

        return rows.stream()
                .map(row -> row.withStock(byPart.getOrDefault(row.id(), Map.of())))
                .toList();
    }

    /**
     * Пишет витрину в поток — всю, что прошла отбор.
     *
     * <p><b>Потоком, а не списком.</b> Тридцать пять тысяч строк с двадцатью
     * с лишним колонками, собранные в памяти перед отправкой, — это сотни
     * мегабайт на каждого скачивающего. Та же причина, по которой прайс
     * площадки пишется через StAX.
     *
     * <p>Курсор работает только внутри транзакции: при включённом autoCommit
     * Postgres игнорирует {@code fetchSize} и вычитывает весь склад в память.
     *
     * <p>Отбор тот же, что и у страницы: скачанный файл обязан совпасть с тем,
     * что владелец видел на экране, — ради этой сверки он его и качает.
     *
     * <p>Идентификаторы складов подставляются в SQL текстом, и это безопасно:
     * они пришли из нашей же таблицы, а не из запроса. Параметром колонку
     * не задать.
     */
    @Transactional(readOnly = true)
    public void export(String query, boolean withReserved, boolean withMissing,
                       List<Long> warehouseIds, Vehicle vehicle,
                       Map<String, String> columns, Map<String, String> words,
                       String sort, boolean descending,
                       List<Warehouse> warehouses, RowWriter writer) {

        Filter filter = filterOf(query, withReserved, withMissing, warehouseIds, vehicle,
                columns, words);
        String order = orderBy(sort, descending);

        StringBuilder stock = new StringBuilder();
        for (Warehouse warehouse : warehouses) {
            stock.append("""
                    ,
                           (SELECT sum(s.qty - s.qty_reserved) FROM part_stock s
                             WHERE s.part_id = p.id AND s.warehouse_id = %1$d) AS free_%1$d,
                           (SELECT sum(s.qty_reserved) FROM part_stock s
                             WHERE s.part_id = p.id AND s.warehouse_id = %1$d) AS res_%1$d"""
                    .formatted(warehouse.id()));
        }

        String sql = """
                SELECT p.number, p.public_code, p.title, p.condition,
                       """ + VEHICLE_BRAND + " AS brand, " + VEHICLE_MODEL + " AS model, "
                       + VEHICLE_YEAR_FROM + " AS year_from, "
                       + VEHICLE_YEAR_TO + " AS year_to,"
                       + VEHICLE_BODY + " AS body_code, " + VEHICLE_ENGINE + " AS engine_code, "
                       + VEHICLE_YEAR + " AS year, " + DONOR_CODE + " AS donor_code," + """
                       p.side_fr, p.side_lr, p.price, p.installation_price, p.color,
                       p.description, p.manufacturer, p.marking, p.section, p.note,
                       (SELECT o.raw_number FROM part_oem o
                         WHERE o.part_id = p.id AND o.is_primary LIMIT 1) AS oem,
                       (SELECT string_agg(o.raw_number, ', ' ORDER BY o.id) FROM part_oem o
                         WHERE o.part_id = p.id AND NOT o.is_primary) AS crosses,
                       pn.name AS part_name, p.is_published, p.barcode,
                       p.legacy_code, p.video_url, p.text_block,
                       p.weight_kg, p.length_mm, p.width_mm, p.height_mm,
                       p.package_weight_kg, p.package_length_mm,
                       p.package_width_mm, p.package_height_mm,
                       p.created_at, p.updated_at, p.price_changed_at,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.updated_by) AS updated_by_name,
                       (SELECT tm.display_name FROM tenant_member tm
                         WHERE tm.id = p.price_changed_by) AS price_changed_by_name,
                       (SELECT count(*) FROM part_photo ph
                         WHERE ph.part_id = p.id AND ph.status = 'PROCESSED') AS photo_count,
                       """
                       // Оценка словом, как и стороны ниже: файл открывают
                       // в Excel и читают глазами, и «NO_DEFECTS» там — та же
                       // утечка внутреннего представления, что «REAR».
                       // Выражение одно с колонкой на экране: второй копии
                       // тут взяться неоткуда.
                       + QUALITY_GRADE + " AS quality_grade,"
                       + SupplyKinds.sqlLabelWithArrival("sp") + " AS supply_label," + """
                       coalesce(p.manual_equipment, nullif(concat_ws(', ',
                           CASE d.steering WHEN 'RIGHT' THEN 'правый руль'
                                           WHEN 'LEFT' THEN 'левый руль' END,
                           CASE d.transmission_type WHEN 'AT' THEN 'АКПП'
                                                    WHEN 'MT' THEN 'МКПП'
                                                    WHEN 'CVT' THEN 'вариатор' END,
                           CASE d.drive_type WHEN 'FWD' THEN 'передний'
                                             WHEN 'RWD' THEN 'задний'
                                             WHEN 'AWD' THEN 'полный' END,
                           d.transmission_model,
                           d.equipment_code), '')) AS equipment"""
                + stock + """

                  FROM part p
                  LEFT JOIN donor d ON d.id = p.donor_id
                  LEFT JOIN catalog.brand b ON b.id = d.brand_id
                  LEFT JOIN catalog.model m ON m.id = d.model_id
                  LEFT JOIN catalog.generation g ON g.id = d.generation_id
                  LEFT JOIN part_name pn ON pn.id = p.part_name_id
                  LEFT JOIN supply sp ON sp.id = d.supply_id"""
                // Своя машина позиции без донора — та же, что у колонок
                // на экране (задача 0290): файл сверяют с экраном.
                + OWN_VEHICLE
                + filter.where() + " ORDER BY " + order;

        jdbc.query(connection -> {
            var ps = connection.prepareStatement(sql);
            // Курсор пачками: без этого драйвер вычитывает весь склад разом.
            ps.setFetchSize(500);
            for (int at = 0; at < filter.args().size(); at++) {
                Object arg = filter.args().get(at);
                if (arg instanceof Long[] array) {
                    ps.setArray(at + 1, connection.createArrayOf("bigint", array));
                } else {
                    ps.setObject(at + 1, arg);
                }
            }
            return ps;
        }, rs -> {
            List<String> cells = new ArrayList<>();
            // Первой колонкой, как и на экране: по этому номеру деталь
            // называют в разговоре, и файл открывают как раз затем, чтобы
            // свериться с тем, что видно на витрине.
            cells.add(number(rs, "number"));
            cells.add(text(rs, "public_code"));
            cells.add(text(rs, "title"));
            cells.add(text(rs, "quality_grade"));
            cells.add(text(rs, "brand"));
            cells.add(text(rs, "model"));
            cells.add(text(rs, "year_from"));
            cells.add(text(rs, "year_to"));
            cells.add(text(rs, "body_code"));
            cells.add(text(rs, "engine_code"));
            cells.add(text(rs, "year"));
            // Словами, а не кодами: файл открывают в Excel и читают глазами,
            // и «REAR» там означает утечку внутреннего представления.
            // В выгрузке прежней системы стоит ровно «Задн.» и «Лев.».
            cells.add(SIDE_FR.getOrDefault(text(rs, "side_fr"), ""));
            cells.add(SIDE_LR.getOrDefault(text(rs, "side_lr"), ""));
            cells.add(text(rs, "donor_code"));
            cells.add(number(rs, "price"));
            cells.add(number(rs, "installation_price"));
            cells.add(text(rs, "color"));
            cells.add(text(rs, "description"));
            cells.add(text(rs, "manufacturer"));
            cells.add(text(rs, "oem"));
            cells.add(text(rs, "crosses"));
            cells.add(text(rs, "note"));
            cells.add(text(rs, "marking"));
            cells.add(text(rs, "section"));
            cells.add(text(rs, "part_name"));
            cells.add(text(rs, "supply_label"));
            cells.add(text(rs, "equipment"));
            cells.add(rs.getBoolean("is_published") ? "Везде" : "Нет");
            cells.add(number(rs, "photo_count"));
            cells.add(text(rs, "text_block"));
            cells.add(text(rs, "video_url"));
            cells.add(number(rs, "weight_kg"));
            cells.add(dimensionsOf(rs, "length_mm", "width_mm", "height_mm"));
            cells.add(dimensionsOf(rs, "package_length_mm",
                    "package_width_mm", "package_height_mm"));
            cells.add(number(rs, "package_weight_kg"));
            cells.add(text(rs, "barcode"));
            cells.add(text(rs, "legacy_code"));
            cells.add(day(rs, "created_at"));
            cells.add(day(rs, "updated_at"));
            cells.add(text(rs, "updated_by_name"));
            cells.add(day(rs, "price_changed_at"));
            cells.add(text(rs, "price_changed_by_name"));
            for (Warehouse warehouse : warehouses) {
                cells.add(number(rs, "free_" + warehouse.id()));
                cells.add(number(rs, "res_" + warehouse.id()));
            }
            writer.write(cells);
        });
    }

    /** Заголовок выгрузки: тот же состав и порядок, что у строк. */
    public static List<String> exportHeader(List<Warehouse> warehouses) {
        List<String> header = new ArrayList<>(List.of(
                "№ позиции",
                "Номер товара", "Запчасть", "Оценка состояния", "Марка", "Модель",
                "Поколение с", "Поколение по", "Кузов", "Двигатель", "Год выпуска",
                "Передний / Задний", "Левый / Правый", "Номер донора",
                "Цена", "Установка", "Цвет", "Комментарий", "Производитель",
                "Номер производителя", "Кросс-номера", "Заметка", "Маркировка", "Секция",
                // Скачанный файл обязан совпадать с тем, что владелец видел
                // на экране, — ради этой сверки он его и качает.
                "Наименование", "Поставка", "Комплектация", "Выгружать",
                "Количество фото", "Текстовый блок", "Видео", "Вес товара",
                "Габариты товара", "Габариты товара в упаковке", "Вес в упаковке",
                "Ст. баркод", "Старые данные", "Создан", "Изменён", "Кто изменил",
                "Цена изменена в", "Кто изменил цену"));
        for (Warehouse warehouse : warehouses) {
            header.add(warehouse.name() + " (свободно)");
            header.add(warehouse.name() + " (резерв)");
        }
        return header;
    }

    /** Дата без времени: в файле на тридцать пять тысяч строк время — шум. */
    private static String day(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? "" : java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy")
                .withZone(java.time.ZoneId.systemDefault())
                .format(value.toInstant());
    }

    private static String dimensionsOf(java.sql.ResultSet rs, String a, String b, String c)
            throws java.sql.SQLException {
        String value = dimensions(rs.getObject(a, Integer.class),
                rs.getObject(b, Integer.class), rs.getObject(c, Integer.class));
        return value == null ? "" : value;
    }

    private static String text(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    private static String number(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** Приёмник строки выгрузки: реализуется контроллером, пишущим в ответ. */
    public interface RowWriter {
        void write(List<String> cells);
    }

    /** Склады арендатора: из них получаются колонки остатка. */
    @Transactional(readOnly = true)
    public List<Warehouse> warehouses() {
        return jdbc.query("SELECT id, name FROM warehouse ORDER BY id",
                (rs, i) -> new Warehouse(rs.getLong("id"), rs.getString("name")));
    }

    public record Warehouse(Long id, String name) {
    }

    public record Page(long total, List<Row> rows) {
    }

    /**
     * @param stock остаток по складам: ключ — идентификатор склада. Колонок
     *              столько, сколько складов у клиента
     */
    public record Row(Long id,
                      /**
                       * Порядковый номер позиции — тот, который называют вслух
                       * («посмотри позицию 347»). Не {@code id}: тот внутренний
                       * и наружу не показывается.
                       */
                      Long number,
                      String code, String title, String qualityGrade, String condition,
                      String brand, String model, String generation,
                      Integer yearFrom, Integer yearTo, String body, String engine,
                      Integer year, String donorCode,
                      BigDecimal price, BigDecimal installationPrice,
                      String color, String description, String note,
                      String manufacturer, String marking, String section,
                      /**
                       * Код ячейки хранения. Не то же, что {@code section}:
                       * ту человек пишет руками своей нумерацией полок,
                       * а ячейку заводят складом и печатают на этикетке.
                       */
                      String cellCode,
                      String sideLr, String sideFr, BigDecimal qty,
                      String oem, String crosses, String photoKey,
                      String supply, String equipment,
                      // Паритет с таблицей товаров прежней системы: у неё
                      // сорок две колонки, и владелец переехавшего клиента
                      // ищет глазами те же, к которым привык.
                      String partName, Boolean published, String barcode,
                      /**
                       * Номер товара в прежней системе. Сырые данные переезда
                       * (legacy_data) наружу не идут: это jsonb со строкой
                       * чужой выгрузки, то есть внутреннее представление.
                       */
                      String legacyCode,
                      /**
                       * Номер объявления Дрома, только цифры (задача 0274): по нему
                       * клиент сверяет перенесённую позицию со своим кабинетом.
                       */
                      String dromAdNumber,
                      /**
                       * Оригинальность словом площадки, дословно («Оригинал»):
                       * приезжает файлом переноса с Дрома (задача 0277).
                       */
                      String authenticity,
                      String videoUrl, String textBlock,
                      BigDecimal weightKg, String dimensions, String packageDimensions,
                      BigDecimal packageWeightKg,
                      Instant createdAt, Instant updatedAt, String updatedByName,
                      Instant priceChangedAt, String priceChangedByName,
                      int photoCount,
                      /**
                       * Откуда машина в колонках «Марка», «Модель», «Поколение»:
                       * {@code DONOR} — донор, {@code VERIFIED} — применимость,
                       * подтверждённая человеком, {@code PARSED} — разобранная
                       * автоматически; пусто — машины нет (задача 0290).
                       */
                      String vehicleSource,
                      /**
                       * Сколько поколений у своей машины в применимости; больше
                       * одного — поколение и годы не показываются: какое из них
                       * у детали, неизвестно.
                       */
                      Integer vehicleSpans,
                      /**
                       * Номер кузова из наименования («120»), когда поколение
                       * не показано потому, что кузова блока его не подтвердили —
                       * другого ряда или их нет вовсе (задачи 0294, 0299); иначе пусто.
                       */
                      String vehicleTitleBody,
                      /** Номера кузовов того поколения блока («150, 151») — к той же подсказке. */
                      String vehicleBlockBodies,
                      /**
                       * Коды кузова, по которым поколение своей машины определено
                       * нами, а не названо клиентом («GRJ120, KDJ120», задача 0295);
                       * иначе пусто.
                       */
                      String vehicleGenerationBodies,
                      /**
                       * Поколение в колонке — поколение Дрома целиком, собранное из
                       * нескольких строк справочника (рестайлингов, задача 0297):
                       * годы от первой строки до последней.
                       */
                      boolean vehicleGenerationMerged,
                      Map<Long, BigDecimal> stock) {

        Row withStock(Map<Long, BigDecimal> found) {
            return new Row(id, number, code, title, qualityGrade, condition, brand, model,
                    generation,
                    yearFrom, yearTo, body, engine, year, donorCode, price, installationPrice,
                    color, description, note, manufacturer, marking, section, cellCode,
                    sideLr, sideFr,
                    qty, oem, crosses, photoKey, supply, equipment,
                    partName, published, barcode, legacyCode, dromAdNumber, authenticity,
                    videoUrl, textBlock, weightKg, dimensions, packageDimensions,
                    packageWeightKg, createdAt, updatedAt, updatedByName,
                    priceChangedAt, priceChangedByName, photoCount, vehicleSource,
                    vehicleSpans, vehicleTitleBody, vehicleBlockBodies, vehicleGenerationBodies,
                    vehicleGenerationMerged,
                    found);
        }
    }
}
