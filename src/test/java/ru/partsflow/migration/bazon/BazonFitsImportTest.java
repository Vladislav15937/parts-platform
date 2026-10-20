package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.platform.tenant.TenantProvisioning;
import ru.partsflow.publishing.drom.DromPriceGenerator;
import ru.partsflow.support.PostgresTestBase;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Блок «&lt;Вид&gt; подходит:» и текстовый блок из файла выгрузки Bazon на Дром
 * (задача 0337): перенос, именованные блоки и описание в файле.
 *
 * <p>Через HTTP, а не вызовом сервиса: итог переноса — запись, а запись, не
 * сериализуемая Jackson'ом, в тестах сервиса не видна.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class BazonFitsImportTest extends PostgresTestBase {

    private static final String FITS_A = """
            Фара подходит:
            Toyota Camry 5 поколение 2001–2006 XV30 (ACV30, MCV30) двигатель 2AZ-FE (2.4 Б)
            Toyota Camry 5 поколение 2001–2006 XV30 (ACV30), любой двигатель (1MZ-FE)""";

    private static final String PAYMENT = "ОПЛАТА В МАГАЗИНЕ ТОЛЬКО НАЛИЧНЫМИ!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantProvisioning provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DromPriceGenerator generator;

    @TempDir
    Path files;

    @Test
    @DisplayName("split: блоки берутся по заголовку и по месту, комментарий и текстовый блок не путаются")
    void splitFindsBlocksByPlace() {
        // Полное описание: комментарий, «снята с», «подходит», текстовый блок.
        var full = BazonFitsImporter.split("\nС накладкой.\n\nЗапчасть снята с: Toyota Camry\n\n"
                + FITS_A + "\n\n" + PAYMENT);
        assertThat(full.fits()).isEqualTo(FITS_A);
        assertThat(full.textBlock()).isEqualTo(PAYMENT);

        // Комментарий с переводами строк и пустой строкой внутри не мешает.
        var multiline = BazonFitsImporter.split("\nПервая строка\r\n\r\nвторая\n\n" + FITS_A);
        assertThat(multiline.fits()).isEqualTo(FITS_A);
        assertThat(multiline.textBlock()).isNull();

        // Текстовый блок из нескольких строк, с пустой строкой внутри — до конца.
        var longTail = BazonFitsImporter.split("\n" + FITS_A + "\n\nПервый абзац\n\nВторой абзац");
        assertThat(longTail.textBlock()).isEqualTo("Первый абзац\n\nВторой абзац");

        // Нет «подходит», есть «снята с»: хвост после неё — текстовый блок.
        var noFits = BazonFitsImporter.split("\nЗапчасть снята с: Toyota Camry\n\n" + PAYMENT);
        assertThat(noFits.fits()).isNull();
        assertThat(noFits.textBlock()).isEqualTo(PAYMENT);

        // Один блок без опоры: комментарий это или текстовый блок — не различить.
        var lone = BazonFitsImporter.split("\n" + PAYMENT);
        assertThat(lone.fits()).isNull();
        assertThat(lone.textBlock()).isNull();
        assertThat(lone.tailUnclear()).isTrue();

        // Слова «подходит:» в середине комментария — не заголовок блока.
        var inComment = BazonFitsImporter.split("\nЭтот бампер подходит: смотрите фото\n\n" + PAYMENT);
        assertThat(inComment.fits()).isNull();
    }

    @Test
    @DisplayName("Файл Bazon переносит «подходит» и текстовый блок; два одинаковых текста — один блок;"
            + " повтор ничего не меняет; иное не затирается; в файле Дрома блок на своём месте")
    void importFillsFitsAndSharesTextBlock() throws Exception {
        String code = "fit" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        String schema = tenant.schemaName();
        new BazonImporter(dataSource, schema).importAll(donors(), catalog(
                row("101", "Фара левая"), row("102", "Фара правая"), row("103", "Бампер")));
        MockHttpSession session = login(code);

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <parts>
                <part><id>101</id><description><![CDATA[
                %s

                %s]]></description></part>
                <part><id>102</id><description><![CDATA[
                %s

                %s]]></description></part>
                <part><id>103</id><description><![CDATA[
                %s]]></description></part>
                <part><id>9999</id><description><![CDATA[
                %s

                %s]]></description></part>
                </parts>
                """.formatted(FITS_A, PAYMENT, FITS_A, PAYMENT, PAYMENT, FITS_A, PAYMENT);

        mvc.perform(multipart("/api/import/bazon/fits").file(xml(xml)).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(4))
                .andExpect(jsonPath("$.notFound").value(1))
                .andExpect(jsonPath("$.fitsFilled").value(2))
                .andExpect(jsonPath("$.textBlocksSet").value(2))
                .andExpect(jsonPath("$.textBlocksNew").value(1))
                .andExpect(jsonPath("$.tailUnclear").value(1));

        assertThat(jdbc.queryForObject("SELECT bazon_fits FROM " + schema
                + ".part_drom_card c JOIN " + schema + ".part p ON p.id = c.part_id"
                + " WHERE p.number = 101", String.class)).isEqualTo(FITS_A);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".text_block", Integer.class))
                .as("одинаковые тексты схлопнулись в один блок").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT text_block_id) FROM " + schema
                + ".part WHERE number IN (101, 102)", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT text_block_id FROM " + schema
                + ".part WHERE number = 103", Long.class))
                .as("единственный блок без опоры не принят за текстовый").isNull();

        // Повтор: заполненное узнаётся и не считается заново.
        mvc.perform(multipart("/api/import/bazon/fits").file(xml(xml)).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fitsFilled").value(0))
                .andExpect(jsonPath("$.fitsSame").value(2))
                .andExpect(jsonPath("$.textBlocksSet").value(0))
                .andExpect(jsonPath("$.textBlockBusy").value(2))
                .andExpect(jsonPath("$.textBlocksNew").value(0));

        // Другой текст в файле не затирает записанное и называется.
        String changed = xml.replace("2AZ-FE (2.4 Б)", "2AZ-FE (2.5 Б)");
        mvc.perform(multipart("/api/import/bazon/fits").file(xml(changed)).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fitsDiffers").value(2));
        assertThat(jdbc.queryForObject("SELECT bazon_fits FROM " + schema
                + ".part_drom_card c JOIN " + schema + ".part p ON p.id = c.part_id"
                + " WHERE p.number = 101", String.class)).isEqualTo(FITS_A);

        // Файл Дрома: «подходит» стоит перед текстовым блоком, без чужих строк.
        String feed = feed(schema);
        assertThat(feed).contains("<![CDATA[\n" + FITS_A + "\n\n" + PAYMENT + "]]>");

        // Правка блока доходит до объявлений без правки позиций.
        long blockId = jdbc.queryForObject("SELECT id FROM " + schema + ".text_block", Long.class);
        mvc.perform(put("/api/text-blocks/" + blockId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Наличные\",\"body\":\"Только наличные\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Наличные"));
        assertThat(feed(schema)).contains("\n\nТолько наличные]]>").doesNotContain(PAYMENT);
    }

    @Test
    @DisplayName("Справочник блоков: имя не повторяется без регистра, занятый блок не удаляется,"
            + " выбор в карточке меняет описание, снятый выбор оставляет карточку без блока")
    void dictionaryAndSelection() throws Exception {
        String code = "fit" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        String schema = tenant.schemaName();
        new BazonImporter(dataSource, schema).importAll(donors(), catalog(row("201", "Фара левая")));
        MockHttpSession session = login(code);

        mvc.perform(post("/api/text-blocks").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Наличные\",\"body\":\"" + PAYMENT + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
        mvc.perform(post("/api/text-blocks").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  наличные \",\"body\":\"другое\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("уже есть")));
        mvc.perform(post("/api/text-blocks").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Пустой\",\"body\":\"  \"}"))
                .andExpect(status().isBadRequest());

        long blockId = jdbc.queryForObject("SELECT id FROM " + schema + ".text_block", Long.class);
        long partId = jdbc.queryForObject("SELECT id FROM " + schema + ".part WHERE number = 201", Long.class);

        // Выбор блока в карточке — тем же PUT, что правка остального.
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textBlockId\":" + blockId + "}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT text_block_id FROM " + schema + ".part WHERE id = ?",
                Long.class, partId)).isEqualTo(blockId);
        assertThat(feed(schema)).contains(PAYMENT);

        mvc.perform(delete("/api/text-blocks/" + blockId).with(csrf()).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("выбран у позиций: 1")));

        // Старая вкладка шлёт свободный текст: он вытесняет выбранный блок, а не прячется за ним.
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textBlock\":\"Свой текст позиции\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT text_block_id FROM " + schema + ".part WHERE id = ?",
                Long.class, partId)).isNull();
        assertThat(feed(schema)).contains("Свой текст позиции").doesNotContain(PAYMENT);
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textBlockId\":" + blockId + "}"))
                .andExpect(status().isOk());

        // Снять выбор: ключ с null.
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textBlockId\":null}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT text_block_id FROM " + schema + ".part WHERE id = ?",
                Long.class, partId)).isNull();
        assertThat(feed(schema)).doesNotContain(PAYMENT);

        // Свободный блок удаляется.
        mvc.perform(delete("/api/text-blocks/" + blockId).with(csrf()).session(session))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Не владелец файл Bazon не переносит; не XML — 400 словами")
    void accessAndFormat() throws Exception {
        String code = "fit" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        jdbc.update("INSERT INTO " + tenant.schemaName() + ".tenant_member"
                + " (display_name, role, login, password_hash) VALUES ('Продавец', 'SELLER', 'prodavets', ?)",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("пароль-8симв"));
        var seller = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"%s\",\"login\":\"prodavets\",\"password\":\"пароль-8симв\"}"
                                .formatted(code)))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(multipart("/api/import/bazon/fits").file(xml("<parts/>")).with(csrf())
                        .session((MockHttpSession) seller.getRequest().getSession(false)))
                .andExpect(status().isForbidden());

        // Справочник блоков: читать может любой вошедший, писать — владелец и менеджер.
        MockHttpSession sellerSession = (MockHttpSession) seller.getRequest().getSession(false);
        mvc.perform(post("/api/text-blocks").with(csrf()).session(login(code))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Блок\",\"body\":\"Текст\"}")).andExpect(status().isCreated());
        long blockId = jdbc.queryForObject("SELECT id FROM " + tenant.schemaName() + ".text_block", Long.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/text-blocks")
                .session(sellerSession)).andExpect(status().isOk());
        mvc.perform(post("/api/text-blocks").with(csrf()).session(sellerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Чужой\",\"body\":\"Текст\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/text-blocks/" + blockId).with(csrf()).session(sellerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Блок\",\"body\":\"Подмена\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/text-blocks/" + blockId).with(csrf()).session(sellerSession))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT body FROM " + tenant.schemaName() + ".text_block", String.class))
                .isEqualTo("Текст");

        jdbc.update("INSERT INTO " + tenant.schemaName() + ".tenant_member"
                + " (display_name, role, login, password_hash) VALUES ('Менеджер', 'MANAGER', 'menedzher', ?)",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("пароль-8симв"));
        var manager = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"%s\",\"login\":\"menedzher\",\"password\":\"пароль-8симв\"}"
                                .formatted(code)))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession managerSession = (MockHttpSession) manager.getRequest().getSession(false);
        mvc.perform(post("/api/text-blocks").with(csrf()).session(managerSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Блок менеджера\",\"body\":\"Текст\"}")).andExpect(status().isCreated());

        mvc.perform(multipart("/api/import/bazon/fits").file(xml("это не xml")).with(csrf())
                        .session(login(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("XML")));
    }

    @Test
    @DisplayName("Файл с DOCTYPE и ENTITY не раскрывается: ни внутренняя, ни внешняя сущность в позицию не попадает")
    void doctypeIsNotExpanded() throws Exception {
        String code = "fit" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        String schema = tenant.schemaName();
        new BazonImporter(dataSource, schema).importAll(donors(), catalog(row("401", "Фара левая")));
        Path secret = files.resolve("secret.txt");
        Files.writeString(secret, "ТАЙНОЕ-СОДЕРЖИМОЕ");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE parts [
                  <!ENTITY inner "РАСКРЫТО-ВНУТРЕННЯЯ">
                  <!ENTITY outer SYSTEM "%s">
                ]>
                <parts>
                <part><id>401</id><description>
                Фара подходит:
                &inner; &outer;</description></part>
                </parts>
                """.formatted(secret.toUri());
        var result = mvc.perform(multipart("/api/import/bazon/fits").file(xml(xml)).with(csrf())
                .session(login(code))).andReturn();
        // Либо отказ словами, либо игнорирование сущностей, — но ни следа содержимого в базе.
        assertThat(result.getResponse().getStatus()).isIn(200, 400);
        String stored = String.join("|", jdbc.queryForList("SELECT coalesce(bazon_fits, '') || coalesce(text_block, '')"
                + " FROM " + schema + ".part p LEFT JOIN " + schema + ".part_drom_card c ON c.part_id = p.id", String.class));
        assertThat(stored).doesNotContain("РАСКРЫТО-ВНУТРЕННЯЯ").doesNotContain("ТАЙНОЕ-СОДЕРЖИМОЕ");
        assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .doesNotContain("ТАЙНОЕ-СОДЕРЖИМОЕ");
    }

    @Test
    @DisplayName("У позиции свой свободный текст без выбранного блока — перенос его не трогает и называет")
    void ownFreeTextIsNotTouched() throws Exception {
        String code = "fit" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        String schema = tenant.schemaName();
        new BazonImporter(dataSource, schema).importAll(donors(), catalog(row("501", "Фара левая")));
        jdbc.update("UPDATE " + schema + ".part SET text_block = 'Свой текст клиента' WHERE number = 501");
        String xml = "<parts><part><id>501</id><description><![CDATA[\n" + FITS_A + "\n\n" + PAYMENT
                + "]]></description></part></parts>";

        mvc.perform(multipart("/api/import/bazon/fits").file(xml(xml)).with(csrf()).session(login(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.textBlocksSet").value(0))
                .andExpect(jsonPath("$.textBlockBusy").value(1))
                .andExpect(jsonPath("$.fitsFilled").value(1));
        assertThat(jdbc.queryForObject("SELECT text_block FROM " + schema + ".part WHERE number = 501",
                String.class)).isEqualTo("Свой текст клиента");
        assertThat(jdbc.queryForObject("SELECT text_block_id FROM " + schema + ".part WHERE number = 501",
                Long.class)).isNull();
    }

    @Test
    @DisplayName("Правка блока видна одним и тем же текстом в файле Дрома, списке склада и выгрузке склада")
    void blockEditIsSeenEverywhere() throws Exception {
        String code = "fit" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        String schema = tenant.schemaName();
        new BazonImporter(dataSource, schema).importAll(donors(), catalog(row("301", "Фара левая")));
        MockHttpSession session = login(code);
        mvc.perform(post("/api/text-blocks").with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Наличные\",\"body\":\"СТАРЫЙ-ТЕКСТ-БЛОКА\"}")).andExpect(status().isCreated());
        long blockId = jdbc.queryForObject("SELECT id FROM " + schema + ".text_block", Long.class);
        long partId = jdbc.queryForObject("SELECT id FROM " + schema + ".part WHERE number = 301", Long.class);
        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"textBlockId\":" + blockId + "}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/text-blocks/" + blockId).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Наличные\",\"body\":\"НОВЫЙ-ТЕКСТ-БЛОКА\"}")).andExpect(status().isOk());

        String list = new String(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/parts/catalog").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String export = new String(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/parts/catalog/export").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        for (String surface : new String[] {list, export, feed(schema)}) {
            assertThat(surface).contains("НОВЫЙ-ТЕКСТ-БЛОКА").doesNotContain("СТАРЫЙ-ТЕКСТ-БЛОКА");
        }
    }

    // ---------- фикстуры ----------

    private static MockMultipartFile xml(String content) {
        return new MockMultipartFile("file", "drom-parts.xml", "application/xml",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String HEADER = """
            "Номер товара";"Запчасть";"Номер донора";"Цена";"Ткацкая (свободно)";\
            "Ткацкая (резерв)";"Ткацкая (ожидается)";"Выгружать"
            """;

    private static String row(String number, String name) {
        return "\"%s\";\"%s\";\"\";\"1000\";\"1\";\"0\";\"0\";\"да\"\n".formatted(number, name);
    }

    private Path catalog(String... rows) throws Exception {
        return write("catalog-" + UUID.randomUUID() + ".csv", HEADER + String.join("", rows));
    }

    private Path donors() throws Exception {
        return write("donors.csv", """
                "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус"
                """);
    }

    private Path write(String name, String content) throws Exception {
        Path path = files.resolve(name);
        Files.write(path, content.getBytes(BazonCsvReader.CHARSET));
        return path;
    }

    private String feed(String schema) {
        try {
            TenantContext.set(schema);
            return transactions.execute(status -> {
                var out = new ByteArrayOutputStream();
                try {
                    generator.writeTo(out);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return out.toString(StandardCharsets.UTF_8);
            });
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpSession login(String code) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"%s\",\"login\":\"vladelec\",\"password\":\"пароль-8симв\"}"
                                .formatted(code)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
