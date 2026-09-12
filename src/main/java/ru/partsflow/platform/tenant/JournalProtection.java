package ru.partsflow.platform.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Действует ли защита журналов: что фактически может рабочая роль.
 *
 * <p>Разделение ролей держится не на коде, а на настройке ячейки: забыли
 * переменные окружения — приложение поднимется владельцем схем и будет
 * работать как ни в чём не бывало, только журнал снова станет правиться
 * прямым SQL. Отличить одно от другого по поведению нельзя: склад работает
 * одинаково, а разница видна лишь в тот день, когда журнал предъявляют
 * как доказательство.
 *
 * <p>Поэтому спрашивается не конфигурация, а сама база: <b>может ли роль,
 * под которой я работаю, переписать движение склада</b>. Настройка может
 * быть заполнена, а права — не выданы (или наоборот), и значение имеет
 * только второе.
 *
 * <p><b>Отдельно от {@link JournalProtectionCheck} — потому что ответ нужен
 * дважды.</b> Проверка при старте говорит это человеку в лог, готовность
 * приложения ({@code /actuator/readiness}) — шагу выкладки. Одно вычисление
 * на две поверхности: посчитанное дважды разъезжается, и тогда лог
 * и healthcheck отвечают на один вопрос по-разному.
 *
 * <p><b>Смотрится один арендатор, а не все пятьсот.</b> Права выдаёт
 * {@link SchemaGrants} одинаково всем схемам, а готовность спрашивают раз
 * в несколько секунд: полторы тысячи запросов на опрос — это цена
 * диагностики, которую платит работа.
 *
 * <p>Транзакция не нужна: запрос идёт в {@code public}, к реестру,
 * а {@code has_table_privilege} квалифицирует схему в аргументе —
 * {@code search_path} тут ни при чём.
 */
@Component
public class JournalProtection {

    /** Что должно быть неизменяемым: те же три таблицы, что и в {@link SchemaGrants}. */
    public static final List<String> JOURNALS =
            List.of("stock_movement", "audit_log", "customer_account_entry");

    /**
     * Обычная таблица, по которой видно, что защита — это защита, а не общее
     * отсутствие прав.
     *
     * <p>Без неё «журналы защищены» получается и на ячейке, где рабочей роли
     * не выдали прав вовсе: переписать журнал она действительно не может —
     * как и продать деталь. Зелёный ответ на неработающей ячейке хуже
     * красного, поэтому спрашивается и то, что править роль обязана.
     * Проверено живьём: ровно это состояние и выходит между заведением роли
     * и накатом, который права выдаёт.
     */
    private static final String PROBE = "part";

    private final JdbcTemplate jdbc;

    public JournalProtection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Что рабочая роль может сделать с журналами первого работающего арендатора.
     *
     * <p><b>Схема выбирается та, в которой журналы есть.</b> Прежде проверка
     * брала первого {@code ACTIVE} в реестре и на отсутствии таблицы отвечала
     * «не могу править» — то есть запись реестра без схемы (сорвавшийся
     * провижининг, чужая отметка) объявляла журналы защищёнными, ничего
     * не проверив. Тихое «всё в порядке» здесь хуже отказа: его предъявляют
     * как доказательство.
     *
     * <p><b>Наличие таблицы спрашивается у {@code pg_class}, а не
     * {@code to_regclass}.</b> Разница в правах: {@code to_regclass} разбирает
     * имя и на схеме без {@code USAGE} отказывает — «permission denied for
     * schema t_1000001», то есть 42501, который Spring переводит в «bad SQL
     * grammar». Ровно это и вышло живьём на ячейке с разделёнными ролями,
     * где права на схемы ещё не выданы: вместо ответа про журналы готовность
     * показывала текст запроса. Системный каталог читается любой ролью
     * и ничего не разбирает.
     */
    public Status status() {
        String schema;
        try {
            schema = jdbc.query("""
                    SELECT r.schema_name FROM public.tenant_registry r
                     WHERE r.status = 'ACTIVE'
                       AND EXISTS (SELECT 1 FROM pg_class c
                                     JOIN pg_namespace n ON n.oid = c.relnamespace
                                    WHERE n.nspname = r.schema_name
                                      AND c.relname = 'stock_movement')
                     ORDER BY r.tenant_id LIMIT 1""",
                    rs -> rs.next() ? rs.getString(1) : null);
        } catch (RuntimeException e) {
            // Реестра ещё нет — ячейка поднимается впервые. Отличать это
            // от недоступной базы не нужно: про базу отвечает своя проверка.
            // Причина — из самого глубокого исключения: обёртка Spring несёт
            // в сообщении весь текст запроса, и «проверить не удалось»
            // читалось бы простынёй SQL вместо «реестра нет».
            return new Status(currentUser(), null, false, List.of(),
                    TenantMigrations.rootMessage(e));
        }

        if (schema == null) {
            return new Status(currentUser(), null, false, List.of(), null);
        }

        return new Status(currentUser(), schema, canUpdate(schema, PROBE),
                JOURNALS.stream().filter(journal -> canUpdate(schema, journal)).toList(), null);
    }

    /**
     * Может ли роль переписать названную таблицу.
     *
     * <p>Отказ здесь означает «не дотягивается вовсе» — нет таблицы либо нет
     * прав на саму схему ({@code has_table_privilege} разбирает имя и без
     * {@code USAGE} отказывает). В обоих случаях переписать журнал этой ролью
     * нельзя; отличает эти состояния от настоящей защиты проба по обычной
     * таблице, {@link #PROBE}.
     */
    private boolean canUpdate(String schema, String table) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT has_table_privilege(current_user, ?, 'UPDATE')",
                    Boolean.class, schema + "." + table));
        } catch (RuntimeException e) {
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

    /**
     * @param role      роль, под которой приложение работает; её и спрашивают
     * @param schema    на чьих журналах проверено; {@code null} — арендаторов
     *                  со схемой в ячейке ещё нет, и проверять нечего
     * @param reachable дотягивается ли роль до обычных таблиц схемы. Нет —
     *                  ячейка неработоспособна, и «журналы защищены» про неё
     *                  было бы правдой ни о чём
     * @param writable  какие журналы эта роль может переписать. Пусто —
     *                  защита действует
     * @param problem   почему проверить не удалось; {@code null} — удалось
     */
    public record Status(String role, String schema, boolean reachable,
                         List<String> writable, String problem) {

        /** Ответ получен: есть на чём проверять и проверка не сорвалась. */
        public boolean checked() {
            return problem == null && schema != null;
        }

        /** Журналы этой роли не принадлежат, а работать она может. */
        public boolean locked() {
            return checked() && reachable && writable.isEmpty();
        }
    }
}
