package ru.partsflow.platform.tenant;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Общая схема ячейки: накат и ответ на вопрос «накатана ли она».
 *
 * <p><b>Единственное место, где известен путь к changelog'у каталога</b> —
 * тот же довод, что у {@link TenantSchemaMigrator}: Liquibase считает
 * changeset по пути файла, и два места, знающие путь, разъедутся на первой
 * же правке.
 *
 * <p><b>Состояние спрашивается отдельно от наката, и ради этого класс
 * и появился.</b> Накат идёт при старте ({@link CatalogMigrations}), то есть
 * {@code ApplicationRunner}'ом — а он работает уже после того, как Tomcat
 * начал принимать запросы. Всё это время готовность приложения отвечала
 * «готов», потому что спрашивала что угодно, кроме общей схемы: при
 * перезапуске на базе, где реестр арендаторов уже есть, проверка версий
 * зелёная, журналы проверяются по реестру, метрики отдаются — и «да»
 * приходило на секунды раньше правды. Вывод задачи 0078 повторён здесь
 * буквально: <b>спрашивать надо то, что стартовые шаги делают, а не то, что
 * они закончились</b>.
 *
 * <p><b>Сверяются идентификаторы changeset'ов, а не путь.</b>
 * {@code listUnrunChangeSets} ответил бы «не накатано ничего» везде, где
 * changelog накатывали другим путём: тесты открывают его из каталога проекта
 * ({@code db.changelog-catalog.xml}), приложение — из classpath
 * ({@code db/changelog/db.changelog-catalog.xml}), а {@code db/verify.sh} —
 * третьим. Для Liquibase это три разных набора, и проверка, привязанная
 * к пути, краснела бы на исправной ячейке навсегда — то есть была бы
 * выключена первой. Идентификатор же у changeset'а один, и в каталоге
 * он уникален (тридцать пять changeset'ов, ни одного повтора).
 *
 * <p><b>Чего этот способ не видит:</b> переписанного {@code runOnChange} —
 * changeset в истории есть, а накатить его Liquibase собирается заново.
 * Сверять контрольные суммы вместо идентификаторов нельзя: их формат
 * меняется от версии к версии Liquibase, и такая проверка однажды покраснеет
 * на ячейке, где всё в порядке. Окно от переписанного {@code runOnChange}
 * измеряется тем же временем старта и состава схемы не меняет.
 *
 * <p><b>Владельцем схем, а не рабочей ролью.</b> DDL делает владелец,
 * и историю его наката читает он же: рабочей роли {@code SELECT}
 * на {@code public.databasechangelog} никто не выдавал
 * ({@code ops/create-roles.sh}), и проверка, спрашивающая её рабочей ролью,
 * отвечала бы «permission denied» на правильно настроенной ячейке.
 */
@Component
public class CatalogSchemaMigrator {

    private static final String CHANGELOG = "db/changelog/db.changelog-catalog.xml";

    /** Сколько непринятых changeset'ов называть по имени: дальше — числом. */
    private static final int NAMED = 3;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    /**
     * Идентификаторы changeset'ов артефакта: считаются один раз.
     *
     * <p>Changelog лежит внутри артефакта и за жизнь процесса не меняется,
     * а разбор его — это чтение всего набора, включая семнадцать тысяч строк
     * справочника машин. Готовность спрашивают раз в несколько секунд; тот же
     * размен, что у {@link TenantSchemaMigrator#expectedVersion()}.
     */
    private volatile List<String> expected;

    public CatalogSchemaMigrator(
            @SchemaOwnerDataSource.SchemaOwner DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Накатывает всё непринятое. Уже накатанное Liquibase пропускает сам. */
    public void migrate() {
        inLiquibase(liquibase -> {
            liquibase.update(new Contexts());
            return null;
        });
    }

    /**
     * Чего общей схеме не хватает.
     *
     * <p>Один запрос к истории на опрос: разбор changelog'а запомнен, а строк
     * в {@code public.databasechangelog} три десятка.
     */
    public Pending pending() {
        List<String> all = expected();
        Set<String> applied = new HashSet<>(jdbc.query(
                "SELECT author, id FROM public.databasechangelog",
                (rs, i) -> key(rs.getString("author"), rs.getString("id"))));

        List<String> missing = all.stream().filter(one -> !applied.contains(one)).toList();
        return new Pending(all.size(), missing);
    }

    private List<String> expected() {
        List<String> known = expected;
        if (known != null) {
            return known;
        }
        List<String> computed = inLiquibase(liquibase ->
                liquibase.getDatabaseChangeLog().getChangeSets().stream()
                        .map(changeSet -> key(changeSet.getAuthor(), changeSet.getId()))
                        .toList());
        expected = computed;
        return computed;
    }

    private static String key(String author, String id) {
        return author + ":" + id;
    }

    private <T> T inLiquibase(LiquibaseCall<T> call) {
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setLiquibaseSchemaName("public");

            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                return call.apply(liquibase);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Общая схема каталога: " + CHANGELOG, e);
        }
    }

    /** Отдельный интерфейс: {@link java.util.function.Function} не бросает проверяемые. */
    private interface LiquibaseCall<T> {
        T apply(Liquibase liquibase) throws Exception;
    }

    /**
     * @param total   сколько changeset'ов несёт сборка
     * @param missing каких из них в истории базы ещё нет. Пусто — схема принята
     *                целиком, и это единственное состояние, в котором приложение
     *                можно объявлять готовым
     */
    public record Pending(int total, List<String> missing) {

        public boolean ok() {
            return missing.isEmpty();
        }

        /**
         * Имена непринятых для человека: первые три, остальные числом.
         *
         * <p>Тридцать пять идентификаторов в строку — это строка, которую
         * не читают; тот же приём, что у {@link TenantMigrations#namesOf}.
         */
        public String names() {
            String first = String.join(", ", missing.subList(0, Math.min(NAMED, missing.size())));
            int rest = missing.size() - Math.min(NAMED, missing.size());
            return rest == 0 ? first : first + " и ещё " + rest;
        }
    }
}
