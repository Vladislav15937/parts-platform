package ru.partsflow.platform.tenant;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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

    /**
     * Объявление нижней границы отката — рядом с самим changelog'ом и внутри
     * артефакта: у сборки версии 120 и у сборки версии 138 она может быть
     * разной, и спрашивать надо у той, которая откатывает.
     */
    private static final String ROLLBACK_FLOOR = "db/changelog/rollback-floor.properties";

    private final DataSource dataSource;

    /** Версия артефакта: считается один раз, см. {@link #expectedVersion()}. */
    private volatile String expectedVersion;

    /** Нижняя граница отката: считается один раз, см. {@link #rollbackFloor()}. */
    private volatile String rollbackFloor;

    public TenantSchemaMigrator(
            @SchemaOwnerDataSource.SchemaOwner DataSource dataSource) {
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
     *
     * <p><b>И запоминается.</b> Changelog лежит внутри артефакта и в течение
     * жизни процесса не меняется, а разбор его — соединение к базе и чтение
     * всех файлов набора. Готовность приложения ({@code /actuator/readiness})
     * спрашивают раз в несколько секунд: без памяти каждый опрос заново
     * разбирал бы весь changelog.
     */
    public String expectedVersion() {
        String known = expectedVersion;
        if (known != null) {
            return known;
        }
        String computed = inLiquibase(null, liquibase -> {
            List<ChangeSet> all = liquibase.getDatabaseChangeLog().getChangeSets();
            return all.isEmpty()
                    ? null
                    : all.size() + "/" + all.get(all.size() - 1).getId();
        });
        expectedVersion = computed;
        return computed;
    }

    /**
     * Сколько changeset'ов несёт этот артефакт. Он же — номер версии,
     * к которой приводит {@code --schema-sync} без {@code --to}.
     */
    public int totalChangeSets() {
        return changeSetsIn(expectedVersion());
    }

    /**
     * Отметка версии для схемы, на которой накатаны первые {@code count}
     * changeset'ов набора.
     *
     * <p>Формат тот же, что у {@link #expectedVersion()}, и считается он тем же
     * выражением: отметка после отката вниз обязана читаться теми же глазами,
     * что и отметка после наката вверх. {@code versionAt(totalChangeSets())}
     * равно {@code expectedVersion()} — проверено {@code SchemaSyncTest}.
     */
    public String versionAt(int count) {
        if (count <= 0) {
            return "0/—";
        }
        return inLiquibase(null, liquibase -> {
            List<ChangeSet> all = liquibase.getDatabaseChangeLog().getChangeSets();
            int last = Math.min(count, all.size());
            return last + "/" + all.get(last - 1).getId();
        });
    }

    /**
     * Нижняя граница отката этого артефакта — «число косая идентификатор»,
     * тем же форматом, что и {@link #expectedVersion()}.
     *
     * <p><b>Что это.</b> Самая младшая версия, до которой откат ВОЗВРАЩАЕТ
     * схему к тому виду, какой она на этой версии имела. Ниже неё одиннадцать
     * выпущенных changeset'ов не отменяют того, что сделали (волна «логика
     * в Java» с пустым {@code --rollback}, переименование, два снятых
     * {@code NOT NULL}), и откат туда проходит МОЛЧА, с кодом ноль, оставляя
     * схему, на которой код той сборки работать не может. Задача 0102: довод,
     * почему граница, а не второй мост отката, — в
     * {@code db/changelog/rollback-floor.properties} и {@code db/CLAUDE.md}.
     *
     * <p><b>Пара сверяется с changelog'ом, а не принимается на слово.</b>
     * Число обязано указывать на changeset с этим идентификатором — иначе
     * объявление читалось бы двумя способами, и однажды это были бы разные
     * места. Разъехалось — исключение: инструмент, который откатывает
     * по неверной границе, хуже инструмента, который не запускается.
     */
    public String rollbackFloor() {
        String known = rollbackFloor;
        if (known != null) {
            return known;
        }
        String declared = checkedFloor(declaredFloor(), totalChangeSets(), this::versionAt);
        rollbackFloor = declared;
        return declared;
    }

    /**
     * Сверка объявления с набором, отделённая от базы намеренно.
     *
     * <p>Обе ветки отказа — «версии в наборе нет» и «пара разъехалась» —
     * это правила про текст, и проверять их тестом, которому нужна база
     * и Liquibase, значит не проверять вовсе: такой тест напишут один раз
     * и на положительный случай. Питоновская половина того же объявления
     * проверяется пятью подделками (`db/verify-rollback.py --selftest`),
     * и эта обязана быть не слабее.
     *
     * @param versionAt как {@link #versionAt(int)} — вынесено параметром,
     *                  чтобы сверку можно было спросить без соединения
     */
    static String checkedFloor(String declared, int total,
                               java.util.function.IntFunction<String> versionAt) {
        int number = changeSetsIn(declared);
        if (number < 1 || number > total) {
            throw new IllegalStateException("Граница отката объявлена как «"
                    + declared + "», а в наборе " + total + " changeset'ов: "
                    + ROLLBACK_FLOOR);
        }
        String at = versionAt.apply(number);
        if (!at.equals(declared)) {
            throw new IllegalStateException("Граница отката объявлена как «"
                    + declared + "», а версия " + number + " этого набора — «"
                    + at + "». Объявление разъехалось с changelog'ом: "
                    + ROLLBACK_FLOOR);
        }
        return declared;
    }

    private String declaredFloor() {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(ROLLBACK_FLOOR)) {
            if (in == null) {
                throw new IllegalStateException("В артефакте нет " + ROLLBACK_FLOOR
                        + " — граница отката не объявлена, а откат вниз на неё опирается");
            }
            // Явно UTF-8: Properties.load(InputStream) читает ISO-8859-1,
            // и русское объяснение внутри файла приехало бы крокозябрами.
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Не прочитался " + ROLLBACK_FLOOR, e);
        }
        String value = props.getProperty("tenant");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(ROLLBACK_FLOOR
                    + ": нет строки «tenant=…» — граница для схемы арендатора"
                    + " не объявлена");
        }
        return value.strip();
    }

    /**
     * Что схема знает о себе: сколько changeset'ов артефакта на ней уже есть,
     * сколько ещё нет и чего на ней лежит <b>сверх</b> артефакта.
     *
     * <p><b>Спрашивается у Liquibase, а не у отметки в реестре.</b> Отметка —
     * кэш, и она врёт, если в схему лазили руками; шаг выкладки, который
     * поверил бы ей, выложил бы код на схему, которой он не соответствует.
     *
     * <p><b>Третье поле — то, ради чего метод и появился.</b> Схема, побывавшая
     * под более новой сборкой, несёт changeset'ы, которых в этом артефакте нет
     * вовсе. Снять их он не может: тело {@code --rollback} лежит в файле
     * changeset'а, то есть в чужом jar, — а {@code rollbackCount} их
     * попросту не видит и снял бы вместо них последние <b>свои</b>, то есть
     * не те. Молчаливый накат в такой схеме — это пара «код одной версии,
     * база другой», ровно та, которой быть не должно. Поэтому лишние
     * возвращаются списком и называются по именам.
     */
    public SchemaState inspect(String schema) {
        return inLiquibase(schema, liquibase -> {
            Set<String> known = new HashSet<>();
            for (ChangeSet changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
                known.add(key(changeSet.getFilePath(), changeSet.getId(), changeSet.getAuthor()));
            }

            int applied = 0;
            List<String> extra = new ArrayList<>();
            for (RanChangeSet ran : liquibase.getDatabase().getRanChangeSetList()) {
                if (known.contains(key(ran.getChangeLog(), ran.getId(), ran.getAuthor()))) {
                    applied++;
                } else {
                    extra.add(ran.getChangeLog() + "::" + ran.getId());
                }
            }

            int pending = liquibase.listUnrunChangeSets(
                    new Contexts(), new LabelExpression()).size();
            return new SchemaState(applied, pending, List.copyOf(extra));
        });
    }

    /** Накатывает ровно {@code count} следующих непройденных changeset'ов. */
    public void migrateNext(String schema, int count) {
        inLiquibase(schema, liquibase -> {
            liquibase.update(count, new Contexts(), new LabelExpression());
            return null;
        });
    }

    /**
     * Снимает {@code count} последних накатанных changeset'ов.
     *
     * <p>Цену отката печатает не этот метод, а {@code db/rollback-cost.py}:
     * структурно верный откат теряет данные молча, и что именно теряется,
     * знает только пометка у самого changeset'а.
     *
     * <p>И глубину тоже решает не этот метод: ниже {@link #rollbackFloor()}
     * откат схему не возвращает, и отказывает в этом {@link SchemaSync} —
     * там, где известна целевая версия, а не число шагов.
     */
    public void rollbackLast(String schema, int count) {
        inLiquibase(schema, liquibase -> {
            liquibase.rollback(count, new Contexts(), new LabelExpression());
            return null;
        });
    }

    private static String key(String file, String id, String author) {
        return file + "::" + id + "::" + author;
    }

    /**
     * Сколько changeset'ов стоит за отметкой версии.
     *
     * <p>Формат отметки задаёт {@link #expectedVersion()} — «число косая
     * идентификатор», — и разбирается она здесь же: два места, знающие формат,
     * разъедутся на первой же его правке.
     *
     * <p>Число сравнимо, идентификатор — нет, и на этом стоит правило
     * «схема впереди — норма, схема позади — нет»: расширяющая миграция
     * только добавляет changeset'ы, поэтому больше значит новее.
     *
     * @return {@code -1}, если отметки нет или она не разбирается. Это «не
     *         знаю», а не «совпадает»: выдать неизвестное за годное значит
     *         выложить код на схему, которой он не соответствует
     */
    public static int changeSetsIn(String version) {
        if (version == null) {
            return -1;
        }
        int slash = version.indexOf('/');
        String count = slash < 0 ? version : version.substring(0, slash);
        try {
            return Integer.parseInt(count.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
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

    /**
     * Состояние схемы глазами самого Liquibase, см. {@link #inspect(String)}.
     *
     * @param applied сколько changeset'ов этого артефакта на схеме уже есть
     * @param pending сколько ещё нет. Ноль — накатывать нечего
     * @param extra   что лежит на схеме сверх артефакта: changeset'ы более
     *                новой сборки. Не пусто — этот артефакт схему не выправит,
     *                и говорить об этом надо словами, а не молча накатывать
     */
    public record SchemaState(int applied, int pending, List<String> extra) {

        public boolean ahead() {
            return !extra.isEmpty();
        }
    }
}
