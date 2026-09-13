package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Права рантайм-роли на схему арендатора.
 *
 * <p>Выдаются владельцем схемы сразу после её создания и при накате миграций:
 * новая таблица без прав означает арендатора, который открывается и падает
 * на первом же запросе к ней.
 *
 * <p><b>Журналы отдаются только на чтение и на добавление.</b> `stock_movement`,
 * `audit_log` и `customer_account_entry` — это то, что предъявляют, когда
 * спрашивают «куда делась деталь» и «кто уронил цену». Пока приложение ходило
 * владельцем, `REVOKE` не значил ничего: владелец возвращает себе право одной
 * командой. Теперь владелец — другая роль, и правка журнала прямым SQL
 * из приложения невозможна, а не «запрещена договорённостью».
 *
 * <p>Исправление ошибки в журнале при этом не исчезает — оно идёт встречной
 * записью, как и было задумано: неизменяемость журнала не мешает работе,
 * она мешает переписать историю.
 *
 * <p>Сам список журналов живёт в {@link JournalProtection#JOURNALS} — там,
 * где его проверяют. Две копии одного перечня расходятся молча, и цена
 * расхождения тут несимметричная: забытый здесь журнал остаётся
 * изменяемым, а проверка при этом отвечает «защищены».
 */
@Component
public class SchemaGrants {

    private static final Logger log = LoggerFactory.getLogger(SchemaGrants.class);

    /**
     * Служебные таблицы ячейки в {@code public}, которые правит рабочая роль.
     *
     * <p>Хранилище сессий Spring Session: строка сессии и её атрибуты.
     * {@code shedlock} и {@code tenant_registry} сюда не входят — их права
     * выданы при заведении роли и с тех пор не менялись.
     */
    private static final List<String> CELL_TABLES =
            List.of("spring_session", "spring_session_attributes");

    private final JdbcTemplate owner;
    private final String runtimeRole;

    public SchemaGrants(@SchemaOwnerDataSource.SchemaOwner DataSource ownerDataSource,
                        @Value("${app.runtime-role:}") String runtimeRole) {
        this.owner = new JdbcTemplate(ownerDataSource);
        this.runtimeRole = runtimeRole == null ? "" : runtimeRole.strip();
    }

    /** Настроено ли разделение ролей: в разработке его нет, и это законно. */
    public boolean enabled() {
        return !runtimeRole.isBlank();
    }

    /**
     * Выдаёт рантайм-роли права на схему и запирает журналы.
     *
     * <p>Идемпотентно: повторная выдача ничего не ломает, а нужна она после
     * каждой миграции — таблица, добавленная changeset'ом, прав не наследует.
     */
    public void apply(String schema) {
        if (!enabled()) {
            return;
        }
        requireSafeName(schema);
        String role = requireSafeName(runtimeRole);

        owner.execute("GRANT USAGE ON SCHEMA %s TO %s".formatted(schema, role));
        owner.execute("""
                GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %s TO %s"""
                .formatted(schema, role));
        owner.execute(
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %s TO %s".formatted(schema, role));

        for (String journal : JournalProtection.JOURNALS) {
            owner.execute("REVOKE UPDATE, DELETE ON %s.%s FROM %s".formatted(schema, journal, role));
        }
        log.debug("Схема {}: права выданы роли {}, журналы заперты", schema, runtimeRole);
    }

    /**
     * Права рабочей роли на служебные таблицы ячейки в {@code public}.
     *
     * <p>Сегодня это хранилище сессий (задача 0080): состояние сессии живёт
     * в общей схеме, и рабочая роль пишет туда на каждом входе. Без этих
     * прав ячейка не «теряет возможность», а <b>перестаёт пускать вообще
     * кого-либо</b> — сессию некуда записать, то есть вход отвечает
     * пятисоткой при верном пароле.
     *
     * <p><b>Поэтому права выдаёт накат, а не только {@code ops/create-roles.sh}.</b>
     * Тот заводит роль один раз, при включении разделения, — а таблица
     * появилась позже него, и на работающей ячейке никто не перезапускает
     * скрипт заведения ролей после выкладки. Это ровно та ловушка, что уже
     * записана в корневом {@code CLAUDE.md}: «права выдаются и схемам,
     * которым нечего накатывать», только цена здесь выше — не склад
     * без доступа, а никто не может войти.
     *
     * <p>Идемпотентно, как и {@link #apply(String)}: {@code GRANT} повторно
     * ничего не ломает.
     */
    public void applyCellTables() {
        if (!enabled()) {
            return;
        }
        String role = requireSafeName(runtimeRole);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + role);
        for (String table : CELL_TABLES) {
            owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.%s TO %s"
                    .formatted(table, role));
        }
        log.debug("Служебные таблицы ячейки: права выданы роли {}", runtimeRole);
    }

    /**
     * Имя схемы и роли подставляются в SQL текстом — параметром нельзя.
     *
     * <p>Схема приходит из реестра, где стоит {@code CHECK}, а роль — из
     * настройки ячейки; и всё же проверка нужна: цена ошибки тут не «запрос
     * не выполнится», а выполненный чужой SQL.
     */
    private static String requireSafeName(String name) {
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Недопустимое имя: " + name);
        }
        return name;
    }
}
