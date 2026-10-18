package ru.partsflow.migration.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import ru.partsflow.inventory.PhotoStorage;
import ru.partsflow.migration.excel.DromTransferFile.Column;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;
import ru.partsflow.support.TestImages;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Снимки из файла переноса с Дрома (задача 0273): колонка «Фото» → очередь
 * переноса → наше хранилище, в том же порядке и в том же размере.
 *
 * <p><b>Раскладка — как у живого файла клиента</b>: строка на карточку,
 * адреса {@code …_full} в одной ячейке через перевод строки, главный первым,
 * до шестнадцати на карточку. Всё через HTTP, теми же запросами, что шлёт
 * экран «Импорт»: разбор, загрузка, проход переноса пачками.
 *
 * <p>Дром подменён локальным сервером: проверяется поведение переноса,
 * а не доступность static.baza.drom.ru. Mockito на этой JDK не работает —
 * заглушка рукописная, тот же приём, что в {@code PhotoMigrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DromTransferPhotoTest extends PostgresTestBase {

    private static final String TENANT = "t_000981";
    private static final int REGISTRY_ID = 981;
    private static final String COMPANY = "dromphotos";

    /** Сколько снимков у самой богатой карточки клиента. */
    private static final int MOST_PHOTOS = 16;

    /**
     * Настоящее хранилище: «байты не пережаты» проверяются в нём, а не тем,
     * что метод позвался. Почему свой контейнер — в {@code PhotoMigrationTest}.
     */
    private static final GenericContainer<?> MINIO = TestImages.minio();

    static {
        TestImages.startMinio(MINIO);
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        String endpoint = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));
        registry.add("app.s3.endpoint", () -> endpoint);
        registry.add("app.s3.public-endpoint", () -> endpoint);
        registry.add("app.s3.bucket", () -> "drom-transfer-photo-test");
        registry.add("app.s3.access-key", () -> "minioadmin");
        registry.add("app.s3.secret-key", () -> "minioadmin");
        registry.add("app.s3.path-style-access", () -> true);
        registry.add("app.s3.ensure-bucket", () -> true);
    }

    private static HttpServer drom;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PhotoStorage storage;

    private final ObjectMapper json = new ObjectMapper();
    private long warehouseId;

    /**
     * Снимок по адресу: свои байты у каждого адреса, иначе сравнение
     * контрольных сумм не отличило бы первый снимок от пятого. Заголовок
     * WebP настоящий — Дром отдаёт именно его, — хвост пережимом не станет.
     */
    private static byte[] imageAt(String path) {
        byte[] tail = path.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[64 * 1024 + tail.length];
        byte[] head = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < body.length - tail.length; i++) {
            body[i] = (byte) (i * 31 + path.hashCode());
        }
        System.arraycopy(tail, 0, body, body.length - tail.length, tail.length);
        return body;
    }

    @BeforeAll
    static void startDrom() throws IOException {
        drom = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        drom.createContext("/drom/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("gone")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = imageAt(path);
            exchange.getResponseHeaders().add("Content-Type", "image/webp");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        drom.start();
    }

    @AfterAll
    static void stopDrom() {
        drom.stop(0);
    }

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома: снимки', ?)""", REGISTRY_ID, TENANT, COMPANY);
        warehouseId = inTenant(() -> {
            jdbc.update("DELETE FROM part_photo_import");
            // Те же пять объявлений грузятся в каждом тесте, а объявление, номер
            // которого уже есть, второй позицией не заводится (задача 0274):
            // без очистки следующий тест видел бы «уже у позиции» вместо загрузки.
            jdbc.update("DELETE FROM import_run");
            jdbc.update("DELETE FROM part_oem");
            jdbc.update("DELETE FROM part_stock");
            jdbc.update("DELETE FROM stock_movement");
            jdbc.update("DELETE FROM part");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Барнаул') RETURNING id", Long.class);
            return jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Исакова') RETURNING id",
                    Long.class, branch);
        });
    }

    @Test
    @DisplayName("Пять карточек → пять наборов заданий по порядку ссылок; повтор не задваивает")
    void photoColumnFillsTheQueueInGalleryOrder() throws Exception {
        List<Card> cards = cards();
        byte[] file = file(cards);
        JsonNode preview = preview(file);

        assertThat(preview.path("detected").path("PHOTOS").asInt(-1))
                .as("колонка «Фото» не сопоставлена сама — экран показал бы её «не переносится»")
                .isEqualTo(titles().indexOf("Фото"));

        String requestId = UUID.randomUUID().toString();
        JsonNode report = load(file, preview.path("detected"), requestId);
        assertThat(report.path("imported").asInt())
                .as("загружено не всё: %s", report.path("skipped"))
                .isEqualTo(cards.size());

        for (Card card : cards) {
            long part = partOf(card);
            assertThat(queued(part))
                    .as("у «%s» очередь снимков не та, что в ячейке, или не в том порядке", card.name())
                    .containsExactlyElementsOf(card.expected());
            assertThat(inTenant(() -> jdbc.queryForList(
                    "SELECT sort_order FROM part_photo_import WHERE part_id = ? ORDER BY sort_order",
                    Integer.class, part)))
                    .as("порядок у «%s» с дырами: главным станет не первый снимок", card.name())
                    .containsExactlyElementsOf(
                            IntStream.range(0, card.expected().size()).boxed().toList());
        }
        long before = queueSize();

        // Ответ не дошёл, владелец нажал ещё раз — тем же ключом.
        JsonNode replay = load(file, preview.path("detected"), requestId);
        assertThat(replay.path("imported").asInt()).isEqualTo(cards.size());
        assertThat(queueSize())
                .as("повтор загрузки поставил снимки в очередь второй раз")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("Проход с экрана кладёт снимки как есть: главный первый, байты те же, битая ссылка не мешает")
    void migrationKeepsOrderMainAndBytes() throws Exception {
        List<Card> cards = cards();
        byte[] file = file(cards);
        load(file, preview(file).path("detected"), UUID.randomUUID().toString());

        // Как экран: пачки по двести, пока очередь не опустеет или не встанет.
        JsonNode progress = photoPass();
        long left = Long.MAX_VALUE;
        while (progress.path("pending").asLong() > 0 && progress.path("pending").asLong() < left) {
            left = progress.path("pending").asLong();
            progress = photoPass();
        }
        assertThat(progress.path("pending").asLong())
                .as("очередь не дошла до нуля — экран показал бы «ждёт» навсегда")
                .isZero();

        Card richest = cards.get(0);
        long part = partOf(richest);
        List<Map<String, Object>> photos = inTenant(() -> jdbc.queryForList("""
                SELECT ph.s3_key, ph.is_main, ph.sort_order, ph.bytes, i.url
                  FROM part_photo ph
                  JOIN part_photo_import i ON i.photo_id = ph.id
                 WHERE ph.part_id = ? ORDER BY ph.sort_order""", part));

        assertThat(photos).extracting(p -> p.get("url"))
                .as("снимки карточки легли не все или не в порядке галереи")
                .containsExactlyElementsOf(richest.expected());
        assertThat(photos).filteredOn(p -> Boolean.TRUE.equals(p.get("is_main")))
                .extracting(p -> p.get("url"))
                .as("главным стал не первый снимок — у клиента обложка другая")
                .containsExactly(richest.expected().get(0));

        // Не уменьшен и не пережат: байты в хранилище — байты по ссылке.
        for (Map<String, Object> photo : photos) {
            String url = (String) photo.get("url");
            byte[] served = imageAt(java.net.URI.create(url).getPath());
            byte[] stored;
            try (InputStream in = inTenant(() -> storage.open((String) photo.get("s3_key")))) {
                stored = in.readAllBytes();
            }
            assertThat(sha256(stored))
                    .as("снимок %s в хранилище не тот, что отдал Дром", url)
                    .isEqualTo(sha256(served));
            assertThat(((Number) photo.get("bytes")).longValue()).isEqualTo(served.length);
        }

        // Битая первая ссылка — отказ со словами, остальные снимки той же
        // карточки перенесены.
        Card broken = cards.get(3);
        long brokenPart = partOf(broken);
        assertThat(inTenant(() -> jdbc.queryForList("""
                SELECT error FROM part_photo_import
                 WHERE part_id = ? AND status = 'FAILED'""", String.class, brokenPart)))
                .as("битая ссылка не названа причиной")
                .containsExactly("ответ 404");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo WHERE part_id = ?", Long.class, brokenPart)))
                .as("битая ссылка остановила перенос остальных снимков карточки")
                .isEqualTo(broken.expected().size() - 1L);

        // Задача 0282: повтор файла после настоящего переноса. Строки очереди
        // остаются DONE с photo_id, поэтому уже скачанный снимок по тому же
        // адресу второй раз в очередь не встаёт и в карточке не задваивается.
        long queuedBefore = queueSize();
        long storedBefore = inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo", Long.class));
        JsonNode again = load(file, preview(file).path("detected"), UUID.randomUUID().toString());
        assertThat(again.path("imported").asInt()).isZero();
        assertThat(queueSize())
                .as("повтор файла после переноса поставил скачанные снимки в очередь заново")
                .isEqualTo(queuedBefore);
        assertThat(photoPass().path("pending").asLong()).isZero();
        assertThat(inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_photo", Long.class)))
                .as("повтор файла после переноса задвоил снимки в карточках")
                .isEqualTo(storedBefore);
    }

    /**
     * Пункт 5 критерия и решение владельца 13.10.2026: новая выгрузка на Дром
     * без ручной настройки отдаёт все шестнадцать снимков карточки, обложку
     * первой. Прежнее умолчание (не задано → 10) отрезало бы шесть.
     */
    @Test
    @DisplayName("Новая выгрузка на Дром отдаёт все снимки переехавшей карточки, главный первым")
    void newDromFeedCarriesEveryPhoto() throws Exception {
        List<Card> cards = cards();
        byte[] file = file(cards);
        load(file, preview(file).path("detected"), UUID.randomUUID().toString());
        JsonNode progress = photoPass();
        long left = Long.MAX_VALUE;
        while (progress.path("pending").asLong() > 0 && progress.path("pending").asLong() < left) {
            left = progress.path("pending").asLong();
            progress = photoPass();
        }

        Card richest = cards.get(0);
        long part = partOf(richest);
        // Публикация и категория — не предмет проверки: у переехавшей позиции
        // их ставят свои задачи (0278, «Выгружать»), а прайс без них позицию
        // не возьмёт вовсе.
        inTenant(() -> jdbc.update(
                "UPDATE part SET category_id = 1, is_published = true WHERE id = ?", part));
        List<Long> shown = inTenant(() -> jdbc.queryForList(
                "SELECT id FROM part_photo WHERE part_id = ? ORDER BY is_main DESC, sort_order, id",
                Long.class, part));
        assertThat(shown).hasSize(MOST_PHOTOS);

        // Как экран: выгрузка заводится без числа снимков — владелец его не трогал.
        JsonNode feed = json.readTree(mvc.perform(post("/api/marketplace-accounts")
                        .with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"marketplace":"DROM","title":"Дром %s","settings":"{}",
                                 "productLine":"PART"}""".formatted(richest.name())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String path = json.readTree(mvc.perform(post("/api/marketplace-accounts/"
                                + feed.path("id").asLong() + "/feed-url")
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("path").asText();

        String price = mvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int at = price.indexOf(richest.name());
        assertThat(at).as("позиции нет в прайсе вовсе").isPositive();
        String offer = price.substring(at, price.indexOf("</part>", at));

        assertThat(offer.split("<photoUrl>", -1).length - 1)
                .as("новая выгрузка урезала снимки переехавшей карточки")
                .isEqualTo(MOST_PHOTOS);
        assertThat(offer.substring(offer.indexOf("<photoUrl>")))
                .as("первой ссылкой прайса ушёл не главный снимок — обложка на Дроме будет другой")
                .startsWith("<photoUrl>")
                .contains("/photo/%d.".formatted(shown.get(0)));
        assertThat(offer.indexOf("/photo/%d.".formatted(shown.get(0))))
                .as("главный снимок не первый в прайсе")
                .isLessThan(offer.indexOf("/photo/%d.".formatted(shown.get(1))));
    }

    // --- карточки ---

    /**
     * @param cell     что лежит в ячейке «Фото»
     * @param expected что обязано встать в очередь, по порядку
     */
    private record Card(String name, String cell, List<String> expected) {
    }

    /**
     * Пять карточек раскладкой живого файла. Своё имя на прогон: позиции
     * между тестами не чистятся (журнал движений неизменяем).
     */
    private List<Card> cards() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<Card> cards = new ArrayList<>();

        List<String> sixteen = links(run + "a", MOST_PHOTOS);
        cards.add(new Card("Двигатель 1GR-FE " + run, String.join("\n", sixteen), sixteen));

        // Excel, сохранивший ячейку, приносит CRLF и пробелы по краям.
        List<String> fourteen = links(run + "b", 14);
        cards.add(new Card("Карданный вал передний " + run,
                " " + String.join("\r\n", fourteen) + "\n", fourteen));

        // Одна ссылка дважды — одно задание, место первого появления,
        // и следующая за повтором встаёт вплотную, без дыры в порядке.
        List<String> three = links(run + "c", 3);
        cards.add(new Card("Рулевая рейка " + run,
                String.join("\n", three.get(0), three.get(1), three.get(0), three.get(2)), three));

        List<String> withGone = new ArrayList<>(List.of(link(run + "gone", 0)));
        withGone.addAll(links(run + "d", 2));
        cards.add(new Card("Расширитель крыла " + run, String.join("\n", withGone), withGone));

        List<String> one = links(run + "e", 1);
        cards.add(new Card("АКПП " + run, one.get(0), one));
        return cards;
    }

    private static List<String> links(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(n -> link(prefix, n)).toList();
    }

    private static String link(String prefix, int n) {
        return "http://127.0.0.1:%d/drom/%s%013d_full"
                .formatted(drom.getAddress().getPort(), prefix, 1737520136727L + n);
    }

    private static List<String> titles() {
        return DromTransferFile.COLUMNS.stream().map(Column::title).toList();
    }

    /** Файл договора: все колонки, текстом, строка на карточку. */
    private static byte[] file(List<Card> cards) throws IOException {
        List<String> titles = titles();
        try (XSSFWorkbook book = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Дром");
            Row head = sheet.createRow(0);
            for (int c = 0; c < titles.size(); c++) {
                head.createCell(c).setCellValue(titles.get(c));
            }
            for (int r = 0; r < cards.size(); r++) {
                Card card = cards.get(r);
                Map<String, String> values = new LinkedHashMap<>();
                values.put(DromTransferFile.ANCHOR, String.valueOf(125696381 + r));
                values.put("Наименование", card.name());
                values.put("Цена", "16000");
                // Количества на странице Дрома нет; явная единица — чтобы
                // тест не зависел от умолчания 0279.
                values.put("Количество", "1");
                // «Наличие» читается с задачи 0279: пустое пропускает строку.
                values.put("Наличие", "В наличии");
                values.put("Фото", card.cell());
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < titles.size(); c++) {
                    row.createCell(c).setCellValue(values.getOrDefault(titles.get(c), ""));
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    // --- база ---

    private long partOf(Card card) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT max(id) FROM part WHERE title = ?", Long.class, card.name()));
    }

    private List<String> queued(long part) {
        return inTenant(() -> jdbc.queryForList(
                "SELECT url FROM part_photo_import WHERE part_id = ? ORDER BY sort_order, id",
                String.class, part));
    }

    private long queueSize() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo_import", Long.class));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    // --- HTTP, как экран ---

    private JsonNode preview(byte[] file) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/import/excel/preview").file(xlsx(file))
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Сопоставление уходит тем, что пришло в разборе: человек ничего не выбирал. */
    private JsonNode load(byte[] file, JsonNode detected, String requestId) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/import/excel");
        request.file(xlsx(file));
        detected.fields().forEachRemaining(e -> request.param(e.getKey(), e.getValue().asText()));
        return json.readTree(mvc.perform(request.with(csrf()).session(login())
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", requestId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode photoPass() throws Exception {
        return json.readTree(mvc.perform(post("/api/import/bazon/photos").param("limit", "200")
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile xlsx(byte[] file) {
        return new MockMultipartFile("file", "perenos-s-droma.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file);
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"owner","password":"пароль"}"""
                                .formatted(COMPANY)))
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
