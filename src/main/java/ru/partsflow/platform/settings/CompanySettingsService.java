package ru.partsflow.platform.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
     * Чем отвечаем, если строки настроек в схеме ещё нет.
     *
     * <p>Схема арендатора, отставшая от кода, — обычное состояние между
     * накатом и деплоем (о нём предупреждает {@code SchemaVersionCheck}).
     * Отсутствие строки не повод отказать в продаже: берём то же число,
     * что стояло константой до задачи 0049.
     */
    static final int FALLBACK_RESERVATION_DAYS = 3;

    private final JdbcTemplate jdbc;

    public CompanySettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CompanySettings read() {
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
     */
    @Transactional
    public CompanySettings update(int reservationDays) {
        if (reservationDays < MIN_RESERVATION_DAYS || reservationDays > MAX_RESERVATION_DAYS) {
            throw new IllegalArgumentException(
                    "Срок резервирования — от %d до %d дней".formatted(
                            MIN_RESERVATION_DAYS, MAX_RESERVATION_DAYS));
        }
        jdbc.update("""
                INSERT INTO company_setting (id, reservation_days) VALUES (1, ?)
                ON CONFLICT (id)
                DO UPDATE SET reservation_days = EXCLUDED.reservation_days, updated_at = now()""",
                reservationDays);
        return new CompanySettings(reservationDays);
    }

    /**
     * @param reservationDays на сколько дней откладывается товар в новой
     *                        сделке, если продавец не назвал свой срок
     */
    public record CompanySettings(int reservationDays) {
    }
}
