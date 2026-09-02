package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Фотографии запчастей против настоящего S3.
 *
 * <p>MinIO поднимается контейнером, и снимок действительно уезжает по
 * подписанной ссылке. Без этого проверялась бы только форма URL — а ломается
 * как раз подпись: в неё входят хост, метод и content-type, и любое
 * расхождение даёт отказ уже на телефоне приёмщика.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PhotoServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000050";
    private static final String BUCKET = "parts-photos-test";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>("minio/minio:" +
                    "" +
                    "" +
                    "latest")
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
        // Бакет заводит приложение при старте, как в бою. Здесь это
        // единственный контекст с настоящим хранилищем, значит и проверять
        // заведение больше негде: у остальных S3 нет вовсе.
        registry.add("app.s3.ensure-bucket", () -> true);
    }

    @Autowired
    private PhotoService photos;

    @Autowired
    private S3Client s3;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private Long partId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Бакет заводится при старте, а не руками")
    void bucketIsCreatedOnStartup() {
        // До появления S3BucketInitializer его не создавал никто: свежая
        // ячейка отдавала подписанную ссылку, телефон писал по ней файл
        // и получал «bucket does not exist», а в логах приложения не было
        // ничего — файл идёт мимо него намеренно. Этот тест бакет создавал
        // сам и потому дыры не видел.
        assertThat(s3.listBuckets().buckets())
                .extracting(software.amazon.awssdk.services.s3.model.Bucket::name)
                .contains(BUCKET);
    }

    @BeforeEach
    void fixtures() {

        partId = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, cost_price)
                VALUES (NULL, 'Фара левая Camry V50', 8500, 4000) RETURNING id""", Long.class));

        // Вход нужен только тем проверкам, что идут через HTTP: досъёмка
        // из карточки — единственный путь, где отказ читает человек.
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 50");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (50, ?, 'Снимочная', 'fotoco')""", TENANT);
        inTenant(() -> {
            jdbc.update("DELETE FROM tenant_member WHERE login = 'hozyain'");
            jdbc.update("""
                    INSERT INTO tenant_member (login, display_name, password_hash, role)
                    VALUES ('hozyain', 'Хозяин', ?, 'OWNER')""",
                    passwordEncoder.encode("пароль-подлиннее"));
            return null;
        });
    }

    /**
     * Отказ подтверждения объясняется словами, а не голым кодом.
     *
     * <p>Пока снимки грузил только телефон, кода ответа хватало: очередь
     * разбирает его сама. С появлением досъёмки из карточки этот отказ
     * увидел человек — и увидел «Запрос отклонён (409)», по которому
     * не понять ни что случилось, ни что делать.
     *
     * <p>Через HTTP, а не через сервис: сервис отвечает {@code false},
     * а теряется текст на границе контроллера.
     */
    @Test
    @DisplayName("Подтверждение без файла отказывает словами")
    void confirmWithoutUploadExplainsItself() throws Exception {
        PhotoService.Upload upload =
                inTenant(() -> photos.requestUpload(partId, "image/jpeg", uniqueRequestId()));

        var session = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .with(org.springframework.security.test.web.servlet.request
                                        .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("""
                                        {"company":"fotoco","login":"hozyain",
                                         "password":"пароль-подлиннее"}"""))
                .andReturn().getRequest().getSession(false);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/parts/%d/photos/%d/confirm".formatted(partId, upload.photoId()))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .session((org.springframework.mock.web.MockHttpSession) session)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("хранилище")));
    }

    @Test
    @DisplayName("Снимок уезжает по подписанной ссылке и подтверждается")
    void photoTravelsByPresignedUrl() throws Exception {
        PhotoService.Upload upload = inTenant(() -> photos.requestUpload(partId, "image/jpeg", uniqueRequestId()));

        int status = put(upload.uploadUrl(), "image/jpeg", jpegBytes());
        assertThat(status).as("хранилище отвергло подписанную ссылку").isEqualTo(200);

        assertThat(inTenant(() -> photos.confirmUpload(upload.photoId(), 1920, 1080))).isTrue();

        assertThat(inTenant(() -> jdbc.queryForMap(
                "SELECT status, bytes, width FROM part_photo WHERE id = ?", upload.photoId())))
                .containsEntry("status", "PROCESSED")
                .containsEntry("bytes", (long) jpegBytes().length)
                .containsEntry("width", 1920);
    }

    @Test
    @DisplayName("Подтверждение без загрузки не создаёт битую картинку")
    void confirmWithoutUploadFails() {
        PhotoService.Upload upload = inTenant(() -> photos.requestUpload(partId, "image/jpeg", uniqueRequestId()));

        // Телефон сказал «загрузил», а связь оборвалась. Верить нельзя.
        assertThat(inTenant(() -> photos.confirmUpload(upload.photoId(), null, null))).isFalse();

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part_photo WHERE id = ?", String.class, upload.photoId())))
                .isEqualTo("FAILED");
        assertThat(inTenant(() -> photos.of(partId)))
                .as("неподтверждённая фотография попала в карточку").isEmpty();
    }

    @Test
    @DisplayName("Повтор запроса ссылки возвращает ту же фотографию с новой ссылкой")
    void repeatedUploadRequestIsIdempotent() {
        String requestId = uniqueRequestId();

        PhotoService.Upload first = inTenant(
                () -> photos.requestUpload(partId, "image/jpeg", requestId));
        PhotoService.Upload again = inTenant(
                () -> photos.requestUpload(partId, "image/jpeg", requestId));

        // Вторая запись означала бы мусор в хранилище и лишнюю пустую картинку
        // в карточке. А вот ссылку выдаём новую: прежняя за время ожидания
        // очереди истекла.
        assertThat(again.photoId()).isEqualTo(first.photoId());
        assertThat(again.key()).isEqualTo(first.key());
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo WHERE part_id = ?", Integer.class, partId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Ключ объекта начинается со схемы арендатора")
    void keyIsPrefixedWithTenant() {
        PhotoService.Upload upload = inTenant(() -> photos.requestUpload(partId, "image/jpeg", uniqueRequestId()));

        // Изоляция префиксом: удаление и выгрузка одного клиента — одна операция.
        assertThat(upload.key()).startsWith(TENANT + "/parts/" + partId + "/");
        assertThat(upload.key()).endsWith(".jpg");
    }

    @Test
    @DisplayName("Первая фотография становится главной сама")
    void firstPhotoBecomesMain() throws Exception {
        PhotoService.Upload first = uploaded("image/jpeg");
        PhotoService.Upload second = uploaded("image/jpeg");

        List<PhotoService.PhotoView> views = inTenant(() -> photos.of(partId));
        assertThat(views).hasSize(2);
        assertThat(views).filteredOn(PhotoService.PhotoView::main)
                .extracting(PhotoService.PhotoView::photoId)
                .containsExactly(first.photoId());
        assertThat(second.photoId()).isNotEqualTo(first.photoId());
    }

    @Test
    @DisplayName("Смена главной снимает признак с прежней")
    void mainPhotoSwitches() throws Exception {
        PhotoService.Upload first = uploaded("image/jpeg");
        PhotoService.Upload second = uploaded("image/png");

        inTenant(() -> {
            photos.makeMain(second.photoId());
            return null;
        });

        // В БД частичный уникальный индекс «одна главная на деталь»: если
        // прежнюю не снять, вставка упадёт на нём.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo WHERE part_id = ? AND is_main", Integer.class, partId)
        )).isEqualTo(1);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT is_main FROM part_photo WHERE id = ?", Boolean.class, first.photoId())
        )).isFalse();
    }

    @Test
    @DisplayName("Неподтверждённую нельзя сделать главной")
    void unconfirmedCannotBeMain() {
        PhotoService.Upload pending = inTenant(() -> photos.requestUpload(partId, "image/jpeg", uniqueRequestId()));

        assertThatThrownBy(() -> inTenant(() -> {
            photos.makeMain(pending.photoId());
            return null;
        })).hasMessageContaining("только загруженную");
    }

    @Test
    @DisplayName("Удаление главной передаёт признак следующей")
    void deletingMainPromotesNext() throws Exception {
        PhotoService.Upload first = uploaded("image/jpeg");
        PhotoService.Upload second = uploaded("image/jpeg");

        inTenant(() -> {
            photos.delete(first.photoId());
            return null;
        });

        // Карточка без главной фотографии на площадке выглядит пустой.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT is_main FROM part_photo WHERE id = ?", Boolean.class, second.photoId())
        )).isTrue();
    }

    @Test
    @DisplayName("Удаление убирает файл из хранилища, а не только запись")
    void deleteRemovesObject() throws Exception {
        PhotoService.Upload upload = uploaded("image/jpeg");
        assertThat(objectExists(upload.key())).isTrue();

        inTenant(() -> {
            photos.delete(upload.photoId());
            return null;
        });

        assertThat(objectExists(upload.key()))
                .as("файл остался в хранилище и будет копить счёт за место").isFalse();
    }

    @Test
    @DisplayName("Удаление снимка отмечает позицию для выгрузки")
    void deleteMarksThePart() throws Exception {
        PhotoService.Upload upload = uploaded("image/jpeg");
        inTenant(() -> jdbc.update("DELETE FROM part_change WHERE part_id = ?", partId));

        inTenant(() -> {
            photos.delete(upload.photoId());
            return null;
        });

        // Ссылка на удалённый снимок в прайсе — битая картинка в объявлении,
        // а за такое площадка объявление снимает. Проверка стоит здесь,
        // а не в PartChangeLogTest: удаление ходит в хранилище, и у того
        // контекста хранилища нет — на CI он падал бы «Connection refused».
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_change WHERE part_id = ?", Integer.class, partId)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Ссылка на просмотр открывается")
    void viewUrlWorks() throws Exception {
        uploaded("image/jpeg");

        String url = inTenant(() -> photos.of(partId)).get(0).url();

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(jpegBytes());
    }

    // ---------- вспомогательное ----------

    /** Полный цикл: ссылка, загрузка, подтверждение. */
    private PhotoService.Upload uploaded(String contentType) throws Exception {
        PhotoService.Upload upload = inTenant(() -> photos.requestUpload(partId, contentType, uniqueRequestId()));
        put(upload.uploadUrl(), contentType, jpegBytes());
        inTenant(() -> photos.confirmUpload(upload.photoId(), 800, 600));
        return upload;
    }

    private int put(String url, String contentType, byte[] body) throws IOException, InterruptedException {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        // Content-Type входит в подпись: другой здесь — отказ хранилища.
                        .header("Content-Type", contentType)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    private boolean objectExists(String key) {
        return inTenant(() -> {
            try {
                s3.headObject(b -> b.bucket(BUCKET).key(key));
                return true;
            } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
                return false;
            }
        });
    }

    private static String uniqueRequestId() {
        return java.util.UUID.randomUUID().toString();
    }

    private static byte[] jpegBytes() {
        // Не настоящий JPEG: хранилищу всё равно, а тесту важен только размер.
        return "снимок фары, 3 мегапикселя".getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
