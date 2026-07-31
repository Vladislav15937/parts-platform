package ru.partsflow.migration.bazon;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Перенос фотографий из предыдущей системы.
 *
 * <p>Чужой CDN подменён локальным сервером: настоящий отвечает по-разному
 * в разные дни, а проверять надо поведение переноса, а не доступность
 * baz-on.ru. Mockito на этой JDK не работает, поэтому заглушка рукописная —
 * тот же приём, что в {@code DromDeltaSenderTest}.
 */
@SpringBootTest
class PhotoMigrationTest extends PostgresTestBase {

    private static final String TENANT = "t_000042";

    /** Однопиксельный GIF: настоящие байты картинки, а не текст под видом её. */
    private static final byte[] IMAGE = {
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, (byte) 0x80,
            0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            0x21, (byte) 0xf9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b};

    private static HttpServer cdn;

    @Autowired
    private PhotoMigration migration;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long partId;

    @BeforeAll
    static void startCdn() throws IOException {
        cdn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cdn.createContext("/ok.jpg", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, IMAGE.length);
            exchange.getResponseBody().write(IMAGE);
            exchange.close();
        });
        cdn.createContext("/gone.jpg", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        cdn.createContext("/empty.jpg", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        cdn.start();
    }

    @AfterAll
    static void stopCdn() {
        cdn.stop(0);
    }

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            jdbc.update("DELETE FROM part_photo_import");
            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price) VALUES (1, 'Фара', 1000)
                    RETURNING id""", Long.class);
            return null;
        });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + cdn.getAddress().getPort() + path;
    }

    @Test
    @DisplayName("Снимок переезжает в хранилище и появляется у карточки")
    void photoIsTransferred() {
        queue(url("/ok.jpg"), 0);

        PhotoMigration.Progress progress = inTenant(() -> migration.migrateBatch(10));

        assertThat(progress.done()).isEqualTo(1);
        assertThat(progress.pending()).isZero();
        assertThat(photoCount()).isEqualTo(1);
        // Первый по порядку становится главным — тем же, что был главным
        // в прежней системе.
        assertThat(mainCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Битая ссылка не останавливает проход")
    void brokenLinkDoesNotStopTheRest() {
        // Одна битая ссылка из ста тысяч не должна оставлять склад без снимков.
        queue(url("/gone.jpg"), 0);
        queue(url("/ok.jpg"), 1);

        PhotoMigration.Progress progress = inTenant(() -> migration.migrateBatch(10));

        assertThat(progress.done()).isEqualTo(1);
        assertThat(progress.failed()).isEqualTo(1);
        assertThat(photoCount()).isEqualTo(1);
        assertThat(inTenant(() -> jdbc.queryForObject("""
                SELECT error FROM part_photo_import WHERE status = 'FAILED'""", String.class)))
                .contains("404");
    }

    @Test
    @DisplayName("Пустой ответ не заводит карточке пустую картинку")
    void emptyResponseIsNotAPhoto() {
        queue(url("/empty.jpg"), 0);

        PhotoMigration.Progress progress = inTenant(() -> migration.migrateBatch(10));

        assertThat(progress.failed()).isEqualTo(1);
        assertThat(photoCount()).isZero();
    }

    @Test
    @DisplayName("Повторный проход не переносит перенесённое дважды")
    void doneStaysDone() {
        queue(url("/ok.jpg"), 0);
        inTenant(() -> migration.migrateBatch(10));

        PhotoMigration.Progress second = inTenant(() -> migration.migrateBatch(10));

        assertThat(second.done()).isZero();
        assertThat(photoCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Неудачные возвращаются в очередь отдельным действием")
    void failedAreRetriedOnlyWhenAsked() {
        queue(url("/gone.jpg"), 0);
        inTenant(() -> migration.migrateBatch(10));

        // Сам по себе следующий проход их не трогает: удалённый файл
        // не появится от повтора, и вечный повтор скрывал бы это.
        assertThat(inTenant(() -> migration.migrateBatch(10)).failed()).isZero();

        assertThat(inTenant(() -> migration.retryFailed())).isEqualTo(1);
        assertThat(inTenant(() -> migration.status()).pending()).isEqualTo(1);
    }

    private void queue(String url, int order) {
        inTenant(() -> jdbc.update("""
                INSERT INTO part_photo_import (part_id, url, sort_order) VALUES (?, ?, ?)""",
                partId, url, order));
    }

    private long photoCount() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo WHERE part_id = ?", Long.class, partId));
    }

    private long mainCount() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part_photo WHERE part_id = ? AND is_main",
                Long.class, partId));
    }

    /**
     * Внутри транзакции, а не только с выставленным контекстом: search_path
     * ставит провайдер соединений Hibernate при выдаче соединения транзакции,
     * а JdbcTemplate снаружи берёт соединение из пула и уходит в public.
     * Наступил на это здесь же, в собственном тесте.
     */
    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
