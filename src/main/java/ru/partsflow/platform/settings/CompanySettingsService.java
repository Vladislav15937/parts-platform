package ru.partsflow.platform.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.tenant.TenantContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Настройки компании: то, что владелец ставит один раз, а видят все.
 *
 * <p><b>Чем это отличается от {@link MemberSettingsService}.</b> Там —
 * состояние экрана одного человека (сортировка, отборы, колонки), оно никого,
 * кроме него, не касается и лежит свободным jsonb. Здесь — данные предприятия:
 * срок резерва ставит владелец, а последствие видит каждый продавец в каждой
 * сделке. Поэтому колонки, типы и {@code CHECK} в схеме (tenant/066),
 * а не мешок с ключами.
 *
 * <p><b>Читается на каждой сделке, и не кэшируется намеренно.</b> Это один
 * запрос по первичному ключу однострочной таблицы против десятков сделок
 * в день; кэш же пришлось бы сбрасывать по арендаторам во всех узлах ячейки,
 * и цена ошибки тут — владелец поменял срок, а сделки ещё сутки уезжают
 * со старым, причём объяснить это ему нечем.
 */
@Service
public class CompanySettingsService {

    /**
     * Границы срока резерва.
     *
     * <p>Снизу — один день, а не ноль: ноль в нашей системе не означает
     * «без резерва». Сделка резервирует товар всегда, автоснятия резерва нет,
     * и ноль дал бы сделку, родившуюся с красной пометкой «срок истёк».
     * Расширить границу вниз позже безопасно, сузить обратно — нет
     * (db/CLAUDE.md про откат, сужающий {@code CHECK}).
     *
     * <p>Сверху — год: {@code Instant.now().plus(Duration.ofDays(n))} при
     * большом {@code n} бросает {@code DateTimeException}, то есть пятисотку
     * на оформлении сделки.
     */
    public static final int MIN_RESERVATION_DAYS = 1;

    public static final int MAX_RESERVATION_DAYS = 365;

    /**
     * Чем отвечаем, если настройки в схеме ещё нет.
     *
     * <p>Схема арендатора, отставшая от кода, — обычное состояние между
     * накатом и деплоем (о нём предупреждает {@code SchemaVersionCheck}),
     * и отставание это <b>не «строки нет», а таблицы нет целиком</b>: строку
     * заводит тот же changeset, что и таблицу. Ни то ни другое не повод
     * отказать в продаже — берём то же число, что стояло константой
     * до задачи 0049.
     */
    static final int FALLBACK_RESERVATION_DAYS = 3;

    private static final Logger log = LoggerFactory.getLogger(CompanySettingsService.class);

    /** Схемы, про которые уже сказано, что они отстали: по строке, а не по сделке. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private final JdbcTemplate jdbc;

    public CompanySettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Срок резерва по умолчанию.
     *
     * <p><b>Отставшая схема не роняет продажу — и это главное в этом методе.</b>
     * Зовут его не только настройки: с задачи 0049 он стоит на пути создания
     * <b>каждой</b> сделки. Схема арендатора, не докатанная до
     * {@code tenant/066}, — обычное состояние между накатом и деплоем
     * ({@code SchemaVersionCheck} про это и пишет при старте), и отсутствие
     * таблицы уходило бы оттуда {@code BadSqlGrammarException}'ом, то есть
     * пятисоткой на самом частом действии продавца. До задачи 0049 продажа
     * на такой схеме работала всегда — там стояла константа, — и настройка
     * срока резерва не имеет права этого отнять. Тот же довод, по которому
     * не валит вход {@code LoginSessions} и не валит старт
     * {@code JournalProtectionCheck}.
     *
     * <p><b>Спрашиваем каталог, а не ловим отказ.</b> Перехват сломанного
     * запроса стоил бы дороже, чем выглядит: внутри чужой транзакции
     * отказавшая инструкция помечает её на откат целиком, и всё, что вызвавший
     * сделает дальше, получит «current transaction is aborted». Сегодня
     * {@code read()} зовут вне чужой транзакции, но это условие нигде
     * не записано и переживёт не всякую правку. {@code to_regclass} — поиск
     * по системному каталогу, а не по складу.
     *
     * <p>Пусто при существующей таблице — случай отдельный и отвечаем на него
     * так же: строку заводит тот же changeset, что и таблицу, но арендатора
     * могли восстановить из дампа, снятого посреди миграции.
     */
    @Transactional(readOnly = true)
    public CompanySettings read() {
        if (!tableExists()) {
            warnOnce();
            return new CompanySettings(FALLBACK_RESERVATION_DAYS);
        }
        List<Integer> found = jdbc.queryForList(
                "SELECT reservation_days FROM company_setting WHERE id = 1", Integer.class);
        return new CompanySettings(found.isEmpty() ? FALLBACK_RESERVATION_DAYS : found.get(0));
    }

