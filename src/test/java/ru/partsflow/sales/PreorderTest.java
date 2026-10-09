package ru.partsflow.sales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Товар по ожидаемой поставке: завести, отложить под клиента, принять
 * (задача 0170).
 *
 * <p><b>Главное, что здесь доказывается, — что складской резерв не тронут, а
 * предзаказ ложится рядом с ним, не удваиваясь.</b> После прихода сумма
 * {@code qty_reserved} по складу равна сумме предзаказов, а не вдвое, сделка
 * остаётся той же, и обе сверки ({@code v_reservation_discrepancy},
 * {@code v_stock_discrepancy}) молчат — до предзаказа, при живом предзаказе
 * и после прихода.
 *
 * <p>Через HTTP, а не вызовом сервиса: ответы уходят record'ами, и класс в
 * стиле record Jackson не сериализует — тест на сервис этого не увидит. Гонка
 * двух предзаказов — единственное место, где сервис зовётся напрямую: двум
 * потокам нужны свои транзакции.
 *
 * <p>Своя схема ({@code t_000170}): числа сверок абсолютные, а общая схема
 * с соседом сдвинула бы их.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PreorderTest extends PostgresTestBase {

    private static final String TENANT = "t_000170";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalesService sales;

    private Long near;
    private Long far;
    private Long customer;
    private Long donor;
    private int counter;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 170");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (170, ?, 'Разборка', 'predzakaz')""", TENANT);
        Long brand = jdbc.queryForObject(
                "SELECT id FROM catalog.brand WHERE slug = 'toyota'", Long.class);

        inTenant(() -> {
            member("vladelec", "Владелец", "OWNER");
            member("menedzher", "Менеджер", "MANAGER");
            member("prodavets", "Продавец", "SELLER");
            member("kladovshchik", "Кладовщик", "STOREKEEPER");
            if (near == null) {
                Long branch = jdbc.queryForObject(
                        "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
                near = jdbc.queryForObject(
                        "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                        Long.class, branch);
                far = jdbc.queryForObject(
                        "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Дальний') RETURNING id",
                        Long.class, branch);
                customer = jdbc.queryForObject(
                        "INSERT INTO customer (name) VALUES ('Автосервис') RETURNING id", Long.class);
                donor = jdbc.queryForObject("""
                        INSERT INTO donor (brand_id, note) VALUES (?, 'Контейнерная Камри')
                        RETURNING id""", Long.class, brand);
            }
            return null;
        });
    }

    // ---------- кто заводит ----------

    @Test
    @DisplayName("Ожидаемый товар заводит только владелец: менеджер, кладовщик и продавец получают 403")
    void onlyOwnerRegistersExpectedGoods() throws Exception {
        long supply = supply();
        String body = expectedBody("Фара левая", 3, "9000");

        for (String denied : List.of("menedzher", "kladovshchik", "prodavets")) {
            mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                            .with(csrf()).session(login(denied))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/intake/supplies/" + supply + "/expected-parts")
                            .session(login(denied)))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/intake/supplies/" + supply + "/expected-on")
                            .with(csrf()).session(login(denied))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedOn\":\"" + LocalDate.now().plusDays(10) + "\"}"))
                    .andExpect(status().isForbidden());
        }
        // Закрытый адрес при видимой кнопке — отказ в ответ на нажатие, а
        // закрытая кнопка при открытом адресе — не защита. Здесь доказана
        // вторая сторона; первая — в экранном тесте.
        assertThat(count("SELECT count(*) FROM part WHERE supply_id = " + supply))
                .as("отказанный запрос всё же завёл позицию")
                .isZero();

        mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                        .with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM part WHERE supply_id = " + supply
                + " AND expected_origin AND status = 'DRAFT'")).isEqualTo(1);
    }

    @Test
    @DisplayName("Без цены, вида детали и количества позицию не завести; машина необязательна")
    void minimumIsEnforcedInWords() throws Exception {
        long supply = supply();
        MockHttpSession owner = login("vladelec");
        for (String bad : List.of(
                expectedBody("Фара левая", 3, "0"),
                expectedBody("Фара левая", 0, "9000"),
                "{\"quantity\":1,\"price\":100}")) {
            mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                            .with(csrf()).session(owner)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }

        // Ответ владельца 9 октября 2026: машина при заведении не обязательна —
        // контрактные агрегаты возят партиями без машин. Контроль в том же
        // тесте: названная несуществующая машина по-прежнему отбивается словами.
        mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                        .with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawName\":\"Двигатель\",\"quantity\":1,\"price\":100}"))
                .andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM part WHERE supply_id = " + supply
                + " AND donor_id IS NULL AND expected_origin")).isEqualTo(1);
        mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                        .with(csrf()).session(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawName\":\"Двигатель\",\"donorId\":99999999,"
                                + "\"quantity\":1,\"price\":100}"))
                .andExpect(status().is4xxClientError());
    }

    // ---------- предзаказ ----------

    @Test
    @DisplayName("Предзаказ — отдельное состояние: склад ничего не откладывает, сверки молчат")
    void preorderIsASeparateStateAndReservesNothing() throws Exception {
        assertReconciled();
        long supply = supply();
        long part = expectedPart(supply, "Фара левая", 3, "9000");

        JsonNode deal = createDeal(part, 2, near, daysAhead(30)).andReturn();
        assertThat(deal.path("preorder").asBoolean()).isTrue();
        assertThat(deal.path("items").get(0).path("status").asText()).isEqualTo("PREORDER");

        assertThat(count("SELECT count(*) FROM part_stock WHERE part_id = " + part))
                .as("под предзаказ склад завёл строку раскладки — это уже не предзаказ")
                .isZero();
        assertReconciled();

        // Обычная продажа обычной детали не изменилась: резерв по свободному
        // остатку ставит прежняя инструкция, и сверка по-прежнему пуста.
        long ordinary = ordinaryPart("Бампер обычный", 2);
        JsonNode sold = createDeal(ordinary, 1, near, null).andReturn();
        assertThat(sold.path("items").get(0).path("status").asText()).isEqualTo("RESERVED");
        assertThat(sold.path("preorder").asBoolean()).isFalse();
        assertReconciled();
    }

    @Test
    @DisplayName("Срок предзаказа называет продавец: пустой срок — отказ, настройка компании его не двигает")
    void sellerNamesTheTermAndCompanySettingDoesNotMoveIt() throws Exception {
        long supply = supply();
        long part = expectedPart(supply, "Дверь передняя", 5, "15000");

        // Без срока: три дня на месячный контейнер сделали бы предзаказ
        // просроченным в день заведения.
        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dealBody(part, 1, near, null)))
                .andExpect(status().isBadRequest());

        Instant named = daysAhead(40);
        JsonNode deal = createDeal(part, 1, near, named).andReturn();
        long id = deal.path("id").asLong();
        assertThat(Instant.parse(deal.path("reservedUntil").asText())).isEqualTo(named);

        inTenant(() -> jdbc.update("UPDATE company_setting SET reservation_days = 1"));
        try {
            assertThat(Instant.parse(dealById(id).path("reservedUntil").asText()))
                    .as("смена настройки компании сдвинула срок предзаказа")
                    .isEqualTo(named);
            inTenant(() -> jdbc.update("UPDATE company_setting SET reservation_days = 300"));
            assertThat(Instant.parse(dealById(id).path("reservedUntil").asText()))
                    .isEqualTo(named);
        } finally {
            inTenant(() -> jdbc.update("UPDATE company_setting SET reservation_days = 3"));
        }

        // Обычная продажа без срока по-прежнему берёт настройку.
        long ordinary = ordinaryPart("Стекло обычное", 1);
        mvc.perform(post("/api/deals").with(csrf()).session(login("prodavets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dealBody(ordinary, 1, near, null)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Больше, чем в поставке, не отложить: второму покупателю отказ словами")
    void cannotPromiseMoreThanTheSupplyHolds() throws Exception {
        long part = expectedPart(supply(), "Капот", 3, "20000");

        createDeal(part, 2, near, daysAhead(20)).andReturn();
        MvcResult refused = tryDeal(part, 2, near, daysAhead(20));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        String message = new String(refused.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(message)
                .contains("уже отложили под других покупателей")
                .doesNotContain("свободного остатка на складе");
        // Одна оставшаяся — ещё можно.
        createDeal(part, 1, near, daysAhead(20)).andReturn();
        assertThat(count("SELECT count(*) FROM deal_item WHERE part_id = " + part
                + " AND status = 'PREORDER'")).isEqualTo(2);
    }

    @Test
    @DisplayName("Два одновременных предзаказа на последнюю единицу: побеждает один")
    void twoSimultaneousPreordersOnTheLastUnit() throws Exception {
        long part = expectedPart(supply(), "Крыло переднее", 1, "7000");

        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondBlocked = new CountDownLatch(1);
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        Thread first = new Thread(() -> {
            TenantContext.set(TENANT);
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    sales.createReserved(customer, null, daysAhead(20), null,
                            List.of(new SalesService.ItemRequest(part, BigDecimal.ONE, null, near)),
                            List.of());
                    firstHolds.countDown();
                    // Держим строку до тех пор, пока второй не встанет на
                    // блокировке: иначе тест выродится в последовательные
                    // вызовы и перестанет проверять то, ради чего написан.
                    // Смотрит за этим основной поток: снимок pg_stat_activity
                    // кэшируется до конца транзакции, и из этой он не увидел бы
                    // чужого ожидания никогда.
                    try {
                        secondBlocked.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable e) {
                firstFailure.set(e);
            } finally {
                TenantContext.clear();
            }
        });
        Thread second = new Thread(() -> {
            TenantContext.set(TENANT);
            try {
                firstHolds.await(30, TimeUnit.SECONDS);
                transactionTemplate.executeWithoutResult(status ->
                        sales.createReserved(customer, null, daysAhead(20), null,
                                List.of(new SalesService.ItemRequest(
                                        part, BigDecimal.ONE, null, near)),
                                List.of()));
            } catch (Throwable e) {
                secondFailure.set(e);
            } finally {
                TenantContext.clear();
            }
        });
        first.start();
        second.start();
        assertThat(firstHolds.await(30, TimeUnit.SECONDS)).isTrue();
        awaitBlockedOnPart();
        secondBlocked.countDown();
        first.join(60_000);
        second.join(60_000);

        assertThat(first.isAlive() || second.isAlive()).as("поток завис").isFalse();
        assertThat(firstFailure.get())
                .as("первый предзаказ отбит: %s; второй: %s", firstFailure.get(), secondFailure.get())
                .isNull();
        assertThat(secondFailure.get())
                .as("оба покупателя отложили одну и ту же единицу — деталь обещана дважды")
                .isNotNull()
                .hasMessageContaining("уже отложили под других покупателей");
        assertThat(count("SELECT COALESCE(sum(quantity), 0) FROM deal_item WHERE part_id = "
                + part + " AND status = 'PREORDER'")).isEqualTo(1);
    }

    @Test
    @DisplayName("Выдать сделку с предзаказом нельзя: деталь ещё не пришла")
    void preorderCannotBeIssued() throws Exception {
        long part = expectedPart(supply(), "Фонарь", 2, "4000");
        long deal = createDeal(part, 1, near, daysAhead(20)).andReturn().path("id").asLong();

        MvcResult refused = mvc.perform(post("/api/deals/" + deal + "/issue")
                        .with(csrf()).session(login("prodavets")))
                .andExpect(status().isConflict()).andReturn();
        assertThat(text(refused)).contains("предзаказ");
        assertThat(count("SELECT count(*) FROM stock_movement WHERE part_id = " + part)).isZero();
    }

    // ---------- срок ----------

    @Test
    @DisplayName("«Истек срок» не горит у предзаказа ни на доске, ни в списке просроченных")
    void preorderNeverExpiresBeforeArrival() throws Exception {
        long part = expectedPart(supply(), "Зеркало", 3, "3000");
        long preorder = createDeal(part, 1, near, daysAhead(20)).andReturn().path("id").asLong();
        long ordinaryPart = ordinaryPart("Зеркало обычное", 1);
        long ordinary = createDeal(ordinaryPart, 1, near, null).andReturn().path("id").asLong();

        inTenant(() -> jdbc.update(
                "UPDATE deal SET reserved_until = now() - interval '2 days' WHERE id IN (?, ?)",
                preorder, ordinary));

        // Контроль: обычная просроченная — в обоих местах. Без него тест
        // прошёл бы и тогда, когда просроченных нет ни там, ни там.
        List<Long> byEndpoint = expiredByEndpoint();
        assertThat(byEndpoint).contains(ordinary).doesNotContain(preorder);

        JsonNode board = board();
        List<Long> onBoard = new ArrayList<>();
        for (JsonNode column : board.path("columns")) {
            if ("EXPIRED".equals(column.path("key").asText())) {
                column.path("cards").forEach(card -> onBoard.add(card.path("id").asLong()));
            }
        }
        assertThat(onBoard)
                .as("доска и список просроченных разошлись — починили одно из двух мест")
                .contains(ordinary).doesNotContain(preorder);

        // У предзаказа на доске и в списке есть признак — экран по нему
        // говорит «ожидается» вместо «срок истёк».
        JsonNode card = cardOf(board, preorder);
        assertThat(card.path("preorder").asBoolean()).isTrue();
        JsonNode row = registryRow(preorder);
        assertThat(row.path("preorder").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Ожидаемая дата сдвинулась: срок едет вместе с ней в обе стороны, пометка видна, история пишет")
    void shiftOfExpectedDateMovesTermAndLeavesMark() throws Exception {
        long supply = supply();
        LocalDate ten = LocalDate.now().plusDays(10);
        setExpectedOn(supply, ten);
        long part = expectedPart(supply, "Решётка", 2, "2500");
        Instant term = daysAhead(25);
        long deal = createDeal(part, 1, near, term).andReturn().path("id").asLong();
        assertThat(dealById(deal).path("expectedOn").asText()).isEqualTo(ten.toString());
        assertThat(dealById(deal).path("shiftFrom").isNull()).isTrue();

        // Вперёд на десять суток.
        setExpectedOn(supply, ten.plusDays(10));
        JsonNode after = dealById(deal);
        assertThat(Instant.parse(after.path("reservedUntil").asText()))
                .as("срок не поехал за датой")
                .isEqualTo(term.plus(Duration.ofDays(10)));
        assertThat(after.path("shiftFrom").asText()).isEqualTo(ten.toString());
        assertThat(after.path("shiftTo").asText()).isEqualTo(ten.plusDays(10).toString());
        // Пометка видна на всех поверхностях, где продавец видит срок: в самой
        // сделке, в реестре списком и на доске, а список сделок клиента
        // отдаёт ту же сделку.
        assertThat(registryRow(deal).path("shiftTo").asText())
                .isEqualTo(ten.plusDays(10).toString());
        assertThat(cardOf(board(), deal).path("shiftTo").asText())
                .isEqualTo(ten.plusDays(10).toString());
        assertThat(customerListDeal(deal).path("shiftFrom").asText()).isEqualTo(ten.toString());
        assertThat(historyOf(deal))
                .anyMatch(m -> m.contains("Ожидаемая дата поставки сдвинулась")
                        && m.contains("было " + day(ten))
                        && m.contains("стало " + day(ten.plusDays(10))));

        // Назад на пятнадцать суток от новой: второй сдвиг не затирает то, что
        // было названо клиенту первым.
        setExpectedOn(supply, ten.minusDays(5));
        JsonNode back = dealById(deal);
        assertThat(Instant.parse(back.path("reservedUntil").asText()))
                .isEqualTo(term.plus(Duration.ofDays(10)).minus(Duration.ofDays(15)));
        assertThat(back.path("shiftFrom").asText()).isEqualTo(ten.toString());
        assertThat(back.path("shiftTo").asText()).isEqualTo(ten.minusDays(5).toString());

        // Продавец сказал клиенту — пометка гаснет, срок остаётся.
        mvc.perform(post("/api/deals/" + deal + "/shift-seen").with(csrf())
                        .session(login("prodavets")))
                .andExpect(status().isOk());
        JsonNode seen = dealById(deal);
        assertThat(seen.path("shiftFrom").isNull()).isTrue();
        assertThat(seen.path("shiftTo").isNull()).isTrue();
        assertThat(Instant.parse(seen.path("reservedUntil").asText()))
                .isEqualTo(Instant.parse(back.path("reservedUntil").asText()));
    }

    @Test
    @DisplayName("Дата, вернувшаяся на названную клиенту, гасит пометку сама")
    void shiftBackToTheNamedDateClearsTheMark() throws Exception {
        long supply = supply();
        LocalDate ten = LocalDate.now().plusDays(10);
        setExpectedOn(supply, ten);
        long part = expectedPart(supply, "Усилитель", 1, "2500");
        long deal = createDeal(part, 1, near, daysAhead(25)).andReturn().path("id").asLong();

        setExpectedOn(supply, ten.plusDays(3));
        assertThat(dealById(deal).path("shiftTo").isNull()).isFalse();
        setExpectedOn(supply, ten);
        assertThat(dealById(deal).path("shiftTo").isNull())
                .as("дата вернулась на названную, а пометка осталась").isTrue();
    }

    // ---------- приход ----------

    @Test
    @DisplayName("Приход той же позицией: предзаказ становится обычным резервом, не удваиваясь")
    void arrivalTurnsPreorderIntoOrdinaryReserveWithoutDoubling() throws Exception {
        long supply = supply();
        long part = expectedPart(supply, "Фара правая", 5, "9500");
        long first = createDeal(part, 2, near, daysAhead(30)).andReturn().path("id").asLong();
        long second = createDeal(part, 1, near, daysAhead(30)).andReturn().path("id").asLong();
        long numberBefore = dealById(first).path("number").asLong();
        assertReconciled();

        // Позиция до прихода — на витрине продавца ожидаемой строкой.
        JsonNode row = stockRow(part);
        assertThat(row.path("expected").asBoolean()).isTrue();
        assertThat(row.path("qtyAvailable").decimalValue()).isEqualByComparingTo("2");

        String requestId = UUID.randomUUID().toString();
        MvcResult done = receipt(near, supply, part, 5, requestId);
        assertThat(done.getResponse().getStatus()).isEqualTo(201);

        // Та же карточка, а не вторая.
        assertThat(count("SELECT count(*) FROM part WHERE supply_id = " + supply)).isEqualTo(1);
        assertThat(text("SELECT status FROM part WHERE id = " + part)).isEqualTo("IN_STOCK");
        assertThat(stock("qty", part, near)).isEqualByComparingTo("5");
        assertThat(stock("qty_reserved", part, near))
                .as("резерв удвоился: предзаказы посчитаны и как предзаказ, и как резерв")
                .isEqualByComparingTo("3");

        for (long id : List.of(first, second)) {
            JsonNode d = dealById(id);
            assertThat(d.path("items").get(0).path("status").asText()).isEqualTo("RESERVED");
            assertThat(d.path("items").get(0).path("warehouseId").asLong()).isEqualTo(near);
            assertThat(d.path("preorder").asBoolean()).isFalse();
        }
        assertThat(dealById(first).path("number").asLong())
                .as("номер сделки поменялся — покупатель потерял обещание")
                .isEqualTo(numberBefore);
        // Приём на обещанный склад истории не засоряет: менять было нечего.
        assertThat(historyOf(first)).noneMatch(m -> m.contains("пришла на склад"));
        assertReconciled();

        // Повтор офлайн-очереди возвращает прежний результат и не резервирует
        // второй раз.
        assertThat(receipt(near, supply, part, 5, requestId).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(stock("qty_reserved", part, near)).isEqualByComparingTo("3");
        assertThat(stock("qty", part, near)).isEqualByComparingTo("5");

        // Теперь это обычная деталь: выдаётся, и склад сходится.
        mvc.perform(post("/api/deals/" + first + "/issue").with(csrf())
                        .session(login("prodavets")))
                .andExpect(status().isOk());
        assertThat(stock("qty", part, near)).isEqualByComparingTo("3");
        assertThat(stock("qty_reserved", part, near)).isEqualByComparingTo("1");
        assertReconciled();
    }

    @Test
    @DisplayName("Приход на другой склад: склад сделки становится фактическим, и подмена видна в истории")
    void arrivalOnAnotherWarehouseIsWrittenInHistory() throws Exception {
        long supply = supply();
        long part = expectedPart(supply, "Рамка", 2, "1800");
        long deal = createDeal(part, 2, near, daysAhead(30)).andReturn().path("id").asLong();

        assertThat(receipt(far, supply, part, 2, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(201);

        JsonNode d = dealById(deal);
        assertThat(d.path("items").get(0).path("warehouseId").asLong())
                .as("склад строки остался обещанным, а деталь лежит на другом")
                .isEqualTo(far);
        assertThat(stock("qty_reserved", part, far)).isEqualByComparingTo("2");
        assertThat(count("SELECT count(*) FROM part_stock WHERE part_id = " + part
                + " AND warehouse_id = " + near)).isZero();
        assertThat(historyOf(deal))
                .anyMatch(m -> m.contains("Дальний") && m.contains("Ткацкая"));
        assertReconciled();
    }

    @Test
    @DisplayName("Приход частями на два склада: целиком нигде — отказ с числами, движение не записано")
    void splitArrivalIsRefusedWithNumbersAndWritesNothing() throws Exception {
        long supply = supply();
        long part = expectedPart(supply, "Дверь задняя", 4, "11000");
        long deal = createDeal(part, 4, near, daysAhead(30)).andReturn().path("id").asLong();

        // Первая часть: пришло не всё, предзаказ ждёт остальное и не ломается.
        assertThat(receipt(near, supply, part, 2, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(dealById(deal).path("items").get(0).path("status").asText())
                .isEqualTo("PREORDER");
        assertThat(stock("qty_reserved", part, near)).isEqualByComparingTo("0");
        assertReconciled();

        int movements = count("SELECT count(*) FROM stock_movement WHERE part_id = " + part);
        int documents = count("SELECT count(*) FROM stock_document");

        // Вторая — на другой склад: в сумме хватает, целиком нигде нет.
        MvcResult refused = receipt(far, supply, part, 2, UUID.randomUUID().toString());
        assertThat(refused.getResponse().getStatus())
                .as("размазанный по двум складам резерв прошёл молча").isEqualTo(409);
        String message = text(refused);
        assertThat(message).contains("Приход не записан").contains("«Ткацкая» — 2")
                .contains("«Дальний» — 2").contains("примите приход на тот склад");
        assertThat(count("SELECT count(*) FROM stock_movement WHERE part_id = " + part))
                .as("отказанный приход всё же записал движение").isEqualTo(movements);
        assertThat(count("SELECT count(*) FROM stock_document")).isEqualTo(documents);
        assertThat(count("SELECT count(*) FROM part_stock WHERE part_id = " + part
                + " AND warehouse_id = " + far)).isZero();
        assertThat(dealById(deal).path("items").get(0).path("status").asText())
                .isEqualTo("PREORDER");
        assertReconciled();

        // Тот же остаток на склад, где уже лежит основная часть, — проходит, и
        // всё обещанное ложится на него целиком.
        assertThat(receipt(near, supply, part, 2, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(stock("qty_reserved", part, near)).isEqualByComparingTo("4");
        assertThat(dealById(deal).path("items").get(0).path("status").asText())
                .isEqualTo("RESERVED");
        assertReconciled();
    }

    // ---------- снятие ----------

    @Test
    @DisplayName("Контейнер не пришёл: сделку снимают с причиной в истории, и место под предзаказ освобождается")
    void cancellationLeavesReasonInHistory() throws Exception {
        long part = expectedPart(supply(), "Балка", 1, "6000");
        long deal = createDeal(part, 1, near, daysAhead(20)).andReturn().path("id").asLong();

        MvcResult refused = tryDeal(part, 1, near, daysAhead(20));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);

        mvc.perform(post("/api/deals/" + deal + "/cancel").with(csrf())
                        .session(login("prodavets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"контейнер не пришёл\"}"))
                .andExpect(status().isOk());

        JsonNode d = dealById(deal);
        assertThat(d.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(d.path("items").get(0).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(historyOf(deal)).anyMatch(m -> m.contains("контейнер не пришёл"));
        assertThat(count("SELECT count(*) FROM deal_item WHERE part_id = " + part
                + " AND status = 'PREORDER'")).isZero();
        // Отмена не пытается снять резерв, которого не было.
        assertReconciled();
        createDeal(part, 1, near, daysAhead(20)).andReturn();
    }

    // ---------- что видит продавец ----------

    @Test
    @DisplayName("Продавец находит ожидаемую позицию поиском, с датой; после прихода она обычная")
    void sellerFindsExpectedPartAndItBecomesOrdinaryAfterArrival() throws Exception {
        long supply = supply();
        LocalDate expected = LocalDate.now().plusDays(12);
        setExpectedOn(supply, expected);
        long part = expectedPart(supply, "Радиатор", 1, "8000");

        JsonNode row = stockRow(part);
        assertThat(row.path("expected").asBoolean()).isTrue();
        assertThat(row.path("expectedOn").asText()).isEqualTo(expected.toString());
        assertThat(row.path("qtyAvailable").decimalValue()).isEqualByComparingTo("1");
        assertThat(row.path("warehouseId").asLong()).isPositive();

        createDeal(part, 1, near, daysAhead(30)).andReturn();
        // Всё обещано — строка не прячется: продавец ответит «ожидается, но
        // всё под заказ», а не «нет такого».
        assertThat(stockRow(part).path("qtyAvailable").decimalValue()).isEqualByComparingTo("0");

        assertThat(receipt(near, supply, part, 1, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(201);
        JsonNode ordinary = stockRow(part);
        assertThat(ordinary.path("expected").asBoolean()).isFalse();
        assertThat(ordinary.path("qty").decimalValue()).isEqualByComparingTo("1");
        assertThat(ordinary.path("qtyAvailable").decimalValue()).isEqualByComparingTo("0");
    }

    // ---------- приёмка не заводит вторую карточку ----------

    @Test
    @DisplayName("Принять можно только заведённую по этой поставке; чужая — отказ словами")
    void onlyTheExpectedPartOfThisSupplyCanBeAccepted() throws Exception {
        long supply = supply();
        long other = supply();
        long part = expectedPart(supply, "Панель", 1, "900");
        long ordinary = ordinaryPart("Панель обычная", 1);

        assertThat(receipt(near, other, part, 1, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(receipt(near, supply, ordinary, 1, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM stock_movement WHERE part_id = " + part)).isZero();
    }

    // ---------------------------------------------------------------

    // ---------- доделка по PR 370: ответ владельца 9 октября 2026 ----------

    @Test
    @DisplayName("Смешанная сделка (предзаказ + обычная позиция) просрочена по сроку обычной: «Истек срок» её видит")
    void mixedDealExpiresByTheOrdinaryPosition() throws Exception {
        long expected = expectedPart(supply(), "Крыло переднее", 2, "4000");
        long ordinaryPart = ordinaryPart("Крыло обычное", 1);
        long onlyPreorderPart = expectedPart(supply(), "Капот", 1, "6000");

        long mixed = json(mvc.perform(post("/api/deals").with(csrf()).session(login("prodavets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":%d,"reservedUntil":"%s",
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d},
                                          {"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(customer, daysAhead(30), expected, near,
                                        ordinaryPart, near)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        long pure = createDeal(onlyPreorderPart, 1, near, daysAhead(30))
                .andReturn().path("id").asLong();

        inTenant(() -> jdbc.update(
                "UPDATE deal SET reserved_until = now() - interval '2 days' WHERE id IN (?, ?)",
                mixed, pure));

        // Контроль в той же проверке: чисто предзаказная просрочкой по-прежнему
        // не считается — иначе тест прошёл бы и там, где условие снято вовсе.
        assertThat(expiredByEndpoint())
                .as("просроченный резерв обычной позиции в смешанной сделке не видит никто")
                .contains(mixed).doesNotContain(pure);

        JsonNode board = board();
        List<Long> onBoard = new ArrayList<>();
        for (JsonNode column : board.path("columns")) {
            if ("EXPIRED".equals(column.path("key").asText())) {
                column.path("cards").forEach(card -> onBoard.add(card.path("id").asLong()));
            }
        }
        assertThat(onBoard)
                .as("доска и список просроченных разошлись на смешанной сделке")
                .contains(mixed).doesNotContain(pure);

        // Экран отличает смешанную от чисто предзаказной признаком, а не
        // «есть предзаказ»: иначе «срок истёк» к смешанной не показали бы.
        assertThat(cardOf(board, mixed).path("preorder").asBoolean()).isTrue();
        assertThat(cardOf(board, mixed).path("preorderOnly").asBoolean()).isFalse();
        assertThat(registryRow(mixed).path("preorderOnly").asBoolean()).isFalse();
        assertThat(dealById(mixed).path("preorderOnly").asBoolean()).isFalse();
        assertThat(registryRow(pure).path("preorderOnly").asBoolean()).isTrue();
        assertThat(dealById(pure).path("preorderOnly").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Частичный приход не открывает пришедший остаток обычной продаже мимо очереди предзаказов")
    void partialArrivalKeepsTheRemainderForPreorders() throws Exception {
        long supply = supply();
        long part = expectedPart(supply, "Дверь правая", 3, "12000");
        long waiting = createDeal(part, 2, near, daysAhead(30)).andReturn().path("id").asLong();

        // Приехала одна штука из трёх: обещанному (две) её не хватает, предзаказ ждёт.
        assertThat(receipt(near, supply, part, 1, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(stock("qty", part, near)).isEqualByComparingTo("1");
        assertThat(dealById(waiting).path("items").get(0).path("status").asText())
                .isEqualTo("PREORDER");

        // Продавец за прилавком видит не «свободно 1», а то, что ему отдадут.
        assertThat(stockRow(part).path("qtyAvailable").decimalValue())
                .as("продавец видит свободным то, что обещано предзаказу")
                .isEqualByComparingTo("0");

        // И сервер ему этот остаток не отдаёт — словами, без поломки.
        MvcResult refused = tryDeal(part, 1, near, null);
        assertThat(refused.getResponse().getStatus())
                .as("пришедшее, но обещанное предзаказу, ушло в обычную продажу: %s",
                        text(refused))
                .isEqualTo(409);
        assertThat(text(refused)).contains("Пришла только часть поставки");
        assertThat(stock("qty_reserved", part, near)).isEqualByComparingTo("0");

        // Заказ с площадки не отклоняется (деньги уже у площадки), но и
        // обещанное не отбирает: записывается необеспеченным.
        MvcResult order = mvc.perform(post("/api/deals/orders").with(csrf())
                        .session(login("prodavets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","orderNo":"PO-%d",
                                 "items":[{"partId":%d,"quantity":1,"warehouseId":%d}]}"""
                                .formatted(System.nanoTime(), part, near)))
                .andReturn();
        assertThat(order.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(order).path("deal").path("status").asText())
                .as("заказ площадки отложил пришедшее, обещанное предзаказу")
                .isEqualTo("DRAFT");
        assertThat(stock("qty_reserved", part, near)).isEqualByComparingTo("0");

        // Пришла остальная часть: очередь предзаказов получает своё, остаток
        // свободен, и обычная продажа снова проходит.
        assertThat(receipt(near, supply, part, 2, UUID.randomUUID().toString())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(dealById(waiting).path("items").get(0).path("status").asText())
                .isEqualTo("RESERVED");
        assertThat(stock("qty_reserved", part, near)).isEqualByComparingTo("2");
        assertThat(tryDeal(part, 1, near, null).getResponse().getStatus()).isEqualTo(201);
        assertReconciled();
    }

    private long supply() throws Exception {
        MvcResult created = mvc.perform(post("/api/intake/supplies").with(csrf())
                        .session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"CONTAINER\",\"number\":\"П-" + (++counter) + "-"
                                + System.nanoTime() + "\",\"supplierName\":\"Onteco\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(created).path("id").asLong();
    }

    private String expectedBody(String name, int quantity, String price) {
        return """
                {"rawName":"%s","donorId":%d,"quantity":%d,"price":%s}"""
                .formatted(name, donor, quantity, price);
    }

    private long expectedPart(long supply, String name, int quantity, String price)
            throws Exception {
        MvcResult created = mvc.perform(post("/api/intake/supplies/" + supply + "/expected-parts")
                        .with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expectedBody(name, quantity, price)))
                .andExpect(status().isCreated()).andReturn();
        long best = 0;
        for (JsonNode row : json(created)) {
            best = Math.max(best, row.path("id").asLong());
        }
        return best;
    }

    private void setExpectedOn(long supply, LocalDate date) throws Exception {
        mvc.perform(put("/api/intake/supplies/" + supply + "/expected-on")
                        .with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedOn\":\"" + date + "\"}"))
                .andExpect(status().isOk());
    }

    /** Обычная деталь с остатком на ближнем складе. */
    private long ordinaryPart(String title, int quantity) {
        return inTenant(() -> {
            Long id = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, ?, 5000)
                    RETURNING id""", Long.class, title);
            ledger().record(ru.partsflow.inventory.StockMovement.intake(
                    id, BigDecimal.valueOf(quantity), near, null));
            return id;
        });
    }

    @Autowired
    private ru.partsflow.inventory.StockLedger stockLedger;

    private ru.partsflow.inventory.StockLedger ledger() {
        return stockLedger;
    }

    private String dealBody(long part, int quantity, long warehouse, Instant until) {
        return """
                {"customerId":%d,%s
                 "items":[{"partId":%d,"quantity":%d,"warehouseId":%d}]}"""
                .formatted(customer,
                        until == null ? "" : "\"reservedUntil\":\"" + until + "\",",
                        part, quantity, warehouse);
    }

    private MvcResult tryDeal(long part, int quantity, long warehouse, Instant until)
            throws Exception {
        return mvc.perform(post("/api/deals").with(csrf()).session(login("prodavets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dealBody(part, quantity, warehouse, until)))
                .andReturn();
    }

    /** Заводит сделку и требует успеха; ответ читается через {@code andReturn}. */
    private Created createDeal(long part, int quantity, long warehouse, Instant until)
            throws Exception {
        MvcResult result = tryDeal(part, quantity, warehouse, until);
        assertThat(result.getResponse().getStatus())
                .as("сделка не создалась: %s", text(result)).isEqualTo(201);
        return new Created(json(result));
    }

    private record Created(JsonNode body) {
        JsonNode andReturn() {
            return body;
        }
    }

    private MvcResult receipt(long warehouse, long supply, long part, int quantity,
                              String requestId) throws Exception {
        return mvc.perform(post("/api/intake/receipts").with(csrf())
                        .session(login("kladovshchik"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId":%d,"supplyId":%d,"requestId":"%s",
                                 "items":[{"rawName":"принять заведённую","quantity":%d,
                                           "price":1,"partId":%d}]}"""
                                .formatted(warehouse, supply, requestId, quantity, part)))
                .andReturn();
    }

    private JsonNode dealById(long id) throws Exception {
        return json(mvc.perform(get("/api/deals/" + id).session(login("prodavets")))
                .andExpect(status().isOk()).andReturn());
    }

    private List<String> historyOf(long id) throws Exception {
        JsonNode rows = json(mvc.perform(get("/api/deals/" + id + "/history")
                        .session(login("prodavets")))
                .andExpect(status().isOk()).andReturn());
        List<String> messages = new ArrayList<>();
        rows.forEach(row -> messages.add(row.path("message").asText()));
        return messages;
    }

    private JsonNode board() throws Exception {
        return json(mvc.perform(get("/api/deals/board").session(login("prodavets")))
                .andExpect(status().isOk()).andReturn());
    }

    private static JsonNode cardOf(JsonNode board, long dealId) {
        for (JsonNode column : board.path("columns")) {
            for (JsonNode card : column.path("cards")) {
                if (card.path("id").asLong() == dealId) {
                    return card;
                }
            }
        }
        throw new AssertionError("сделки " + dealId + " нет на доске: " + board);
    }

    private JsonNode registryRow(long dealId) throws Exception {
        JsonNode page = json(mvc.perform(get("/api/deals/registry?size=200")
                        .session(login("prodavets")))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode row : page.path("items")) {
            if (row.path("id").asLong() == dealId) {
                return row;
            }
        }
        throw new AssertionError("сделки " + dealId + " нет в реестре: " + page);
    }

    private JsonNode customerListDeal(long dealId) throws Exception {
        JsonNode rows = json(mvc.perform(get("/api/deals?customerId=" + customer)
                        .session(login("prodavets")))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == dealId) {
                return row;
            }
        }
        throw new AssertionError("сделки " + dealId + " нет у клиента: " + rows);
    }

    private List<Long> expiredByEndpoint() throws Exception {
        JsonNode rows = json(mvc.perform(get("/api/deals/expired-reservations")
                        .session(login("prodavets")))
                .andExpect(status().isOk()).andReturn());
        List<Long> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    /** Строка выдачи продавца по детали: поиск по её публичному коду. */
    private JsonNode stockRow(long partId) throws Exception {
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));
        JsonNode found = json(mvc.perform(get("/api/parts/stock?q=" + code)
                        .session(login("prodavets")))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode row : found.path("rows")) {
            if (row.path("partId").asLong() == partId) {
                return row;
            }
        }
        throw new AssertionError("продавец не нашёл позицию " + code + ": " + found);
    }

    /** Обе сверки пусты — до предзаказа, при нём и после прихода. */
    private void assertReconciled() {
        assertThat(count("SELECT count(*) FROM v_reservation_discrepancy"))
                .as("сверка резерва зашумела: предзаказ выглядит для неё разъездом")
                .isZero();
        assertThat(count("SELECT count(*) FROM v_stock_discrepancy"))
                .as("сверка остатка зашумела").isZero();
    }

    private void awaitBlockedOnPart() {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock'
                       AND query LIKE '%FOR UPDATE OF p%'""", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException(
                "Второй предзаказ не встал на блокировке строки part: строка не блокируется, "
                        + "и гонка в этом тесте не воспроизводится. Сессии: "
                        + jdbc.queryForList("""
                        SELECT state, wait_event_type, left(regexp_replace(query, '\\s+', ' ', 'g'), 60) AS q
                          FROM pg_stat_activity WHERE datname = current_database()
                           AND state <> 'idle'"""));
    }

    private int count(String sql) {
        return inTenant(() -> {
            Number n = jdbc.queryForObject(sql, Number.class);
            return n == null ? 0 : n.intValue();
        });
    }

    private String text(String sql) {
        return inTenant(() -> jdbc.queryForObject(sql, String.class));
    }

    private BigDecimal stock(String column, long part, long warehouse) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT " + column + " FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                BigDecimal.class, part, warehouse));
    }

    private static String text(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static Instant daysAhead(int days) {
        // До миллисекунд: Postgres хранит микросекунды, а на Linux Instant.now()
        // несёт наносекунды — сравнение «что записали и что прочли» иначе
        // краснеет только на раннере.
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .plus(Duration.ofDays(days));
    }

    private static String day(LocalDate date) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.of("ru"))
                .format(date);
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"predzakaz","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
