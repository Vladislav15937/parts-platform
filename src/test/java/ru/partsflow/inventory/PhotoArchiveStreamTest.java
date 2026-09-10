package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Все снимки позиции одним архивом — через настоящий сервлет-контейнер.
 *
 * <p><b>Зачем живой порт, а не MockMvc.</b> Тело здесь пишется прямо
 * в {@code response.getOutputStream()}, и поломка этого класса выглядит как
 * <b>200 и ноль байт</b>: продавец нажимает «Скачать все фото», браузер
 * рапортует об успехе, а в «Загрузках» лежит нечитаемый файл — и узнаёт он
 * об этом уже в разговоре с покупателем. MockMvc собирает ответ в памяти
 * и позволяет сменить статус после записи, поэтому та же поломка выглядит
 * в нём честным четырёхсотым. Ровно поэтому в проекте уже стоит
 * {@code DromFeedStreamTest}, и здесь тот же случай.
 *
 * <p>Отметки после закрытия потока это скачивание не пишет, поэтому ожидания
 * состояния ({@code awaitMark}) здесь не нужно: проверять нечего.
 *
 * <p>Хранилище настоящее: MinIO контейнером. Иначе проверялась бы форма
 * ответа, а ломается как раз содержимое — в архив кладут байты из S3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=none")
class PhotoArchiveStreamTest extends PostgresTestBase {

    private static final String TENANT = "t_000136";
    private static final String BUCKET = "parts-photos-archive-test";

