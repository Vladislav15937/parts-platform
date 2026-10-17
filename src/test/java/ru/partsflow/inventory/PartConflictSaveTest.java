package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Чужая правка того же поля не сохраняет только это поле; остальное из запроса
 * сохраняется — задача 0313.
 *
 * <p>Ответ владельца продукта 17.10.2026 дословно: «3 - не сохраняются только
 * конфликтные поля, а остальные сохраняются». До него (PR 449, задача 0307)
 * отказ по одному полю не сохранял ничего: 409, и заметку, набранную рядом
 * с ценой, человек вписывал заново после перезагрузки.
 *
 * <p>Через HTTP: ответ — запись с {@code notSaved}, и сериализует её Jackson,
 * а вызов сервиса напрямую этого не видит.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartConflictSaveTest extends PostgresTestBase {

    private static final String TENANT = "t_000954";

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final Map<String, String> SCREEN_LABELS = PartEditValues.screenLabels();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    private Long partId;
    private Long managerA;
    private PartEditValues values;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 954");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (954, ?, 'Разборка', 'conflict0313')""", TENANT);
        String suffix = String.valueOf(SEQ.incrementAndGet());

        Long[] cells = new Long[2];
        long[] number = new long[1];
        inTenant(() -> {
            member("vladelec0313", "Пётр Владельцев", "OWNER");
            managerA = member("anna0313", "Анна Первая", "MANAGER");
            member("boris0313", "Борис Вторых", "MANAGER");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Основной') RETURNING id",
                    Long.class, branch);
            cells[0] = jdbc.queryForObject("INSERT INTO storage_cell (warehouse_id, code)"
                    + " VALUES (?, ?) RETURNING id", Long.class, warehouse, "А-" + suffix);
            cells[1] = jdbc.queryForObject("INSERT INTO storage_cell (warehouse_id, code)"
                    + " VALUES (?, ?) RETURNING id", Long.class, warehouse, "Б-" + suffix);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, 'Фара', 100, true) RETURNING id""", Long.class);
            number[0] = jdbc.queryForObject("SELECT max(number) + 1000 FROM part", Long.class);
            return null;
        });
        values = new PartEditValues(json, suffix, number[0], cells[0], cells[1]);

        // Заполнено всё — иначе «не стёрлось» нечем отличить от «и не было».
        ObjectNode full = json.createObjectNode();
        values.initial().forEach(full::set);
        MvcResult filled = send(login("vladelec0313"), full);
        assertThat(filled.getResponse().getStatus()).as(text(filled)).isEqualTo(200);
        inTenant(() -> jdbc.update("DELETE FROM outbox"));
    }

    @Test
    @DisplayName("Две вкладки: А меняет цену; Б от старого снимка — цену и заметку: 200, заметка Б, цена А, notSaved «Цена»")
    void twoTabsSaveTheNoteAndKeepThePrice() throws Exception {
        MockHttpSession a = login("anna0313");
        MockHttpSession b = login("boris0313");
        JsonNode openedA = editable(a);
        JsonNode openedB = editable(b);

        ObjectNode byA = json.createObjectNode();
        byA.put("price", 5000);
        byA.putObject("seen").set("price", openedA.get("price"));
        assertThat(send(a, byA).getResponse().getStatus()).isEqualTo(200);

        ObjectNode byB = json.createObjectNode();
        byB.put("price", 5200);
        byB.put("note", "заметка Б");
        ObjectNode seen = byB.putObject("seen");
        seen.set("price", openedB.get("price"));
        seen.set("note", openedB.get("note"));
        MvcResult result = send(b, byB);

        assertThat(result.getResponse().getStatus())
                .as("частичный успех — не отказ: %s", text(result)).isEqualTo(200);
        JsonNode answer = json.readTree(text(result));
        assertThat(answer.path("notSaved").toString())
                .isEqualTo("[{\"key\":\"price\",\"label\":\"Цена\",\"cause\":\"Цена\"}]");
        assertThat(answer.path("price").decimalValue())
                .as("прежние поля ответа на месте — старая вкладка читает их как раньше")
                .isEqualByComparingTo("5000");
        assertThat(answer.path("form").path("note").asText())
                .as("форма в ответе — то, что легло").isEqualTo("заметка Б");
        assertThat(answer.path("form").path("price").decimalValue()).isEqualByComparingTo("5000");

        JsonNode card = editable(a);
        assertThat(card.get("price").decimalValue()).as("цена А не затёрта").isEqualByComparingTo("5000");
        assertThat(card.get("note").asText()).as("заметка Б сохранена").isEqualTo("заметка Б");

        // История — только сохранённое: цену правили владелец (заполнение) и А,
        // заметку — владелец и Б; записи о цене от Б нет.
        List<String> priceAuthors = authors("Цена");
        List<String> noteAuthors = authors("Заметка");
        assertThat(priceAuthors).as("авторы правок цены")
                .containsExactlyInAnyOrder("Пётр Владельцев", "Анна Первая");
        assertThat(noteAuthors).as("авторы правок заметки")
                .containsExactlyInAnyOrder("Пётр Владельцев", "Борис Вторых");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT price_changed_by FROM part WHERE id = ?", Long.class, partId)))
                .as("автор цены у позиции — А").isEqualTo(managerA);
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM outbox
                 WHERE event_type = 'part.price_changed.v1' AND aggregate_id = ?""",
                Long.class, partId)))
                .as("событие цены для площадок — одно, от А").isEqualTo(1L);
    }

    @Test
    @DisplayName("Каждое поле формы занято, заметка рядом свободна: поле прежнее, заметка сохранена, notSaved называет поле словами экрана")
    void eachTakenFieldAloneIsNotSaved() throws Exception {
        MockHttpSession owner = login("vladelec0313");
        Map<String, JsonNode> changed = values.changed();

        for (String key : PartEditValues.fieldKeys()) {
            assertThat(changed).as("у поля «%s» нет нового значения — допишите его"
                    + " в PartEditValues.changed()", key).containsKey(key);
            JsonNode before = editable(owner);
            String neighbour = key.equals("note") ? "marking" : "note";
            String written = "соседнее из того же запроса " + key;
            ObjectNode body = json.createObjectNode();
            body.set(key, changed.get(key));
            body.put(neighbour, written);
            ObjectNode seen = body.putObject("seen");
            seen.set(key, values.foreign(before.get(key)));
            seen.set(neighbour, before.get(neighbour));

            MvcResult result = send(owner, body);
            assertThat(result.getResponse().getStatus())
                    .as("«%s» занято, «%s» свободно: %s", key, neighbour, text(result))
                    .isEqualTo(200);
            JsonNode notSaved = json.readTree(text(result)).path("notSaved");
            assertThat(notSaved.size()).as("не сохранено по «%s»: %s", key, notSaved).isEqualTo(1);
            assertThat(notSaved.get(0).path("key").asText()).isEqualTo(key);
            String label = SCREEN_LABELS.get(key);
            if (label != null) {
                assertThat(notSaved.get(0).path("label").asText())
                        .as("подпись — словами строки экрана").isEqualTo(label);
            }

            JsonNode after = editable(owner);
            assertThat(after.get(neighbour).asText())
                    .as("занятое «%s» не дало сохранить соседнее «%s»", key, neighbour)
                    .isEqualTo(written);
            for (String other : PartEditValues.fieldKeys()) {
                if (!other.equals(neighbour)) {
                    assertThat(PartEditValues.same(before.get(other), after.get(other)))
                            .as("занятое «%s»: поле «%s» изменилось — было %s, стало %s",
                                    key, other, before.get(other), after.get(other))
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("Цена операцией при чужой цене — цена прежняя, заметка сохранена")
    void arithmeticPriceIsNotAppliedToForeignPrice() throws Exception {
        MockHttpSession owner = login("vladelec0313");
        JsonNode before = editable(owner);
        ObjectNode body = json.createObjectNode();
        body.put("price", 10);
        body.put("priceOp", "DECREASE_PERCENT");
        body.put("note", "заметка при операции");
        ObjectNode seen = body.putObject("seen");
        seen.set("price", values.foreign(before.get("price")));
        seen.set("note", before.get("note"));

        MvcResult result = send(owner, body);
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(json.readTree(text(result)).path("notSaved").findValuesAsText("key"))
                .containsExactly("price");
        JsonNode after = editable(owner);
        assertThat(after.get("price").decimalValue()).as("«−10 %» от чужой цены не применён")
                .isEqualByComparingTo(before.get("price").decimalValue());
        assertThat(after.get("note").asText()).isEqualTo("заметка при операции");
    }

    @Test
    @DisplayName("Номер производителя занят — кросс-номера из того же запроса тоже не пишутся, заметка пишется")
    void takenOemKeepsCrossesToo() throws Exception {
        bundleIsNotSplit("oem", "crosses");
    }

    @Test
    @DisplayName("Кросс-номера заняты — номер производителя из того же запроса тоже не пишется, заметка пишется")
    void takenCrossesKeepOemToo() throws Exception {
        bundleIsNotSplit("crosses", "oem");
    }

    private void bundleIsNotSplit(String taken, String partner) throws Exception {
        MockHttpSession owner = login("vladelec0313");
        JsonNode before = editable(owner);
        ObjectNode body = json.createObjectNode();
        body.put("oem", "11111-22222");
        body.put("crosses", "33333-44444");
        body.put("note", "заметка при номерах");
        ObjectNode seen = body.putObject("seen");
        seen.set(taken, values.foreign(before.get(taken)));
        seen.set(partner, before.get(partner));
        seen.set("note", before.get("note"));

        MvcResult result = send(owner, body);
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        JsonNode notSaved = json.readTree(text(result)).path("notSaved");
        assertThat(notSaved.findValuesAsText("key")).containsExactly(taken, partner);
        String takenLabel = SCREEN_LABELS.get(taken);
        assertThat(notSaved.findValuesAsText("cause"))
                .as("член связки назван причиной — занятым полем").containsOnly(takenLabel);

        JsonNode after = editable(owner);
        assertThat(after.get("oem")).isEqualTo(before.get("oem"));
        assertThat(after.get("crosses")).as("набор номеров не записан наполовину")
                .isEqualTo(before.get("crosses"));
        assertThat(after.get("note").asText()).isEqualTo("заметка при номерах");
    }

    @Test
    @DisplayName("Занята связка, а больше в запросе ничего — 409 прежним текстом, ничего не записано")
    void wholeRequestTakenIsRefused() throws Exception {
        MockHttpSession owner = login("vladelec0313");
        JsonNode before = editable(owner);
        ObjectNode body = json.createObjectNode();
        body.put("oem", "11111-22222");
        body.put("crosses", "33333-44444");
        ObjectNode seen = body.putObject("seen");
        seen.set("oem", values.foreign(before.get("oem")));
        seen.set("crosses", before.get("crosses"));

        MvcResult result = send(owner, body);
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(409);
        assertThat(json.readTree(text(result)).path("message").asText())
                .isEqualTo("Поле «Номер производителя» уже изменил другой человек."
                        + " Перезагрузите страницу и повторите правку");
        JsonNode after = editable(owner);
        for (String key : PartEditValues.fieldKeys()) {
            assertThat(PartEditValues.same(before.get(key), after.get(key)))
                    .as("отказ целиком сохранил «%s»", key).isTrue();
        }
    }

    /**
     * Два одновременных сохранения от одного снимка: цена и набор номеров
     * у обоих, а рядом у каждого своё поле. Под блокировкой строки они идут
     * по очереди, и проигравший обязан получить «не сохранено» ровно на то,
     * что лежит от победителя, — без полусохранённого набора номеров.
     */
    @RepeatedTest(10)
    @DisplayName("Два одновременных PUT от одного снимка: ответы согласованы с базой, набор номеров не смешан")
    void concurrentSavesAgreeWithTheBase() throws Exception {
        MockHttpSession a = login("anna0313");
        MockHttpSession b = login("boris0313");
        JsonNode opened = editable(a);

        ObjectNode byA = concurrentBody(opened, 7001, "A-1001", "A-2001", "note", "заметка А");
        ObjectNode byB = concurrentBody(opened, 7002, "B-1002", "B-2002", "marking", "МАРК-Б");

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> sendAfter(start, a, byA));
        CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(() -> sendAfter(start, b, byB));
        start.countDown();
        MvcResult resultA = first.get(30, TimeUnit.SECONDS);
        MvcResult resultB = second.get(30, TimeUnit.SECONDS);

        assertThat(resultA.getResponse().getStatus()).as(text(resultA)).isEqualTo(200);
        assertThat(resultB.getResponse().getStatus()).as(text(resultB)).isEqualTo(200);
        List<String> lostA = json.readTree(text(resultA)).path("notSaved").findValuesAsText("key");
        List<String> lostB = json.readTree(text(resultB)).path("notSaved").findValuesAsText("key");
        assertThat(List.of(lostA.isEmpty(), lostB.isEmpty()))
                .as("ровно один сохранил всё: А не сохранил %s, Б не сохранил %s", lostA, lostB)
                .containsExactlyInAnyOrder(true, false);
        List<String> lost = lostA.isEmpty() ? lostB : lostA;
        assertThat(lost).containsExactlyInAnyOrder("price", "oem", "crosses");

        JsonNode card = editable(a);
        ObjectNode winner = lostA.isEmpty() ? byA : byB;
        assertThat(card.get("price").decimalValue()).isEqualByComparingTo(winner.get("price").decimalValue());
        assertThat(card.get("oem").asText()).isEqualTo(winner.get("oem").asText());
        assertThat(card.get("crosses").asText()).as("набор номеров — от одного, а не смешан")
                .isEqualTo(winner.get("crosses").asText());
        assertThat(card.get("note").asText()).as("своё поле А сохранено").isEqualTo("заметка А");
        assertThat(card.get("marking").asText()).as("своё поле Б сохранено").isEqualTo("МАРК-Б");
    }

    private ObjectNode concurrentBody(JsonNode opened, int price, String oem, String crosses,
                                      String own, String ownValue) {
        ObjectNode body = json.createObjectNode();
        body.put("price", price);
        body.put("oem", oem);
        body.put("crosses", crosses);
        body.put(own, ownValue);
        ObjectNode seen = body.putObject("seen");
        for (String key : List.of("price", "oem", "crosses", own)) {
            seen.set(key, opened.get(key));
        }
        return body;
    }

    private MvcResult sendAfter(CountDownLatch start, MockHttpSession session, JsonNode body) {
        try {
            start.await();
            return send(session, body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ помощники

    private List<String> authors(String label) throws Exception {
        JsonNode history = json.readTree(text(mvc.perform(get("/api/parts/" + partId + "/history")
                .session(login("vladelec0313"))).andReturn()));
        List<String> out = new ArrayList<>();
        for (JsonNode change : history.path("changes")) {
            for (JsonNode field : change.path("fields")) {
                if (field.path("label").asText().equals(label)) {
                    out.add(change.path("author").asText());
                }
            }
        }
        return out;
    }

    private MvcResult send(MockHttpSession session, JsonNode body) throws Exception {
        return mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode editable(MockHttpSession session) throws Exception {
        return json.readTree(text(mvc.perform(get("/api/parts/" + partId + "/editable")
                .session(session)).andReturn()));
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            jdbc.update("UPDATE tenant_member SET display_name = ?, role = ? WHERE id = ?",
                    displayName, role, found.get(0));
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"conflict0313","login":"%s","password":"пароль"}"""
                                .formatted(login)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
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
