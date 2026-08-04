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
 */
@Component
public class SchemaGrants {

    private static final Logger log = LoggerFactory.getLogger(SchemaGrants.class);

    /** Что нельзя менять после записи. */
    private static final List<String> JOURNALS =
            List.of("stock_movement", "audit_log", "customer_account_entry");

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

        for (String journal : JOURNALS) {
            owner.execute("REVOKE UPDATE, DELETE ON %s.%s FROM %s".formatted(schema, journal, role));
        }
        log.debug("Схема {}: права выданы роли {}, журналы заперты", schema, runtimeRole);
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