    /** Файл-объяснение внутри архива: он есть только тогда, когда есть о чём. */
    private static final String NOTE = "NE-VSE-SNIMKI.txt";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>("minio/minio:latest")
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", "minioadmin")
                    .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        String endpoint = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));
        registry.add("app.s3.endpoint", () -> endpoint);
        registry.add("app.s3.public-endpoint", () -> endpoint);
        registry.add("app.s3.bucket", () -> BUCKET);
        registry.add("app.s3.access-key", () -> "minioadmin");
        registry.add("app.s3.secret-key", () -> "minioadmin");
        registry.add("app.s3.path-style-access", () -> true);
        registry.add("app.s3.ensure-bucket", () -> true);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private PhotoService photos;

    @Autowired
    private PhotoStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Позиция с тремя снимками. */
    private Long partId;

    /** Позиция, которую не фотографировали. */
    private Long bareId;

    /** Ключи снимков в порядке полосы миниатюр (sort_order). */
    private List<String> keys;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 136");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name,
                                                    status, code)
                VALUES (136, ?, 'Архивная', 'ACTIVE', 'arhivco')""", TENANT);

        inTenant(() -> {
            jdbc.update("DELETE FROM part_photo");
            jdbc.update("DELETE FROM tenant_member WHERE login IN ('hozyain', 'smotryashchiy')");
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('hozyain', 'Хозяин', ?, 'OWNER'),
                           ('smotryashchiy', 'Смотрящий', ?, 'VIEWER')""",
                    passwordEncoder.encode("пароль-подлиннее"),
                    passwordEncoder.encode("пароль-подлиннее"));
            return null;
        });

        partId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price)
                VALUES (NULL, 'Фара левая', 8500) RETURNING id""", Long.class));
        bareId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price)
                VALUES (NULL, 'Бампер передний', 4000) RETURNING id""", Long.class));

        keys = new ArrayList<>();
        for (int at = 1; at <= 3; at++) {
            keys.add(uploaded(bytesOf(at)));
        }
        // Главным делаем второй по полосе: у карточки главный именно
        // что не первый в полосе миниатюр — иначе проверка порядка
        // ничего бы не различала.
        String secondKey = keys.get(1);
        Long secondId = inTenant(() -> jdbc.queryForObject(
                "SELECT id FROM part_photo WHERE s3_key = ?", Long.class, secondKey));
        inTenant(() -> {
            photos.makeMain(secondId);
            return null;
        });
    }

    @Test
    @DisplayName("Архив отдаётся с телом, а не одними заголовками")
    void archiveIsServedWithBody() {
        ResponseEntity<byte[]> answer = download(partId, signIn("hozyain"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getHeaders().getFirst("Content-Type")).contains("application/zip");
        assertThat(answer.getBody())
                .as("архив приехал пустым: браузер отчитался об успехе, "
                        + "а покупателю отправлять нечего")
                .isNotNull()
                .isNotEmpty();
        assertThat(entriesOf(answer.getBody())).hasSize(3);
        assertThat(entriesOf(answer.getBody()))
                .as("объяснение про недочитанные снимки попало в архив, где всё "
                        + "прочиталось: лишний файл в каждом архиве приучает не читать")
                .doesNotContainKey(NOTE);
    }

    @Test
    @DisplayName("Пропавший из хранилища снимок не ломает архив: остальные на месте")
    void missingObjectLeavesTheRestOfTheArchiveReadable() {
        // Снимок исчезает из хранилища между чтением состава и сборкой:
        // запись в базе остаётся подтверждённой, объекта уже нет. Так
        // выглядит удаление в S3 мимо приложения и потерянный объект.
        // Второй по полосе — главный, значит пропадает 02.jpg.
        storage.delete(keys.get(0));

        ResponseEntity<byte[]> answer = download(partId, signIn("hozyain"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        Map<String, byte[]> inside = entriesOf(answer.getBody());
        assertThat(inside.keySet())
                .as("пропавший снимок унёс с собой остальные: покупателю "
                        + "отправлять нечего, хотя фотографии в карточке есть")
                .containsExactly("01.jpg", "03.jpg", NOTE);
        assertThat(inside.get("01.jpg")).isEqualTo(bytesOf(2));
        assertThat(inside.get("03.jpg")).isEqualTo(bytesOf(3));

        String note = new String(inside.get(NOTE), StandardCharsets.UTF_8);
        assertThat(note)
                .as("архив без части снимков выглядит целым: продавец отправит "
                        + "покупателю два снимка вместо трёх и не узнает об этом")
                .contains("Всего снимков в карточке: 3")
                .contains("Не удалось прочитать: 1")
                .contains("02.jpg");
    }

    @Test
    @DisplayName("Главный снимок в архиве первый, остальные — как в полосе миниатюр")
    void mainPhotoComesFirst() {
        Map<String, byte[]> inside = entriesOf(download(partId, signIn("hozyain")).getBody());

        assertThat(inside.keySet())
                .containsExactly("01.jpg", "02.jpg", "03.jpg");
        // Второй по полосе — главный, значит он и есть 01. Дальше первый
        // и третий в том же порядке, в каком они в карточке.
        assertThat(inside.get("01.jpg"))
                .as("первым в архиве лежит не главный снимок: покупателю "
                        + "отправляют «сначала общий вид»")
                .isEqualTo(bytesOf(2));
        assertThat(inside.get("02.jpg")).isEqualTo(bytesOf(1));
        assertThat(inside.get("03.jpg")).isEqualTo(bytesOf(3));
    }

    @Test
    @DisplayName("Имя файла содержит публичный код позиции и наименование")
    void fileNameCarriesPublicCodeAndTitle() {
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));

        String disposition = download(partId, signIn("hozyain"))
                .getHeaders().getFirst("Content-Disposition");

        assertThat(disposition)
                .as("по имени файла в «Загрузках» не понять, чьи это снимки")
                .contains(code)
                .contains("fara-levaya")
                .endsWith(".zip\"");
    }

    @Test
    @DisplayName("Позиция без снимков отказывает до первого байта, а не отдаёт пустой архив")
    void partWithoutPhotosIsRefusedBeforeTheFirstByte() {
        ResponseEntity<byte[]> answer = download(bareId, signIn("hozyain"));

        assertThat(answer.getStatusCode().value())
                .as("пустой архив выглядит удавшимся скачиванием: продавец "
                        + "ищет файлы в «Загрузках» и не находит")
                .isNotEqualTo(200);
        assertThat(new String(answer.getBody(), StandardCharsets.UTF_8))
                .as("отказ не объясняет себя")
                .contains("снимков");
    }

    @Test
    @DisplayName("«Просмотр» скачивает так же, как владелец")
    void viewerDownloadsTheSameArchive() {
        // Роли те же, что у просмотра снимков в карточке: в архиве ровно
        // те фотографии, которые смотрящий и так видит на экране.
        ResponseEntity<byte[]> answer = download(partId, signIn("smotryashchiy"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(entriesOf(answer.getBody())).hasSize(3);
    }

    // ---------- вспомогательное ----------

    private ResponseEntity<byte[]> download(Long part, String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session);
        return http.exchange(url("/api/parts/%d/photos/archive".formatted(part)),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
    }

    /**
     * Вход через настоящий HTTP: без сессии архив не отдаётся никому.
     *
     * <p>CSRF-токен берётся заново перед входом — так же, как это делает
     * браузер: репозиторий токенов у нас в cookie, и без него POST входа
     * получил бы 401 с пустым телом, неотличимый от неверного пароля.
     */
    private String signIn(String login) {
        ResponseEntity<String> csrf = http.getForEntity(url("/api/auth/csrf"), String.class);
        String token = cookie(csrf, "XSRF-TOKEN");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);

        ResponseEntity<String> login1 = http.postForEntity(url("/api/auth/login"),
                new HttpEntity<>("""
                        {"company":"arhivco","login":"%s","password":"пароль-подлиннее"}"""
                        .formatted(login), headers),
                String.class);
        assertThat(login1.getStatusCode().value()).as("вход не удался").isEqualTo(200);
        return "JSESSIONID=" + cookie(login1, "JSESSIONID");
    }

    private static String cookie(ResponseEntity<?> answer, String name) {
        List<String> all = answer.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (all == null) {
            return "";
        }
        for (String raw : all) {
            if (raw.startsWith(name + "=")) {
                int end = raw.indexOf(';');
                return raw.substring(name.length() + 1, end < 0 ? raw.length() : end);
            }
        }
        return "";
    }

    /** Содержимое архива по именам записей, в том порядке, в каком оно лежит. */
    private static Map<String, byte[]> entriesOf(byte[] archive) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                out.put(entry.getName(), zip.readAllBytes());
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("архив не читается как zip: " + e.getMessage(), e);
        }
        return out;
    }

    /** Полный цикл снимка: ссылка, загрузка в хранилище, подтверждение. */
    private String uploaded(byte[] body) throws Exception {
        PhotoService.Upload upload = inTenant(() -> photos.requestUpload(
                partId, "image/jpeg", java.util.UUID.randomUUID().toString()));
        HttpResponse<Void> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                        .header("Content-Type", "image/jpeg")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
        inTenant(() -> photos.confirmUpload(upload.photoId(), 800, 600));
        return upload.key();
    }

    /** Байты «снимка»: хранилищу всё равно, а нам важно различать их. */
    private static byte[] bytesOf(int at) {
        return ("снимок фары номер " + at).getBytes(StandardCharsets.UTF_8);
    }

    private String url(String path) {
        return "http://localhost:%d%s".formatted(port, path);
    }

    /**
     * Арендатор ставится <b>до</b> открытия транзакции: {@code search_path}
     * выставляет провайдер соединений Hibernate в момент выдачи соединения.
     */
    private <T> T inTenant(Supplier<T> work) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }
}