    /**
     * Пишет настройку целиком.
     *
     * <p>{@code INSERT … ON CONFLICT}, а не {@code UPDATE}: строку заводит
     * changeset, но у арендатора, заведённого до него и накатанного позже,
     * её может не быть в момент, когда владелец открыл экран, — и «настройка
     * не сохранилась» без единого слова было бы худшим из ответов.
     *
     * <p><b>А вот отставшая схема на записи отвечает отказом, и это обратное
     * решение по сравнению с чтением.</b> Прочитать можно с умолчанием —
     * продажа от этого идёт как раньше; сохранить некуда, и молчаливое
     * «сохранено» было бы враньём: владелец ушёл бы со страницы уверенным,
     * что у него теперь сутки, а сделки продолжали бы откладываться на трое.
     * Поэтому 409 со словами, называющими и причину, и кто её чинит.
     */
    @Transactional
    public CompanySettings update(int reservationDays) {
        if (reservationDays < MIN_RESERVATION_DAYS || reservationDays > MAX_RESERVATION_DAYS) {
            throw new IllegalArgumentException(
                    "Срок резервирования — от %d до %d дней".formatted(
                            MIN_RESERVATION_DAYS, MAX_RESERVATION_DAYS));
        }
        if (!tableExists()) {
            warnOnce();
            throw new IllegalStateException(
                    "Настройки компании ещё не накатаны на схему — сохранить срок "
                            + "некуда. Накатите миграции (ops/migrate-tenants.sh) "
                            + "и повторите; пока действует прежний срок "
                            + FALLBACK_RESERVATION_DAYS + " дня.");
        }
        jdbc.update("""
                INSERT INTO company_setting (id, reservation_days) VALUES (1, ?)
                ON CONFLICT (id)
                DO UPDATE SET reservation_days = EXCLUDED.reservation_days, updated_at = now()""",
                reservationDays);
        return new CompanySettings(reservationDays);
    }

    /**
     * Есть ли таблица в схеме, на которую сейчас смотрит соединение.
     *
     * <p>{@code current_schema()} — первая схема из {@code search_path}, то есть
     * схема арендатора: её ставит провайдер соединений Hibernate внутри
     * транзакции. Без квалификации {@code to_regclass} нашла бы одноимённую
     * таблицу в {@code public}, если та когда-нибудь появится, — и ответ
     * «таблица есть» относился бы к чужой схеме.
     */
    private boolean tableExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(current_schema() || '.company_setting') IS NOT NULL",
                Boolean.class));
    }

    /**
     * Про отставшую схему говорим один раз, а не на каждую сделку.
     *
     * <p>Строка в логе на каждую продажу утопила бы всё остальное ровно тогда,
     * когда в логе ищут причину. Набор ограничен числом арендаторов ячейки
     * и после наката просто перестаёт пополняться.
     */
    private void warnOnce() {
        String schema = TenantContext.getOrNull();
        if (warned.add(String.valueOf(schema))) {
            log.warn("Схема {} не накатана до tenant/066: настройки компании нет, "
                            + "срок резерва берётся прежний — {} дня. "
                            + "Накатить: ops/migrate-tenants.sh",
                    schema, FALLBACK_RESERVATION_DAYS);
        }
    }

    /**
     * @param reservationDays на сколько дней откладывается товар в новой
     *                        сделке, если продавец не назвал свой срок
     */
    public record CompanySettings(int reservationDays) {
    }
}
