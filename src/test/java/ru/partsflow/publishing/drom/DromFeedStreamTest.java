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
