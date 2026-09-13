package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
 * <p><b>«До первого запроса» — не то же самое, что «до первого ответа».</b>
 * {@code ApplicationRunner} работает уже после того, как Tomcat принимает
 * подключения, то есть окно «порт отвечает, схема догоняется» настоящее.
 * Готовность приложения ({@code /actuator/readiness}) поэтому спрашивает
 * не этот класс и не факт его запуска, а состояние самой схемы —
 * {@link CatalogSchemaMigrator#pending()}.
 *
 * <p>Выключается свойством — на случай, когда миграциями управляет внешний
 * оркестратор и приложению туда лезть не надо. Проверка готовности при этом
 * остаётся: она спрашивает схему, а не того, кто её накатывает.
 */
@Component
@ConditionalOnProperty(name = "app.migrate-catalog-on-start", havingValue = "true",
        matchIfMissing = true)
public class CatalogMigrations implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogMigrations.class);

    private final CatalogSchemaMigrator migrator;
    private final SchemaGrants grants;

    public CatalogMigrations(CatalogSchemaMigrator migrator, SchemaGrants grants) {
        this.migrator = migrator;
        this.grants = grants;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        try {
            migrator.migrate();
            log.info("Общая схема каталога актуальна");
        } catch (RuntimeException e) {
            // Падать намеренно: без каталога не заведётся ни один арендатор,
            // и приложение, поднявшееся без него, будет отвечать ошибками
            // на каждый вход — только не сразу и не понятно почему.
            throw new IllegalStateException("Не удалось накатить общую схему каталога", e);
        }

        try {
            // Права на служебные таблицы ячейки — здесь же, по той же причине,
            // по которой их выдаёт накат схемы арендатора: новая таблица прав
            // не наследует, а ops/create-roles.sh на работающей ячейке после
            // выкладки никто не перезапускает. Цена пропуска — не «раздел
            // не работает», а «войти не может никто»: хранилище сессий лежит
            // в public, и без прав на него сессию некуда записать. Поэтому
            // падаем так же, как на каталоге, — и по тому же доводу.
            // В разработке разделения ролей нет, и метод не делает ничего.
            grants.applyCellTables();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Не удалось выдать права на служебные таблицы ячейки", e);
        }
    }
}
