package ru.partsflow.publishing;

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
import ru.partsflow.publishing.drom.DromAccountReader;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Выгрузку переименовывают, выключают и удаляют.
 *
 * <p><b>Через настоящий контейнер, а не вызовом метода.</b> Половина
 * утверждений задачи — про то, что видит <b>площадка</b>, сходив по постоянной
 * ссылке: выключенная выгрузка обязана отвечать «нет такой», а не отдавать
 * пустой прайс. Разница между этими двумя ответами и есть вся задача: пустой
 * файл площадка читает буквально — «этих товаров больше нет» — и снимает
 * объявления вместе с накопленными просмотрами, за которые владелец платит.
 * MockMvc такого не различает: он собирает ответ в памяти и позволяет сменить
 * статус после записи.
 *
 * <p><b>Удаление проверяется с четырёх сторон сразу</b>, потому что решение
 * владельца продукта от 5 сентября 2026 — «помечать удалённой, а не удалять
 * запись» — распадается ровно на четыре обещания: по ссылке не отдаётся,
 * в списке её нет, отметки о заборе остались и доступны, имя файла с названием
 * освободились. Пометка, у которой выполнены не все четыре, это не удаление,
 * а «спрятали»: владелец увидит либо живой прайс у площадки, либо отказ,
 * называющий выгрузку, которой он не видит.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class FeedLifecycleTest extends PostgresTestBase {

    private static final String TENANT = "t_000117";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockLedger ledger;

    @Autowired
    private MarketplaceAccountService accounts;

    @Autowired
    private DromAccountReader reader;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long feedId;
    private String token;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        register(117, TENANT, "lifeco");
        inTenant(() -> {
            // Позиции не чистятся: на них ссылается журнал движений, и остаток
            // прежнего прогона в прайсе ничему здесь не мешает.
            jdbc.update("DELETE FROM marketplace_account");
            jdbc.update("DELETE FROM part_stock");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Полка') RETURNING id",
                    Long.class, branch);
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, 'Живая фара', 3000, true) RETURNING id""", Long.class);
            ledger.record(StockMovement.intake(partId, BigDecimal.ONE, warehouse, null));
            return null;
        });

        feedId = inTenant(() -> accounts.create("DROM", "Дром: основной", null, "PART").id());
        token = inTenant(() -> accounts.rotateFeedToken(feedId));
    }

    @Test
    @DisplayName("Переименование не трогает ссылку: прайс отдаётся по прежнему адресу")
    void renameKeepsTheLink() {
        String before = inTenant(() -> accounts.feedPath(feedId).orElseThrow());

        MarketplaceAccountService.Account renamed =
                inTenant(() -> accounts.rename(feedId, "  Дром: низкая цена  "));

        assertThat(renamed.title())
                .as("название не сохранилось или сохранилось с пробелами по краям")
                .isEqualTo("Дром: низкая цена");
        assertThat(inTenant(() -> accounts.feedPath(feedId).orElseThrow()))
                .as("переименование сменило адрес прайса — а его прописывает "
                        + "в кабинете площадки техспециалист руками, и правка "
                        + "названия стала бы остановкой выгрузки на несколько дней")
                .isEqualTo(before);
        assertThat(feed().getStatusCode().value())
                .as("прайс по прежней ссылке перестал отдаваться")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Название занято другой выгрузкой — отказ словами, а не отказом базы")
    void takenTitleIsRefusedInWords() {
        inTenant(() -> accounts.create("DROM", "Дром: колёса", null, "WHEEL"));

        assertThatThrownBy(() -> inTenant(() -> accounts.rename(feedId, "Дром: колёса")))
                .as("ответ «Операция нарушает целостность данных» не говорит "
                        + "ни что случилось, ни что делать")
                .hasMessageContaining("Дром: колёса")
                .hasMessageContaining("уже заведена");
    }

    /**
     * Выключенная выгрузка отвечает «нет такой», а не пустым прайсом.
     *
     * <p>Пустой прайс — это команда снять все объявления, и она уже отдельно
     * отбита. Здесь проверяется противоположное: площадка обязана понять,
     * что выгрузки нет, и попробовать позже, — тогда объявления доживают
     * до включения обратно.
     */
    @Test
    @DisplayName("Выключенная выгрузка не отдаёт прайс вовсе, а не отдаёт пустой")
    void pausedFeedIsNotServed() {
        assertThat(feed().getStatusCode().value()).isEqualTo(200);

        inTenant(() -> accounts.setStatus(feedId, "PAUSED"));

        ResponseEntity<String> answer = feed();
        assertThat(answer.getStatusCode().value())
                .as("площадка забрала прайс выключенной выгрузки: ссылка живёт, "
                        + "и товар уезжает туда, где клиент его видеть не хочет")
                .isEqualTo(404);
        assertThat(answer.getBody())
                .as("вместо отказа отдан пустой прайс — а это команда снять "
                        + "все объявления вместе с накопленными просмотрами")
                .doesNotContain("<offer");
    }

    @Test
    @DisplayName("Выключенная выгрузка не получает и дельт по API")
    void pausedFeedGetsNoDeltas() {
        inTenant(() -> accounts.setStatus(feedId, "PAUSED"));

        assertThat(reader.active(TENANT))
                .as("дельта уедет в прайс-лист, который владелец закрыл: "
                        + "объявление появится там, где его не заводили, "
                        + "и снять его нечем до полного забора")
                .isEmpty();
    }

    @Test
    @DisplayName("Включённая обратно выгрузка собирает прайс по той же ссылке")
    void resumedFeedServesAgain() {
        inTenant(() -> accounts.setStatus(feedId, "PAUSED"));
        assertThat(feed().getStatusCode().value()).isEqualTo(404);

        MarketplaceAccountService.Account back =
                inTenant(() -> accounts.setStatus(feedId, "ACTIVE"));

        assertThat(back.status()).isEqualTo("ACTIVE");
        ResponseEntity<String> answer = feed();
        assertThat(answer.getStatusCode().value())
                .as("включённая обратно выгрузка не ожила: закрыть прайс "
                        + "на сезон можно, открыть нельзя")
                .isEqualTo(200);
        assertThat(answer.getBody()).contains("Живая фара");
    }

    @Test
    @DisplayName("Выключенная видна владельцу в списке — иначе включать нечего")
    void pausedFeedStaysInTheList() {
        inTenant(() -> accounts.setStatus(feedId, "PAUSED"));

        assertThat(inTenant(() -> accounts.list()))
                .as("выключенная пропала из списка — владелец не найдёт, "
                        + "что включать обратно")
                .extracting(MarketplaceAccountService.Account::status)
                .containsExactly("PAUSED");
    }

    @Test
    @DisplayName("Удалённая выгрузка не отдаёт прайс и пропадает из списка")
    void deletedFeedIsGone() {
        inTenant(() -> {
            accounts.delete(feedId);
            return null;
        });

        assertThat(feed().getStatusCode().value())
                .as("ссылка удалённой выгрузки продолжает работать — "
                        + "то есть удаления не произошло")
                .isEqualTo(404);
        assertThat(inTenant(() -> accounts.list()))
                .as("удалённая осталась в списке: «удалена» превратилось "
                        + "в «помечена», и владелец считает прайс-листы глазами")
                .isEmpty();
    }

    /**
     * Ради этого удаление и сделано пометкой.
     *
     * <p>«Эта выгрузка вообще работала и когда её последний раз забирали» —
     * вопрос, который задают уже после того, как прайс-лист закрыли. Удалённая
     * строка на него отвечает, отсутствующая — нет.
     */
    @Test
    @DisplayName("У удалённой сохраняются отметки о заборе прайса")
    void deletedFeedKeepsItsHistory() {
        assertThat(feed().getStatusCode().value()).isEqualTo(200);
        java.time.Instant downloaded = awaitDownloadMark();
        assertThat(downloaded).as("прайс уехал, а отметки не осталось").isNotNull();

        inTenant(() -> {
            accounts.delete(feedId);
            return null;
        });

        List<MarketplaceAccountService.Account> gone = inTenant(() -> accounts.deleted());
        assertThat(gone).hasSize(1);
        assertThat(gone.get(0).lastDownloadAt())
                .as("история забора ушла вместе с выгрузкой — а её и спрашивают, "
                        + "когда объявления пропали")
                .isEqualTo(downloaded);
        assertThat(gone.get(0).deletedAt())
                .as("не видно, когда выгрузку удалили: «до какого дня прайс "
                        + "работал» — тот же вопрос про историю")
                .isNotNull();
    }

    /**
     * Освобождение имён — часть того же решения, а не удобство.
     *
     * <p>Занятые за невидимой строкой, они отвечают на попытку завести
     * выгрузку заново отказом, который называет запись, которой владелец
     * не видит. Имя файла названо в задаче прямо, название — та же болезнь
     * с другой стороны: и то и другое уникально ради человека.
     */
    @Test
    @DisplayName("Удалённая освобождает и имя файла прайса, и название")
    void deletedFeedFreesItsNames() {
        inTenant(() -> accounts.setFeedFileName(feedId, "drom-parts.xml"));
        inTenant(() -> {
            accounts.delete(feedId);
            return null;
        });

        Long second = inTenant(() -> accounts.create("DROM", "Дром: основной", null, "PART").id());
        MarketplaceAccountService.Account named =
                inTenant(() -> accounts.setFeedFileName(second, "drom-parts.xml"));

        assertThat(named.feedFileName())
                .as("имя файла осталось за удалённой выгрузкой: завести новую "
                        + "с тем же адресом нельзя, а причину владельцу "
                        + "не объяснить — той выгрузки он не видит")
                .isEqualTo("drom-parts.xml");
        assertThat(named.title()).isEqualTo("Дром: основной");
    }

    @Test
    @DisplayName("Удалённую нельзя ни переименовать, ни включить")
    void deletedFeedIsNotEditable() {
        inTenant(() -> {
            accounts.delete(feedId);
            return null;
        });

        assertThatThrownBy(() -> inTenant(() -> accounts.rename(feedId, "Обратно")))
                .hasMessageContaining("не найдена");
        assertThatThrownBy(() -> inTenant(() -> accounts.setStatus(feedId, "ACTIVE")))
                .as("удалённую можно включить обратно запросом — тогда занятое "
                        + "ею имя вернулось бы к живой выгрузке")
                .hasMessageContaining("не найдена");
    }

    /**
     * Отметка о заборе ставится <b>после</b> того, как поток ответа закрыт,
     * — то есть тело уже у клиента, пока сервер ещё пишет. Проверка сразу
     * после запроса читает базу раньше, чем сервер в неё написал, и падает
     * на исправном коде: ровно этим покраснела {@code main} 5 сентября 2026.
     * Не дождавшись, метод отдаёт последнее прочитанное, и утверждение падает
     * своими словами.
     */
    private java.time.Instant awaitDownloadMark() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        java.time.Instant at = downloadMark();
        while (at == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            at = downloadMark();
        }
        return at;
    }

    private java.time.Instant downloadMark() {
        // OffsetDateTime, а не Instant: драйвер Postgres отдаёт timestamptz
        // именно им, и запрошенный Instant кончился бы отказом преобразования.
        java.time.OffsetDateTime at = inTenant(() -> jdbc.queryForObject(
                "SELECT last_feed_download_at FROM marketplace_account WHERE id = ?",
                java.time.OffsetDateTime.class, feedId));
        return at == null ? null : at.toInstant();
    }

    private ResponseEntity<String> feed() {
        return http.getForEntity(
                "http://localhost:%d/feeds/drom/lifeco/%s.xml".formatted(port, token),
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
                id, schema, "Жизненный цикл " + code, code);
    }
}
