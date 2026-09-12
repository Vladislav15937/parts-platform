package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Действует ли защита журналов — спрошено у самой базы, настоящей рабочей ролью.
 *
 * <p>Ответ нужен двум поверхностям: строке в логе при старте
 * ({@link JournalProtectionCheck}) и готовности приложения
 * ({@code /actuator/readiness}), которую спрашивает шаг выкладки. Ошибка
 * здесь тихая: приложение, поднятое владельцем схем, работает точно так же,
 * и разница видна лишь в тот день, когда журнал предъявляют как
 * доказательство.
 *
 * <p><b>Контекста Spring у теста нет намеренно.</b> Проверить зелёный ответ
 * можно только из-под роли, которой журналы не принадлежат, а подменить
 * пользователя базы в контексте нельзя: имя и пароль ставит
 * {@code @DynamicPropertySource} базового класса, и он старше свойств
 * {@code @SpringBootTest}. Отдельный контекст ради этого — ещё один Spring
 * и ещё один пул соединений; роль и права здесь настоящие, а этого
 * достаточно: наружу их отдаёт {@code AppReadinessTest} через HTTP.
 */
class JournalProtectionTest extends PostgresTestBase {

    /**
     * Номер маленький намеренно: проверка берёт первого работающего
     * арендатора реестра по номеру, а реестр общий у всего прогона.
     */
    private static final String TENANT = "t_000002";
    private static final long TENANT_ID = 2;

    /** Рабочая роль ячейки: та же затея, что у {@code partsflow_app} в бою. */
    private static final String ROLE = "readiness_runtime";
    private static final String PASSWORD = "readiness-runtime";

    /**
     * Роль, которой прав на схему не выдали вовсе: состояние ячейки между
     * заведением роли и накатом, который права и выдаёт. Журналы она тоже
     * переписать не может — как и продать деталь.
     */
    private static final String STRANGER = "readiness_stranger";
    private static final String STRANGER_PASSWORD = "readiness-stranger";

