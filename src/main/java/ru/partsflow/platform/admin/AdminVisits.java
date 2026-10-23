package ru.partsflow.platform.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.partsflow.platform.security.TenantPrincipal;
import ru.partsflow.shared.NotFound;

import java.util.List;
import java.util.Optional;

/**
 * Вход администратора сервиса в кабинет разборки и выход обратно.
 *
 * <p><b>Администратор входит собой, а не под владельцем.</b> Решение владельца
 * продукта от 8 сентября 2026 (tasks/0042, пункт 1): «вход администратора это
 * вход ещё одного участника с ролью администратор». Подмена сессии — «войти
 * как владелец» — была бы проще и полностью невидима: журнал разборки
 * подписывал бы его правки именем владельца. Поэтому в кабинете у него свой
 * участник — строка {@code tenant_member} с ролью {@code ADMIN} и ссылкой
 * на учётную запись в общей схеме, — и всё, что пишет «кто сделал», пишет его.
 *
 * <p><b>Строка нужна не для красоты, а потому что на неё ссылаются.</b>
 * В схеме арендатора двадцать с лишним колонок «кто сделал» — у сделки,
 * движения склада, документа, пересчёта, затрат, — и все стоят внешним ключом
 * на {@code tenant_member}. Решение 29 сентября 2026 говорит прямо: записи
 * в схеме клиента «должны уметь назвать того, кого в этой схеме нет». Своя
 * строка участника — это и есть такое имя: учётных данных у неё нет (вход
 * только через общую схему), а журналы, отчёты по менеджерам и история
 * документа называют администратора без единой правки в них самих.
 *
 * <p>«Учётных данных нет» держит Java, а не схема: {@code CHECK} на строку
 * администратора в {@code tenant/076} отложены (почему — там же). Логин и пароль
 * ей пишет только {@code MemberService}, а он строку {@code ADMIN} не трогает
 * ({@code requireNotServiceAdmin}); вход кабинета отказывает строке с этой ролью,
 * даже если логин ей поставят прямым SQL ({@code MemberAuthenticationProvider}).
 *
 * <p><b>Схема подставляется в текст запроса</b> — тем же приёмом, что
 * в {@code MemberAuthenticationProvider}: вход происходит до того, как
 * арендатор выставлен, транзакции JPA тут нет. Имя схемы берётся из реестра
 * и проверяется регуляркой до склейки.
 */
@Service
public class AdminVisits {

    /** Атрибут сессии: кто из администраторов сидит в кабинете. Нужен выходу обратно. */
    public static final String ADMIN_ID = "partsflow.admin.id";

    private final JdbcTemplate jdbc;
    private final AdminAccess access;

    public AdminVisits(JdbcTemplate jdbc, AdminAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /**
     * Войти в кабинет разборки.
     *
     * <p>Без действующего разрешения — отказ словами, а не 403: администратор
     * должен понять, что делать дальше (попросить доступ), а не искать
     * опечатку в своих правах.
     *
     * @return принципал кабинета: схема разборки, свой участник, роль {@code ADMIN}
     */
    public TenantPrincipal enter(ServiceAdminPrincipal admin, String companyCode) {
        Tenant tenant = tenantByCodeOrThrow(companyCode);
        if (!access.isOpen(tenant.id())) {
            throw new IllegalStateException(
                    "Владелец «%s» не открывал доступа администратору — попросите его кнопкой «Запросить доступ» или в разговоре"
                            .formatted(tenant.companyName()));
        }
        long memberId = ensureMember(tenant.schema(), admin);
        return new TenantPrincipal(tenant.schema(), tenant.id(), memberId, admin.login(),
                admin.displayName(), TenantPrincipal.SERVICE_ADMIN_ROLE, true,
                // Права владельца ему даёт роль ADMIN (TenantPrincipal.actsAsOwner),
                // а не назначение: роли клиента у него нет и быть не должно.
                false, java.util.Set.of());
    }

    /**
     * Свой участник администратора в схеме разборки — заводится при первом
     * входе и дальше тот же.
     *
     * <p>Одной инструкцией: «нет ли уже» отдельным чтением пропустило бы два
     * одновременных входа и завело бы двух участников на одного человека,
     * а уникальность по {@code service_admin_id} отбила бы второго ошибкой.
     * Имя переписывается при каждом входе: в журнале администратор называется
     * так, как назван сейчас в системе администрирования.
     */
    private long ensureMember(String schema, ServiceAdminPrincipal admin) {
        Long id = jdbc.queryForObject("""
                INSERT INTO %s.tenant_member (display_name, role, service_admin_id, last_login_at)
                VALUES (?, 'ADMIN', ?, now())
                ON CONFLICT (service_admin_id) WHERE service_admin_id IS NOT NULL
                DO UPDATE SET display_name = EXCLUDED.display_name, is_active = true,
                              last_login_at = now()
                RETURNING id""".formatted(requireSchema(schema)),
                Long.class, admin.displayName(), admin.adminId());
        if (id == null) {
            throw new IllegalStateException("Участник администратора не заведён");
        }
        return id;
    }

    /** Учётная запись администратора — для выхода из кабинета обратно в систему администрирования. */
    public Optional<ServiceAdminPrincipal> admin(long adminId) {
        List<ServiceAdminPrincipal> found = jdbc.query("""
                SELECT id, login, display_name FROM public.service_admin
                 WHERE id = ? AND is_active""",
                (rs, i) -> new ServiceAdminPrincipal(rs.getLong("id"), rs.getString("login"),
                        rs.getString("display_name")),
                adminId);
        return found.stream().findFirst();
    }

    /** Код и название разборки, в которой сидит администратор. */
    public Optional<Tenant> tenant(long tenantId) {
        return jdbc.query("""
                SELECT tenant_id, schema_name, code, company_name FROM public.tenant_registry
                 WHERE tenant_id = ?""", AdminVisits::tenantOf, tenantId).stream().findFirst();
    }

    /** Действующая разборка по коду входа — так её зовут и в списке, и в адресе. */
    public Tenant tenantByCodeOrThrow(String code) {
        return tenantByCode(code).orElseThrow(() -> NotFound.COMPANY.error(code));
    }

    private Optional<Tenant> tenantByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT tenant_id, schema_name, code, company_name FROM public.tenant_registry
                 WHERE code = lower(btrim(?)) AND status = 'ACTIVE'""",
                AdminVisits::tenantOf, code).stream().findFirst();
    }

    private static Tenant tenantOf(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Tenant(rs.getLong("tenant_id"), rs.getString("schema_name"),
                rs.getString("code"), rs.getString("company_name"));
    }

    private static String requireSchema(String schema) {
        if (schema == null || !schema.matches("t_\\d{6,}")) {
            throw new IllegalArgumentException("Недопустимое имя схемы арендатора: " + schema);
        }
        return schema;
    }

    public record Tenant(long id, String schema, String code, String companyName) {
    }
}
