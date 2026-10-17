package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Разовая правка данных по реестру арендаторов: транзакция на арендатора,
 * по умолчанию — только чтение (задачи 0316 и 0317, общий каркас).
 *
 * <p><b>Зачем не changeset.</b> Правило, по которому правятся данные, живёт
 * в Java ({@code OemNumbers}, слова наименования), и вторая его копия в SQL
 * разошлась бы с первой на первой правке. А changeset катится при выкладке
 * сам, на всех арендаторов, без замера и без человека — для необратимой
 * правки это худшее из возможного. Здесь наоборот: запуск зовут руками,
 * сначала проверкой, и правка начинается только с явным {@code --apply}.
 *
 * <p><b>Проверка ничего не пишет по построению, а не по дисциплине.</b>
 * Без {@code apply} транзакция открывается {@code READ ONLY}: попытка записи
 * в ней — отказ базы (25006), то есть пропущенная в коде ветка «только при
 * --apply» роняет проверку громко, а не правит склад молча.
 *
 * <p><b>Схема — {@code SET LOCAL search_path}</b>, то есть до конца транзакции:
 * та же гарантия, что у провайдера соединений Hibernate, и сквозь PgBouncer
 * в transaction mode она не протекает. Имя схемы до склейки сверяется с той же
 * формой, что {@code CHECK} реестра. Своя {@link DataSourceTransactionManager}
 * поверх рабочего источника — чтобы одинаково работать и в маленьком контексте
 * разового запуска, и в контексте приложения (тесты), где менеджер транзакций —
 * JPA.
 *
 * <p><b>Рабочей ролью, а не владельцем схем:</b> правка данных — не DDL,
 * и рабочая роль журналы переписать не может (5 августа 2026). Запись в журнал
 * изменений ей разрешена — на {@code INSERT}.
 *
 * <p><b>Сорвавшийся арендатор не останавливает остальных</b> — как у
 * {@link SchemaSync}: отказ называется по схеме, остальные идут дальше.
 * Два одновременных запуска одной работы по одной схеме не пойдут оба:
 * транзакционный рекомендательный замок по имени работы и схемы, и второй
 * получает отказ словами, а не ждёт.
 */
@Component
public class TenantSweep {

    private static final Logger log = LoggerFactory.getLogger(TenantSweep.class);

    /** Та же форма, что у {@code CHECK} в {@code public.tenant_registry}. */
    private static final Pattern SCHEMA = Pattern.compile("^t_[0-9]{6,}$");

    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager transactions;

    public TenantSweep(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new DataSourceTransactionManager(dataSource);
        // Без этого «только чтение» — подсказка драйверу, а не запрет:
        // SET TRANSACTION READ ONLY делает его запретом базы.
        this.transactions.setEnforceReadOnly(true);
    }

    /** Арендатор из реестра. */
    public record Tenant(long id, String schema) {
    }

    /**
     * Итог по арендатору: либо результат работы, либо причина, по которой
     * её не сделали.
     */
    public record Outcome<R>(String schema, R result, String problem) {
    }

    /**
     * @param job        имя работы — им берётся замок
     * @param apply      {@code false} — транзакция только на чтение
     * @param onlySchema одна схема вместо всего реестра; {@code null} — все
     * @param work       работа над одной схемой; видит {@code JdbcTemplate}
     *                   уже внутри её транзакции и со схемой в {@code search_path}
     */
    public <R> List<Outcome<R>> run(String job, boolean apply, String onlySchema,
                                    BiFunction<JdbcTemplate, Tenant, R> work) {
        List<Outcome<R>> outcomes = new ArrayList<>();
        List<Tenant> tenants = tenants(onlySchema);
        if (onlySchema != null && tenants.isEmpty()) {
            outcomes.add(new Outcome<>(onlySchema, null,
                    "в реестре нет работающего арендатора с такой схемой"));
            return outcomes;
        }
        for (Tenant tenant : tenants) {
            outcomes.add(one(job, apply, tenant, work));
        }
        return outcomes;
    }

    private List<Tenant> tenants(String onlySchema) {
        String sql = """
                SELECT tenant_id, schema_name FROM public.tenant_registry
                 WHERE status IN ('ACTIVE', 'SUSPENDED')""";
        if (onlySchema != null) {
            return jdbc.query(sql + " AND schema_name = ? ORDER BY tenant_id",
                    (rs, i) -> new Tenant(rs.getLong(1), rs.getString(2)), onlySchema);
        }
        return jdbc.query(sql + " ORDER BY tenant_id",
                (rs, i) -> new Tenant(rs.getLong(1), rs.getString(2)));
    }

    private <R> Outcome<R> one(String job, boolean apply, Tenant tenant,
                               BiFunction<JdbcTemplate, Tenant, R> work) {
        String schema = tenant.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            return new Outcome<>(schema, null, "имя схемы не той формы, что ведёт реестр");
        }
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(!apply);
        try {
            return tx.execute(status -> {
                Boolean exists = jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)", Boolean.class, schema);
                if (!Boolean.TRUE.equals(exists)) {
                    return new Outcome<R>(schema, null, "схемы нет в базе, хотя реестр её называет");
                }
                Boolean mine = jdbc.queryForObject(
                        "SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))", Boolean.class, job, schema);
                if (!Boolean.TRUE.equals(mine)) {
                    return new Outcome<R>(schema, null, "по этой схеме уже идёт такой же запуск — повторите позже");
                }
                jdbc.execute("SET LOCAL search_path TO " + schema);
                jdbc.execute("SET LOCAL lock_timeout = '5s'");
                return new Outcome<>(schema, work.apply(jdbc, tenant), null);
            });
        } catch (RuntimeException e) {
            log.error("{}: {} не выполнен", schema, job, e);
            return new Outcome<>(schema, null, TenantMigrations.rootMessage(e));
        }
    }
}
