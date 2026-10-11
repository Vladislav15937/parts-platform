package ru.partsflow.platform.tenant;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.change.CheckSum;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>Перекат {@code runOnChange} видна по контрольной сумме (задача 0090).</b>
 * Идентификатор у переписанного changeset'а тот же, и сверка по одним
 * идентификаторам отвечала «принято» там, где справочник и функции ещё старые.
 * Теперь у принятого {@code runOnChange}-changeset'а сумма из
 * {@code databasechangelog} сверяется с суммой текущего текста — но не
 * строкой в строку, а средствами Liquibase ({@link ChangeSet#isCheckSumValid}):
 * тот читает версию формата из самой записи, и смена версии Liquibase не
 * краснит исправную ячейку (прежний довод против сверки сумм). Если же и это
 * разойдётся, красный ответ самоисцеляется: накат при старте перекатывает
 * changeset и пишет новую сумму. Остальные changeset'ы сумм не сверяют:
 * их правка — отдельная беда, и отвечает за неё сам Liquibase.
 * Накат здесь не запускается: вопрос не лечит.
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
     * Идентификаторы и runOnChange-changeset'ы артефакта: разбор один раз.
     *
     * <p>Changelog лежит внутри артефакта и за жизнь процесса не меняется,
     * а разбор его — это чтение всего набора, включая семнадцать тысяч строк
     * справочника машин. Готовность спрашивают раз в несколько секунд; тот же
     * размен, что у {@link TenantSchemaMigrator#expectedVersion()}.
     */
    private volatile Parsed parsed;

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
        Map<String, String> applied = new HashMap<>();
        jdbc.query("SELECT author, id, md5sum FROM public.databasechangelog",
                rs -> {
                    applied.put(key(rs.getString("author"), rs.getString("id")),
                            rs.getString("md5sum"));
                });

        List<String> missing = all.stream().filter(one -> !applied.containsKey(one)).toList();
        List<String> stale = stale(applied);
        return new Pending(all.size(), missing, stale);
    }

    /** Принятые {@code runOnChange}-changeset'ы, чей текст с тех пор изменился. */
    private List<String> stale(Map<String, String> applied) {
        return parsed().runOnChange().stream()
                .filter(changeSet -> {
                    String stored = applied.get(key(changeSet.getAuthor(), changeSet.getId()));
                    // Нет записи — это «не принят», он уже в missing; нет суммы
                    // (старая запись) Liquibase сам дописывает при следующем накате.
                    return stored != null
                            && !changeSet.isCheckSumValid(CheckSum.parse(stored));
                })
                .map(changeSet -> key(changeSet.getAuthor(), changeSet.getId()))
                .toList();
    }

    private List<String> expected() {
        return parsed().ids();
    }

    /** Разбор changelog'а: один раз за процесс, по тому же доводу, что и {@link #parsed}. */
    private Parsed parsed() {
        Parsed known = parsed;
        if (known != null) {
            return known;
        }
        Parsed computed = inLiquibase(liquibase -> {
            List<ChangeSet> all = liquibase.getDatabaseChangeLog().getChangeSets();
            return new Parsed(
                    all.stream().map(changeSet -> key(changeSet.getAuthor(), changeSet.getId())).toList(),
                    all.stream().filter(ChangeSet::isRunOnChange).toList());
        });
        parsed = computed;
        return computed;
    }

    private record Parsed(List<String> ids, List<ChangeSet> runOnChange) {
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
     * @param missing каких из них в истории базы ещё нет
     * @param stale   каких {@code runOnChange}-changeset'ов текст изменился после
     *                наката: идентификатор в истории есть, справочник или
     *                функция в базе старые. Пусто у обоих списков — схема принята
     *                целиком, и это единственное состояние, в котором приложение
     *                можно объявлять готовым
     */
    public record Pending(int total, List<String> missing, List<String> stale) {

        public boolean ok() {
            return missing.isEmpty() && stale.isEmpty();
        }

        /** Сколько changeset'ов накату предстоит принять или перекатить. */
        public int count() {
            return missing.size() + stale.size();
        }

        /**
         * Имена для человека: первые три, остальные числом. Перекат помечен —
         * «не принят» и «ждёт переката» лечатся одним накатом, но читать их
         * по-разному.
         *
         * <p>Тридцать пять идентификаторов в строку — это строка, которую
         * не читают; тот же приём, что у {@link TenantMigrations#namesOf}.
         */
        public String names() {
            List<String> all = new ArrayList<>(missing);
            stale.forEach(one -> all.add(one + " (изменён после наката)"));
            int shown = Math.min(NAMED, all.size());
            String first = String.join(", ", all.subList(0, shown));
            int rest = all.size() - shown;
            return rest == 0 ? first : first + " и ещё " + rest;
        }
    }
}
