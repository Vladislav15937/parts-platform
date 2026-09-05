package ru.partsflow.publishing.drom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.StockLedger;
import ru.partsflow.inventory.StockMovement;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прайс отдаётся настоящим сервлет-контейнером, а не заглушкой.
 *
 * <p><b>Зачем отдельный контекст с живым портом.</b> Отказ, случившийся
 * после {@code response.getOutputStream()}, в настоящем Tomcat уже нельзя
 * превратить в код ошибки: заголовки отправлены, и площадка получает
 * <b>200 и ноль байт</b>. Пустой прайс она понимает буквально — «этих
 * товаров больше нет» — и снимает объявления вместе с накопленными
 * просмотрами, за которые владелец платит.
 *
 * <p>MockMvc этого не видит вовсе: он собирает ответ в памяти и позволяет
 * сменить статус после записи, поэтому та же поломка выглядит в нём честным
 * четырёхсотым. Проверено откатом правки — тест на MockMvc остался зелёным
 * при полностью сломанном поведении, и ровно поэтому здесь поднят Tomcat.
 *
 * <p>Поймано живым прогоном: условие по колонке чужой линии товара,
 * оставшееся у выгрузки от прежней настройки, отдавало пустой файл
 * и не оставляло в логе ни строки.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class DromFeedStreamTest extends PostgresTestBase {

    private static final String TENANT = "t_000113";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String token;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        register(113, TENANT, "streamco");
        inTenant(() -> {
            jdbc.update("DELETE FROM marketplace_account");
            jdbc.update("DELETE FROM part_stock");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Поток') RETURNING id",
                    Long.class, branch);
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, 'Поток: фара', 3000, true) RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));

            token = "t".repeat(43);
            jdbc.update("""
                    INSERT INTO marketplace_account (marketplace, title, settings, feed_token)
                    VALUES ('DROM', 'Поток', '{"packetId":"1"}'::jsonb, ?)""", token);
            return null;
        });
    }

    @Test
    @DisplayName("Целый прайс отдаётся с телом, а не одними заголовками")
    void wholeFeedIsServed() {
        ResponseEntity<String> answer = get();

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).contains("Поток: фара");
    }

    @Test
    @DisplayName("Битый отбор отказывает до первой строки, а не отдаёт пустой прайс")
    void brokenFilterIsRefusedBeforeTheFirstLine() {
        // Условие по колонке, которой у этой линии товара нет. Через API
        // такое уже не сохранить, но у клиента оно может лежать с прежней
        // настройки выгрузки — а прайс собирается по тому, что в базе.
        inTenant(() -> jdbc.update(
                "UPDATE marketplace_account SET filter_columns = ?::jsonb WHERE feed_token = ?",
                "{\"season\": \"летняя\"}", token));

        ResponseEntity<String> answer = get();

        assertThat(answer.getStatusCode().value())
                .as("площадка получила успех: пустой файл она читает как «товаров нет» "
                        + "и снимает все объявления")
                .isNotEqualTo(200);
    }

    /**
     * Отметка о заборе — единственный след, который остаётся после того,
     * как площадка забрала прайс.
     *
     * <p>Без неё на вопрос «прайс вообще уехал?» отвечал разработчик через
     * {@code docker logs}: контроллер писал строку в лог и больше никуда.
     * То есть клиент ждал человека, чтобы узнать факт, который система знает.
     *
     * <p>Проверяется живым запросом через настоящий Tomcat, а не вызовом
     * метода: отметка ставится после того, как тело ответа дописано, и в этом
     * весь смысл — о полузабранном прайсе она отвечала бы «да».
     */
    @Test
    @DisplayName("После забора прайса у выгрузки стоит отметка со временем")
    void downloadIsMarked() {
        assertThat(mark()).as("отметка стоит до первого забора").isNull();

        assertThat(get().getStatusCode().value()).isEqualTo(200);

        assertThat(mark())
                .as("прайс уехал, а следа не осталось — на вопрос «забрали ли» "
                        + "снова отвечает разработчик по логам")
                .isNotNull();
    }

    @Test
    @DisplayName("Второй забор обновляет отметку, а не оставляет первую")
    void secondDownloadMovesTheMark() {
        get();
        java.time.Instant first = mark();

        get();

        assertThat(mark())
                .as("отметка застыла на первом заборе: по ней прайс выглядит "
                        + "заброшенным при исправной выгрузке")
                .isAfter(first);
    }

    /**
     * Отмечается та выгрузка, за которой пришли.
     *
     * <p>Прайс-листов у клиента пять, и отметка, поставленная всем сразу,
     * отвечает про чужую ссылку — то есть врёт ровно там, где её и смотрят:
     * «этот прайс не забирают уже неделю» превращается в «всё в порядке».
     */
    @Test
    @DisplayName("Отметка ставится только той выгрузке, чей прайс забрали")
    void onlyTheFetchedFeedIsMarked() {
        inTenant(() -> jdbc.update("""
                INSERT INTO marketplace_account (marketplace, title, settings, feed_token)
                VALUES ('DROM', 'Соседняя', '{"packetId":"2"}'::jsonb, ?)""",
                "s".repeat(43)));

        get();

        assertThat(mark()).as("забранный прайс остался без отметки").isNotNull();
        assertThat(markOf("Соседняя"))
                .as("забор одного прайса отметил и соседний")
                .isNull();
    }

    /** Отметка выгрузки из фикстуры; {@code null} — не забирали. */
    private java.time.Instant mark() {
        return markOf("Поток");
    }

    private java.time.Instant markOf(String title) {
        // OffsetDateTime, а не Instant: драйвер Postgres отдаёт timestamptz
        // именно им, и запрошенный Instant кончился бы отказом преобразования.
        java.time.OffsetDateTime at = inTenant(() -> jdbc.queryForObject(
                "SELECT last_feed_download_at FROM marketplace_account WHERE title = ?",
                java.time.OffsetDateTime.class, title));
        return at == null ? null : at.toInstant();
    }

    private ResponseEntity<String> get() {
        return http.getForEntity(
                "http://localhost:%d/feeds/drom/streamco/%s.xml".formatted(port, token),
                String.class);
    }

    /**
     * Арендатор ставится <b>до</b> открытия транзакции: {@code search_path}
     * выставляет провайдер соединений Hibernate в момент выдачи соединения,
     * и установленный внутри контекст до него уже не доедет.
     */
    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private void register(int id, String schema, String code) {
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name,
                                                    status, code)
                VALUES (?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (tenant_id) DO UPDATE SET code = excluded.code""",
                id, schema, "Поток " + code, code);
    }
}
