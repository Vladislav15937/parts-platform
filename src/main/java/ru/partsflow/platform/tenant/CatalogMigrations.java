package ru.partsflow.platform.tenant;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Накат общей схемы {@code catalog} при старте.
 *
 * <p>Схемы арендаторов мигрирует провижининг — каждая версионируется отдельно,
 * и трогать их на старте нельзя: пятьсот схем на подъёме приложения означают
 * минуты недоступности. А вот {@code catalog} и {@code public} общие на ячейку,
 * без них не поднимется ни один арендатор, и накатывать их руками — это ровно
 * тот забытый шаг, из-за которого схема нового клиента создаётся в пустоту.
 *
 * <p><b>Идёт до первого запроса, а не по расписанию.</b> Liquibase берёт
 * блокировку в {@code DATABASECHANGELOGLOCK}, поэтому несколько экземпляров
 * приложения, поднимающихся одновременно, не подерутся: первый мигрирует,
 * остальные ждут и видят, что делать нечего.
 *
 * <p>Выключается свойством — на случай, когда миграциями управляет внешний
 * оркестратор и приложению туда лезть не надо.
 */
@Component
@ConditionalOnProperty(name = "app.migrate-catalog-on-start", havingValue = "true",
        matchIfMissing = true)
public class CatalogMigrations implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogMigrations.class);

    private static final String CHANGELOG = "db/changelog/db.changelog-catalog.xml";

    private final DataSource dataSource;

    public CatalogMigrations(
            @SchemaOwnerDataSource.SchemaOwner DataSource dataSource) {
        // Владельцем, а не рантайм-ролью: DDL делает тот, кому принадлежат
        // схемы, иначе разделение ролей бессмысленно.
        this.dataSource = dataSource;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setLiquibaseSchemaName("public");

            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
            log.info("Общая схема каталога актуальна");
        } catch (Exception e) {
            // Падать намеренно: без каталога не заведётся ни один арендатор,
            // и приложение, поднявшееся без него, будет отвечать ошибками
            // на каждый вход — только не сразу и не понятно почему.
            throw new IllegalStateException("Не удалось накатить общую схему каталога", e);
        }
    }
}
