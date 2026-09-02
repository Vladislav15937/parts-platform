package ru.partsflow.intake;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Одновременный повтор приёмки отвечает результатом первого раза.
 *
 * <p>Проверка «нет ли уже такого» ловит обычный повтор — тот, что пришёл
 * после ответа. А два повтора в один момент проходят её оба: между чтением
 * и вставкой второй ещё ничего не видит. Дубля не появляется — его отбивает
 * уникальный индекс, — но наружу летел 409 «Операция нарушает целостность
 * данных», то есть ошибка на успешную приёмку.
 *
 * <p>Для офлайн-очереди это разница между «удалить запись, работа сделана»
 * и «увести в требует внимания»: 409 она читает как отказ по существу.
 * Приёмщик видит красное там, где деталь уже на складе, и заводит её
 * второй раз руками.
 *
 * <p>Поймано живым прогоном двумя одновременными запросами, а не тестом:
 * последовательный повтор работал правильно с самого начала.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class IntakeRetryHttpTest extends PostgresTestBase {

    private static final String TENANT = "t_000102";

    /** Сколько одновременных повторов пускаем: одного мало, гонка редкая. */
    private static final int PARALLEL = 6;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long warehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 102");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (102, ?, 'Повторная', 'retryco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login = 'keeper'");
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('keeper', 'Кладовщик', ?, 'STOREKEEPER')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Одновременный повтор не превращается в ошибку")
    void concurrentRetryReturnsTheFirstResult() throws Exception {
        MockHttpSession session = login();
        String requestId = "odnovremennyy-" + System.nanoTime();
        String body = """
                {"requestId":"%s","warehouseId":%d,
                 "items":[{"rawName":"Фара","price":100,"quantity":1}]}"""
                .formatted(requestId, warehouse);

        List<Integer> codes = new ArrayList<>();
        CyclicBarrier start = new CyclicBarrier(PARALLEL);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < PARALLEL; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    int status = mvc.perform(post("/api/intake/receipts").with(csrf())
                                    .session(session)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn().getResponse().getStatus();
                    synchronized (codes) {
                        codes.add(status);
                    }
                } catch (Exception e) {
                    synchronized (codes) {
                        codes.add(-1);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(codes)
                .as("одновременный повтор ответил ошибкой на успешную приёмку: %s", codes)
                .containsOnly(201);

        // И главное: партия одна. Ключ существует ровно затем, чтобы повтор
        // не превратился во вторую деталь на складе.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM stock_document WHERE client_request_id = ?",
                Integer.class, requestId)))
                .as("повтор завёл вторую партию")
                .isEqualTo(1);
        // Считаем детали этой партии, а не все «Фары» в схеме: соседний тест
        // заводит свою, и общий счёт ловил бы её, а не повтор.
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM stock_document_line l
                  JOIN stock_document d ON d.id = l.document_id
                 WHERE d.client_request_id = ?""", Integer.class, requestId)))
                .as("повтор завёл вторую деталь")
                .isEqualTo(1);
    }

    /**
     * У ссылки на снимок тот же ключ и была та же половинчатая защита:
     * телефон ждёт адрес, чтобы залить фотографию, и вместо него получал 409.
     */
    @Test
    @DisplayName("Одновременный запрос ссылки на снимок отдаёт одну и ту же")
    void concurrentPhotoUploadUrlIsReplayed() throws Exception {
        MockHttpSession session = login();
        Long partId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price, is_published)
                VALUES (1, 'Фара под снимок', 100, 50, true) RETURNING id""", Long.class));
        String requestId = "foto-" + System.nanoTime();
        String body = """
                {"requestId":"%s","contentType":"image/jpeg"}""".formatted(requestId);

        List<Integer> codes = parallel(PARALLEL, () -> mvc.perform(
                post("/api/parts/%d/photos/upload-url".formatted(partId))
                        .with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus());

        assertThat(codes)
                .as("телефон ждёт ссылку, а получил ошибку: %s", codes)
                .containsOnly(201);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo WHERE client_request_id = ?",
                Integer.class, requestId)))
                .as("повтор завёл второй снимок")
                .isEqualTo(1);
    }

    /** Пускает n одинаковых запросов разом и собирает коды ответов. */
    private List<Integer> parallel(int n, ThrowingSupplier request) throws Exception {
        List<Integer> codes = new ArrayList<>();
        CyclicBarrier start = new CyclicBarrier(n);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread thread = new Thread(() -> {
                int status;
                try {
                    start.await();
                    status = request.get();
                } catch (Exception e) {
                    status = -1;
                }
                synchronized (codes) {
                    codes.add(status);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        return codes;
    }

    private interface ThrowingSupplier {
        int get() throws Exception;
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"retryco","login":"keeper","password":"пароль-подлиннее"}"""))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
