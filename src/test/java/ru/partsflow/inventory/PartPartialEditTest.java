package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Правка карточки шлёт только изменённое, а чужую правку того же поля
 * не затирает молча — задача 0307.
 *
 * <p>Замер на ПРОМ 16.10.2026: две вкладки, одна правит цену, другая заметку;
 * вторая, сохраняя форму целиком, откатывала цену первой до увиденной
 * при открытии, и в истории автором отката значился второй. Ответ владельца
 * продукта дословно: «1-б, и только если правили одно и то же поле сделать
 * предупреждение с просьбой перезагрузить страницу».
 *
 * <p>Проверки идут перебором по компонентам {@code UpdateRequest}, а не по
 * списку полей: новое поле формы попадает в них в тот же момент, когда его
 * дописали в запись, — и без значения для проверки валит её словами.
 *
 * <p>Через HTTP: различие «ключа нет» и «ключ пуст» живёт в разборе тела,
 * а вызов сервиса напрямую его не видит вовсе.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartPartialEditTest extends PostgresTestBase {

    private static final String TENANT = "t_000987";

    /** Не поле карточки, а указание, что сделать с ценой. */
    private static final Set<String> NOT_A_FIELD = Set.of("priceOp");

    /**
     * Пустое значение здесь — «не трогать», как и до задачи: у колонки
     * {@code NOT NULL} пустого не бывает, а пустая цена значит «цену не трогаем»;
     * пустой № позиции экран не отправляет вовсе, а сервер его не трогает.
     */
    private static final Set<String> EMPTY_KEEPS = Set.of("condition", "title", "published",
            "price", "number");

    /** Пустое здесь — отказ словами: без номера товара позицию не найти ни сканером, ни глазами. */
    private static final Set<String> EMPTY_REFUSED = Set.of("code");

    private static final Set<String> ENUMS = Set.of("condition", "qualityGrade");

    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * Подписи строк формы правки — как их видит человек. Отказ обязан назвать
     * поле этими словами: читаются они из самого экрана, а не переписываются
     * сюда, — иначе разошлись бы на первой правке подписи.
     */
    private static final Map<String, String> SCREEN_LABELS = screenLabels();

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
    private Long cellA;
    private Long cellB;
    private String suffix;
    private long number;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 987");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (987, ?, 'Разборка', 'partial0307')""", TENANT);
        suffix = String.valueOf(SEQ.incrementAndGet());

        inTenant(() -> {
            member("vladelec0307", "Пётр Владельцев", "OWNER");
            member("menedzher0307", "Мария Менеджерова", "MANAGER");
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            Long warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Основной') RETURNING id",
                    Long.class, branch);
            cellA = jdbc.queryForObject("INSERT INTO storage_cell (warehouse_id, code)"
                    + " VALUES (?, ?) RETURNING id", Long.class, warehouse, "А-" + suffix);
            cellB = jdbc.queryForObject("INSERT INTO storage_cell (warehouse_id, code)"
                    + " VALUES (?, ?) RETURNING id", Long.class, warehouse, "Б-" + suffix);
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, is_published)
                    VALUES (1, 'Фара', 100, true) RETURNING id""", Long.class);
            number = jdbc.queryForObject("SELECT max(number) + 1000 FROM part", Long.class);
            return null;
        });

        // Заполнено всё — иначе «не стёрлось» нечем отличить от «и не было».
        ObjectNode full = json.createObjectNode();
        initial().forEach((key, value) -> full.set(key, value));
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(login("vladelec0307"))
                        .contentType(MediaType.APPLICATION_JSON).content(full.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    @Test
    @DisplayName("Ключ один — меняется только его поле; остальные, цена, публикация и блоки Дрома прежние")
    void eachKeyAloneChangesOnlyItsField() throws Exception {
        MockHttpSession owner = login("vladelec0307");
        Map<String, JsonNode> changed = changed();

        for (String key : fieldKeys()) {
            assertThat(changed).as("у поля «%s» нет нового значения для проверки — допишите его"
                    + " в changed()", key).containsKey(key);
            JsonNode before = editable(owner);

            ObjectNode body = json.createObjectNode();
            body.set(key, changed.get(key));
            MvcResult result = send(owner, body);
            assertThat(result.getResponse().getStatus())
                    .as("ключ «%s» один: %s", key, text(result)).isEqualTo(200);

            JsonNode after = editable(owner);
            assertThat(same(before.get(key), after.get(key)))
                    .as("ключ «%s» был в запросе, а поле не изменилось", key).isFalse();
            for (String other : fieldKeys()) {
                if (!other.equals(key)) {
                    assertThat(same(before.get(other), after.get(other)))
                            .as("запрос с одним ключом «%s» тронул поле «%s»: было %s, стало %s",
                                    key, other, before.get(other), after.get(other))
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("Ключ есть и пуст — поле очищается (где очистка допустима), остальные прежние")
    void presentEmptyKeyClearsOnlyItsField() throws Exception {
        MockHttpSession owner = login("vladelec0307");

        for (String key : fieldKeys()) {
            JsonNode before = editable(owner);
            ObjectNode body = json.createObjectNode();
            // Пусто так, как шлёт экран: текст — пустой строкой, число и выбор
            // из перечисления — null (пустую строку перечисление не разбирает).
            body.set(key, before.get(key).isTextual() && !ENUMS.contains(key)
                    ? json.getNodeFactory().textNode("") : json.getNodeFactory().nullNode());
            MvcResult result = send(owner, body);
            JsonNode after = editable(owner);

            if (EMPTY_REFUSED.contains(key)) {
                assertThat(result.getResponse().getStatus())
                        .as("пустое «%s» — отказ словами", key).isEqualTo(400);
            } else {
                assertThat(result.getResponse().getStatus())
                        .as("пустое «%s»: %s", key, text(result)).isEqualTo(200);
                if (EMPTY_KEEPS.contains(key)) {
                    assertThat(same(before.get(key), after.get(key)))
                            .as("пустое «%s» значит «не трогать»", key).isTrue();
                } else {
                    assertThat(blank(after.get(key)))
                            .as("пустое «%s» обязано очистить поле, а стало %s", key,
                                    after.get(key)).isTrue();
                }
            }
            for (String other : fieldKeys()) {
                if (!other.equals(key)) {
                    assertThat(same(before.get(other), after.get(other)))
                            .as("пустой ключ «%s» тронул поле «%s»", key, other).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("Поле успели изменить — 409 с его названием, и не сохраняется ничего из запроса")
    void staleSeenValueIsRefusedByName() throws Exception {
        MockHttpSession owner = login("vladelec0307");
        Map<String, JsonNode> changed = changed();

        for (String key : fieldKeys()) {
            JsonNode before = editable(owner);
            ObjectNode body = json.createObjectNode();
            body.set(key, changed.get(key));
            // Соседнее поле без конфликта: «всё или ничего» — оно тоже не сохраняется.
            String neighbour = key.equals("note") ? "marking" : "note";
            body.put(neighbour, "соседнее из того же запроса " + key);
            ObjectNode seen = body.putObject("seen");
            seen.set(key, foreign(before.get(key)));
            seen.set(neighbour, before.get(neighbour));

            MvcResult result = send(owner, body);
            String message = json.readTree(text(result)).path("message").asText();
            assertThat(result.getResponse().getStatus())
                    .as("«%s» успели изменить, а сервер ответил: %s", key, text(result))
                    .isEqualTo(409);
            String label = SCREEN_LABELS.get(key);
            assertThat(message)
                    .as("отказ называет поле словами экрана, а не внутренним именем")
                    .contains(label == null ? "«" : "«" + label + "»")
                    .contains("уже изменил другой человек", "Перезагрузите страницу")
                    .doesNotContain(key + "»");

            JsonNode after = editable(owner);
            for (String any : fieldKeys()) {
                assertThat(same(before.get(any), after.get(any)))
                        .as("отказ по «%s» сохранил «%s»", key, any).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Две вкладки: цена и заметка обе сохраняются; одна цена дважды — второму предупреждение")
    void twoTabs() throws Exception {
        MockHttpSession a = login("vladelec0307");
        MockHttpSession b = login("menedzher0307");
        JsonNode openedA = editable(a);
        JsonNode openedB = editable(b);

        assertThat(send(a, edit(openedA, "price", 5000)).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(b, edit(openedB, "note", "заметка B")).getResponse().getStatus())
                .isEqualTo(200);

        JsonNode card = editable(a);
        assertThat(card.get("price").decimalValue()).isEqualByComparingTo("5000");
        assertThat(card.get("note").asText()).isEqualTo("заметка B");

        JsonNode againA = editable(a);
        JsonNode againB = editable(b);
        assertThat(send(a, edit(againA, "price", 5100)).getResponse().getStatus()).isEqualTo(200);
        MvcResult second = send(b, edit(againB, "price", 5200));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(text(second)).path("message").asText())
                .isEqualTo("Поле «Цена» уже изменил другой человек."
                        + " Перезагрузите страницу и повторите правку");
        assertThat(editable(b).get("price").decimalValue())
                .as("после перезагрузки видна цена первого").isEqualByComparingTo("5100");

        // История: цену правил A, заметку B; отката чужим автором нет.
        JsonNode history = json.readTree(mvc.perform(get("/api/parts/" + partId + "/history")
                .session(a)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<String> priceAuthors = new ArrayList<>();
        List<String> noteAuthors = new ArrayList<>();
        for (JsonNode change : history.path("changes")) {
            for (JsonNode field : change.path("fields")) {
                if (field.path("label").asText().equals("Цена")) {
                    priceAuthors.add(change.path("author").asText());
                }
                if (field.path("label").asText().equals("Заметка")) {
                    noteAuthors.add(change.path("author").asText());
                }
            }
        }
        // Первая правка — заполнение в fixtures() владельцем; дальше две цены A и заметка B.
        assertThat(priceAuthors).as("автор правок цены").containsOnly("Пётр Владельцев");
        assertThat(priceAuthors).hasSize(3);
        assertThat(noteAuthors).as("автор правок заметки")
                .containsExactlyInAnyOrder("Мария Менеджерова", "Пётр Владельцев");
    }

    @Test
    @DisplayName("Оба поменяли цену на одно и то же — не конфликт")
    void sameValueIsNoConflict() throws Exception {
        MockHttpSession a = login("vladelec0307");
        MockHttpSession b = login("menedzher0307");
        JsonNode openedA = editable(a);
        JsonNode openedB = editable(b);

        assertThat(send(a, edit(openedA, "price", 5300)).getResponse().getStatus()).isEqualTo(200);
        MvcResult same = send(b, edit(openedB, "price", 5300));
        assertThat(same.getResponse().getStatus()).as(text(same)).isEqualTo(200);
    }

    @Test
    @DisplayName("Старая вкладка шлёт форму целиком без увиденного — принимается, как до задачи")
    void oldTabSendsTheWholeForm() throws Exception {
        MockHttpSession a = login("vladelec0307");
        MockHttpSession b = login("menedzher0307");
        ObjectNode stale = (ObjectNode) editable(b);

        assertThat(send(a, edit(editable(a), "price", 5400)).getResponse().getStatus())
                .isEqualTo(200);
        stale.put("note", "старая вкладка");
        MvcResult result = send(b, stale);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        JsonNode card = editable(a);
        assertThat(card.get("note").asText()).isEqualTo("старая вкладка");
        assertThat(card.get("price").decimalValue())
                .as("без «увиденного» сравнивать не с чем: форма целиком перезаписывает,"
                        + " как до задачи 0307")
                .isEqualByComparingTo("4500");
    }

    // ------------------------------------------------------------------ значения

    /** Заполнение позиции целиком — до проверок. */
    private Map<String, JsonNode> initial() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("price", 4500);
        v.put("minPrice", 3000);
        v.put("costPrice", 1000);
        v.put("installationPrice", 500);
        v.put("condition", "USED");
        v.put("qualityGrade", "AS_NEW");
        v.put("description", "Комментарий исходный");
        v.put("note", "Заметка исходная");
        v.put("textBlock", "Блок исходный");
        v.put("videoUrl", "https://video.example/1");
        v.put("marking", "МАРК-1");
        v.put("manufacturer", "Koito");
        v.put("color", "чёрный");
        v.put("section", "С-1");
        v.put("barcode", "BC1-" + suffix);
        v.put("weightKg", 2.5);
        v.put("lengthMm", 100);
        v.put("widthMm", 200);
        v.put("heightMm", 300);
        v.put("packageLengthMm", 110);
        v.put("packageWidthMm", 210);
        v.put("packageHeightMm", 310);
        v.put("packageWeightKg", 3.5);
        v.put("storageCellId", cellA);
        v.put("published", true);
        v.put("warranty", "Гарантия 14 дней");
        v.put("pickup", "Самовывоз с Ткацкой");
        v.put("deliveryPayment", "Доставка ТК");
        v.put("dromKind", "Фара");
        v.put("dromSection", "Оптика");
        v.put("applicability", "Toyota Camry");
        v.put("characteristics", "Цвет: чёрный");
        v.put("mayFit", "Toyota Mark X");
        v.put("adDate", "2026-09-28");
        v.put("priceOp", "SET");
        v.put("number", number);
        v.put("code", "CODE1-" + suffix);
        v.put("title", "Фара исходная");
        v.put("sideLr", "LEFT");
        v.put("sideFr", "FRONT");
        v.put("authenticity", "Оригинал");
        v.put("oem", "81170-33670");
        v.put("crosses", "81170-33671");
        v.put("legacyCode", "LEG1-" + suffix);
        v.put("dromAdNumber", "1256963" + suffix);
        v.put("donorCode", "261");
        v.put("brand", "Toyota");
        v.put("model", "Camry");
        v.put("generation", "XV40");
        v.put("body", "ACV40");
        v.put("engine", "2AZ-FE");
        v.put("year", "2006");
        v.put("equipment", "полный привод");
        return nodes(v);
    }

    /** Новое значение каждого поля — другое, чем в {@link #initial()}. */
    private Map<String, JsonNode> changed() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("price", 4600);
        v.put("minPrice", 3100);
        v.put("costPrice", 1100);
        v.put("installationPrice", 600);
        v.put("condition", "CONTRACT");
        v.put("qualityGrade", "WITH_DEFECTS");
        v.put("description", "Комментарий новый");
        v.put("note", "Заметка новая");
        v.put("textBlock", "Блок новый");
        v.put("videoUrl", "https://video.example/2");
        v.put("marking", "МАРК-2");
        v.put("manufacturer", "Depo");
        v.put("color", "белый");
        v.put("section", "С-2");
        v.put("barcode", "BC2-" + suffix);
        v.put("weightKg", 2.7);
        v.put("lengthMm", 101);
        v.put("widthMm", 201);
        v.put("heightMm", 301);
        v.put("packageLengthMm", 111);
        v.put("packageWidthMm", 211);
        v.put("packageHeightMm", 311);
        v.put("packageWeightKg", 3.7);
        v.put("storageCellId", cellB);
        v.put("published", false);
        v.put("warranty", "Гарантия 30 дней");
        v.put("pickup", "Самовывоз с Дальнего");
        v.put("deliveryPayment", "Доставка курьером");
        v.put("dromKind", "Фара противотуманная");
        v.put("dromSection", "Освещение");
        v.put("applicability", "Toyota Camry, Toyota Aurion");
        v.put("characteristics", "Цвет: белый");
        v.put("mayFit", "Toyota Windom");
        v.put("adDate", "2026-10-01");
        v.put("number", number + 1);
        v.put("code", "CODE2-" + suffix);
        v.put("title", "Фара новая");
        v.put("sideLr", "RIGHT");
        v.put("sideFr", "REAR");
        v.put("authenticity", "Копия");
        v.put("oem", "81170-33680");
        v.put("crosses", "81170-33681");
        v.put("legacyCode", "LEG2-" + suffix);
        v.put("dromAdNumber", "1256964" + suffix);
        v.put("donorCode", "262");
        v.put("brand", "Lexus");
        v.put("model", "ES350");
        v.put("generation", "XV50");
        v.put("body", "GSV40");
        v.put("engine", "2GR-FE");
        v.put("year", "2008");
        v.put("equipment", "передний привод");
        return nodes(v);
    }

    /** «Увиденное», которого в базе уже нет: значение, отличное от текущего. */
    private JsonNode foreign(JsonNode current) {
        if (current == null || current.isNull()) {
            return json.getNodeFactory().textNode("чужое значение");
        }
        if (current.isBoolean()) {
            return json.getNodeFactory().booleanNode(!current.booleanValue());
        }
        if (current.isNumber()) {
            return json.getNodeFactory().numberNode(current.decimalValue().add(BigDecimal.valueOf(7)));
        }
        return json.getNodeFactory().textNode(current.asText() + " — чужая правка");
    }

    private Map<String, JsonNode> nodes(Map<String, Object> values) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, json.valueToTree(value)));
        return out;
    }

    /** Все поля формы — компоненты записи, кроме указания про цену. */
    private static List<String> fieldKeys() {
        List<String> keys = new ArrayList<>();
        for (RecordComponent component : PartController.UpdateRequest.class.getRecordComponents()) {
            if (!NOT_A_FIELD.contains(component.getName())) {
                keys.add(component.getName());
            }
        }
        return keys;
    }

    /** Правка одного поля так, как её шлёт экран: значение и то, что человек видел. */
    private ObjectNode edit(JsonNode opened, String key, Object value) {
        ObjectNode body = json.createObjectNode();
        body.set(key, json.valueToTree(value));
        body.putObject("seen").set(key, opened.get(key));
        return body;
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (blank(a) && blank(b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }

    private static boolean blank(JsonNode node) {
        return node == null || node.isNull() || (node.isTextual() && node.asText().isEmpty());
    }

    // ------------------------------------------------------------------ помощники

    private static Map<String, String> screenLabels() {
        try {
            String form = java.nio.file.Files.readString(
                    java.nio.file.Path.of("frontend/src/screens/PartEditForm.tsx"));
            Map<String, String> labels = new LinkedHashMap<>();
            var m = java.util.regex.Pattern.compile("key: '(\\w+)', label: '([^']+)'").matcher(form);
            while (m.find()) {
                labels.put(m.group(1), m.group(2));
            }
            assertThat(labels).as("подписи формы правки не нашлись в PartEditForm.tsx")
                    .containsKeys("price", "note", "oem");
            return labels;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
                                {"company":"partial0307","login":"%s","password":"пароль"}"""
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
