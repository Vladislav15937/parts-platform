package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Проверяет при старте, действует ли защита журналов.
 *
 * <p>Разделение ролей держится не на коде, а на настройке ячейки: забыли
 * переменные окружения — приложение поднимется владельцем схем и будет
 * работать как ни в чём не бывало, только журнал снова станет правиться
 * прямым SQL. Отличить одно от другого по поведению нельзя: склад работает
 * одинаково, а разница видна лишь в тот день, когда журнал предъявляют
 * как доказательство.
 *
 * <p>Поэтому приложение спрашивает у базы прямо: могу ли я переписать
 * движение склада? Если да — говорит об этом громко. Проверка стоит одного
 * запроса и не зависит от того, что написано в настройках: она смотрит
 * на фактические права той роли, под которой работает.
 *
 * <p>Запуск при этом не валится. Разработка и первые прогоны идут одной
 * ролью намеренно, и падение на старте означало бы, что локально ничего
 * не поднять без двух ролей.
 */
@Component
public class JournalProtectionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JournalProtectionCheck.class);

    /** Что должно быть неизменяемым: те же три таблицы, что и в SchemaGrants. */
    private static final List<String> JOURNALS =
            List.of("stock_movement", "audit_log", "customer_account_entry");

    private final JdbcTemplate jdbc;

    public JournalProtectionCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        String schema;
        try {
            schema = jdbc.query("""
                    SELECT schema_name FROM public.tenant_registry
                     WHERE status = 'ACTIVE' ORDER BY tenant_id LIMIT 1""",
                    rs -> rs.next() ? rs.getString(1) : null);
        } catch (RuntimeException e) {
            // Реестра ещё нет — ячейка поднимается впервые, проверять нечего.
            return;
        }
        if (schema == null) {
            return;
        }

        List<String> writable = JOURNALS.stream()
                .filter(journal -> canUpdate(schema, journal))
                .toList();

        if (writable.isEmpty()) {
            log.info("Журналы защищены: рабочая роль {} не может править {}",
                    currentUser(), JOURNALS);
            return;
        }

        log.warn("""
                Журналы правятся прямым SQL из приложения: {} доступны на UPDATE \
                роли {}. Это законно в разработке и опасно в бою: «кто уронил цену» \
                и «куда делась деталь» перестают быть доказательством. \
                Включается разделение ролей — ops/create-roles.sh и переменные \
                DB_USER, APP_DDL_*, APP_RUNTIME_ROLE""",
                writable, currentUser());
    }

    private boolean canUpdate(String schema, String journal) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user, ?, 'UPDATE')",
                    Boolean.class, schema + "." + journal));
        } catch (RuntimeException e) {
            // Таблицы нет или схема не мигрирована — не наш случай.
            return false;
        }
    }

    private String currentUser() {
        try {
            return jdbc.queryForObject("SELECT current_user", String.class);
        } catch (RuntimeException e) {
            return "неизвестна";
        }
    }
}