    @BeforeAll
    static void prepare() {
        provisionTenants(TENANT);

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'readiness_runtime') THEN
                        CREATE ROLE readiness_runtime LOGIN PASSWORD 'readiness-runtime';
                    ELSE
                        ALTER ROLE readiness_runtime LOGIN PASSWORD 'readiness-runtime';
                    END IF;
                END $$""");
        owner.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'readiness_stranger') THEN
                        CREATE ROLE readiness_stranger LOGIN PASSWORD 'readiness-stranger';
                    ELSE
                        ALTER ROLE readiness_stranger LOGIN PASSWORD 'readiness-stranger';
                    END IF;
                END $$""");

        // Ровно то, что выдаёт ops/create-roles.sh общей части ячейки.
        for (String role : List.of(ROLE, STRANGER)) {
            owner.execute("GRANT USAGE ON SCHEMA public TO " + role);
            owner.execute("GRANT SELECT ON public.tenant_registry TO " + role);
        }

        // А права на схему арендатора — настоящим SchemaGrants, а не копией
        // его SQL: проверять надо то, что выдаёт бой. Копия разошлась бы
        // с оригиналом, и тест начал бы стеречь несуществующее поведение.
        new SchemaGrants(ownerDataSource(), ROLE).apply(TENANT);

        owner.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status)
                VALUES (?, ?, 'Защита журналов', 'journal-protection', 'ACTIVE')
                ON CONFLICT (tenant_id) DO UPDATE SET status = 'ACTIVE'""",
                TENANT_ID, TENANT);
    }

    @Test
    @DisplayName("Рабочая роль не может переписать журналы — защита действует")
    void runtimeRoleCannotRewriteJournals() {
        JournalProtection.Status status = probe(runtimeDataSource());

        assertThat(status.role()).isEqualTo(ROLE);
        assertThat(status.schema())
                .as("проверено не на том арендаторе: ответ про чужую схему "
                        + "ничего не говорит о правах на этой")
                .isEqualTo(TENANT);
        assertThat(status.locked())
                .as("защита объявлена недействующей при разделённых ролях: "
                        + "готовность останется красной у правильно настроенной "
                        + "ячейки, и проверку выключат в первый же день. Доступны "
                        + "на UPDATE: %s", status.writable())
                .isTrue();
    }

    @Test
    @DisplayName("Заперты журналы, а не работа: обычные таблицы правятся как раньше")
    void ordinaryTablesStayWritable() {
        // Иначе зелёный ответ можно получить, просто не выдав роли ничего:
        // «журналы защищены» превратилось бы в «ячейка не работает».
        assertThat(canUpdate(runtimeDataSource(), TENANT + ".part"))
                .as("рабочая роль не может править карточку товара — это не защита "
                        + "журналов, а неработающая ячейка")
                .isTrue();
        assertThat(canUpdate(runtimeDataSource(), TENANT + ".stock_movement"))
                .as("движение склада доступно на UPDATE: REVOKE не сработал")
                .isFalse();
    }

    @Test
    @DisplayName("Роль без прав на схему — не «журналы защищены», а неработающая ячейка")
    void roleWithoutGrantsIsNotCountedAsProtected() {
        // Поймано живым прогоном: между заведением рабочей роли и накатом,
        // который выдаёт ей права, журналы «защищены» заодно со складом.
        // Зелёная готовность на такой ячейке — обещание, которого нет:
        // приложение не прочитает и не запишет ничего.
        JournalProtection.Status status = probe(strangerDataSource());

        assertThat(status.checked())
                .as("ответ не получен вовсе — а схема и роль на месте")
                .isTrue();
        assertThat(status.reachable())
                .as("роль без прав на схему сочтена работоспособной")
                .isFalse();
        assertThat(status.locked())
                .as("«журналы защищены» выдано роли, которой не выдано ничего: "
                        + "выкладка на такую ячейку пройдёт зелёной, а работать "
                        + "в ней нельзя")
                .isFalse();
    }

    @Test
    @DisplayName("Владелец схем правит журналы — и проверка говорит об этом")
    void ownerRoleIsReportedAsUnprotected() {
        JournalProtection.Status status = probe(ownerDataSource());

        assertThat(status.writable())
                .as("приложение, работающее владельцем схем, сочтено защищённым: "
                        + "это ровно тот тихий случай, когда журнал перестаёт быть "
                        + "доказательством, а выкладка красит себя зелёным")
                .containsExactlyElementsOf(JournalProtection.JOURNALS);
        assertThat(status.locked()).isFalse();
    }

    @Test
    @DisplayName("Запись реестра без схемы не выдаётся за защищённую")
    void registryRowWithoutSchemaIsSkipped() {
        // Сорвавшийся провижининг оставляет запись в реестре без схемы.
        // Прежняя проверка брала первого ACTIVE и на отсутствии таблицы
        // отвечала «не могу править» — то есть объявляла журналы
        // защищёнными, не проверив ничего. Номер меньше нашего: если
        // такую запись не пропускать, ответ придёт про неё.
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update("""
                INSERT INTO public.tenant_registry
                    (tenant_id, schema_name, company_name, code, status)
                VALUES (1, 't_000001', 'Сорвавшийся провижининг', 'no-schema', 'ACTIVE')
                ON CONFLICT (tenant_id) DO UPDATE SET status = 'ACTIVE'""");
        try {
            JournalProtection.Status status = probe(ownerDataSource());

            assertThat(status.schema())
                    .as("проверено на записи реестра, за которой нет схемы: "
                            + "ответ «журналы защищены» получен ни на чём")
                    .isEqualTo(TENANT);
            assertThat(status.locked())
                    .as("владелец схем объявлен неспособным править журналы — "
                            + "потому что смотрели туда, где таблиц нет")
                    .isFalse();
        } finally {
            owner.update("DELETE FROM public.tenant_registry WHERE tenant_id = 1");
        }
    }

    private static JournalProtection.Status probe(DataSource dataSource) {
        return new JournalProtection(new JdbcTemplate(dataSource)).status();
    }

    private static boolean canUpdate(DataSource dataSource, String table) {
        return Boolean.TRUE.equals(new JdbcTemplate(dataSource).queryForObject(
                "SELECT has_table_privilege(current_user, ?, 'UPDATE')", Boolean.class, table));
    }

    /** Владелец схем: тот, под кем поднят контейнер. */
    private static DataSource ownerDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Рабочая роль: та, под которой приложение работает в ячейке. */
    private static DataSource runtimeDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), ROLE, PASSWORD);
    }

    /** Роль без прав на схему арендатора. */
    private static DataSource strangerDataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), STRANGER, STRANGER_PASSWORD);
    }
}
