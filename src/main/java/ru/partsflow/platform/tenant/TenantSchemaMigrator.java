package ru.partsflow.platform.tenant;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.function.Function;

/**
 * Накат миграций на схему арендатора.
 *
 * <p><b>Единственное место, где известен путь к changelog'у.</b> Liquibase
 * считает changeset по пути файла, а не по содержимому: тот же changelog,
 * открытый как {@code changelog/db.changelog-tenant.xml} и как
 * {@code db/changelog/db.changelog-tenant.xml}, — это два разных набора,
 * и второй запускающий не видит истории первого и накатывает всё заново
 * с «relation already exists». Провижининг и оркестратор обязаны ходить
 * одним путём, поэтому путь спрятан здесь, а не продублирован в обоих.
 *
 * <p>{@code DATABASECHANGELOG} лежит внутри схемы клиента: это даёт
 * независимое версионирование и параллельные миграции разных арендаторов.
 */
@Component
public class TenantSchemaMigrator {

    private static final String TENANT_CHANGELOG = "db/changelog/db.changelog-tenant.xml";

    private final DataSource dataSource;

    public TenantSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Накатывает всё непройденное. Уже накатанное Liquibase пропускает сам. */
    public void migrate(String schema) {
        inLiquibase(schema, liquibase -> {
            liquibase.update(new Contexts());
            return null;
        });
    }

    /**
     * Сколько changeset'ов схеме ещё не хватает.
     *
     * <p>Спрашивается у самого Liquibase, а не сравнением строк версий:
     * ответ должен совпадать с тем, что он сделает при накате, иначе проверка
     * «все ли на нужной версии» будет отвечать не про миграции, а про себя.
     */
    public int pendingCount(String schema) {
        return inLiquibase(schema,
                liquibase -> liquibase.listUnrunChangeSets(
                        new Contexts(), new LabelExpression()).size());
    }

    /**
     * Версия схемы — число changeset'ов и идентификатор последнего.
     *
     * <p><b>Одного идентификатора мало.</b> Changelog подключает
     * {@code 009-views.sql} последним намеренно: вьюхи помечены
     * {@code runOnChange} и должны пересобираться после всех таблиц. Значит
     * новый changeset попадает не в конец, и версия «по последнему» не меняется
     * вовсе — отметка в реестре осталась бы прежней у схемы, которой не хватает
     * миграции. Число ловит любое добавление, идентификатор оставляет версию
     * читаемой человеком.
     *
     * <p>Одинаково для всех арендаторов, поэтому считается один раз на прогон.
     */
    public String expectedVersion() {
        return inLiquibase(null, liquibase -> {
            List<ChangeSet> all = liquibase.getDatabaseChangeLog().getChangeSets();
            return all.isEmpty()
                    ? null
                    : all.size() + "/" + all.get(all.size() - 1).getId();
        });
    }

    /**
     * @param schema схема арендатора; {@code null} — когда нужен только разбор
     *               changelog'а, без обращения к чьей-либо истории
     */
    private <T> T inLiquibase(String schema, LiquibaseCall<T> call) {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            if (schema != null) {
                database.setLiquibaseSchemaName(schema);
                database.setDefaultSchemaName(schema);
            }

            try (Liquibase liquibase = new Liquibase(TENANT_CHANGELOG,
                    new ClassLoaderResourceAccessor(), database)) {
                // Подстановка нужна даже для разбора: без неё Liquibase
                // спотыкается на ${tenant.schema} внутри SQL-файлов.
                liquibase.setChangeLogParameter("tenant.schema",
                        schema == null ? "t_000000" : schema);
                return call.apply(liquibase);
            }
        } catch (Exception e) {
            throw new IllegalStateException(schema == null
                    ? "Changelog арендатора не разобрался"
                    : "Миграции арендатора " + schema + " не накатились", e);
        }
    }

    /** Отдельный интерфейс: {@link Function} не умеет бросать проверяемые. */
    private interface LiquibaseCall<T> {
        T apply(Liquibase liquibase) throws Exception;
    }
}
