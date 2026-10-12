package ru.partsflow.publishing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.publishing.DeliveryStatus.Code;
import ru.partsflow.publishing.drom.DromAnswer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Причина отказа — по виду ответа, а не по подстроке (задача 0268, ответ
 * владельца продукта от 12 октября 2026: «показывать реальную проблему,
 * а не общими словами»).
 *
 * <p>На каждое тело из перечня задачи — свой случай: известный код,
 * незнакомый {@code ERROR_REASON_X}, тот же код в другом регистре
 * и обрамлении, пустое тело, HTML-страница ошибки. Текст {@code last_error}
 * здесь тот, что пишет {@code DromSyncRecord}: метка статуса, затем ответ.
 */
class DeliveryStatusTest {

    private static final Instant AT = Instant.parse("2026-10-12T07:05:00Z");

    private static DeliveryStatus refused(String lastError) {
        DeliveryStatus status = DeliveryStatus.of("DROM", "ACTIVE", true, "4242", AT, lastError);
        assertThat(status.state()).as(lastError).isEqualTo(DeliveryStatus.State.REFUSED);
        return status;
    }

    @Test
    @DisplayName("Известный код узнаётся в любом правдоподобном обрамлении: текст, JSON, XML, кавычки, пробелы")
    void knownCodeInEveryFraming() {
        assertThat(refused("[HTTP 200] ERROR_REASON_PACKET_NOT_FOUND").code())
                .isEqualTo(Code.PACKET_NOT_FOUND);
        assertThat(refused("[HTTP 200]   ERROR_REASON_PACKET_NOT_FOUND\n").code())
                .isEqualTo(Code.PACKET_NOT_FOUND);
        assertThat(refused("[HTTP 200] \"ERROR_REASON_AUTH_FAILED\"").code())
                .isEqualTo(Code.AUTH_FAILED);
        assertThat(refused("[HTTP 200] {\"error\":\"ERROR_REASON_AUTH_FAILED\"}").code())
                .isEqualTo(Code.AUTH_FAILED);
        assertThat(refused("[HTTP 400] {\"result\":{\"errors\":[\" ERROR_REASON_EMPTY_REQUEST \"]}}").code())
                .isEqualTo(Code.EMPTY_REQUEST);
        assertThat(refused("[HTTP 200] <response><error>ERROR_REASON_PACKET_NOT_FOUND</error></response>")
                .code()).isEqualTo(Code.PACKET_NOT_FOUND);
        assertThat(refused("[HTTP 200] Ошибка: ERROR_REASON_PACKET_NOT_FOUND.").code())
                .isEqualTo(Code.PACKET_NOT_FOUND);
        // Статус ответа приезжает вместе с кодом, знакомые слова кода не повторяют.
        DeliveryStatus auth = refused("[HTTP 403] ERROR_REASON_AUTH_FAILED");
        assertThat(auth.httpStatus()).isEqualTo(403);
        assertThat(auth.detail()).isNull();
    }

    @Test
    @DisplayName("Незнакомый ERROR_REASON_X — свой исход с самим кодом, а не «нет кода» и не общий")
    void unknownReasonIsNotNoReason() {
        DeliveryStatus unknown = refused("[HTTP 200] ERROR_REASON_PACKET_DISABLED");
        assertThat(unknown.code()).isEqualTo(Code.UNKNOWN_REASON);
        assertThat(unknown.detail()).isEqualTo("ERROR_REASON_PACKET_DISABLED");

        // Подстрока бы сочла это знакомым кодом: длинное начинается с короткого.
        DeliveryStatus longer = refused("[HTTP 200] {\"error\":\"ERROR_REASON_PACKET_NOT_FOUND_V2\"}");
        assertThat(longer.code()).isEqualTo(Code.UNKNOWN_REASON);
        assertThat(longer.detail()).isEqualTo("ERROR_REASON_PACKET_NOT_FOUND_V2");

        // Два разных кода: какой главный, мы не знаем — не угадываем.
        assertThat(refused("[HTTP 200] ERROR_REASON_AUTH_FAILED ERROR_REASON_PACKET_NOT_FOUND").code())
                .isEqualTo(Code.UNKNOWN_REASON);

        // А вот кода нет вовсе — это другой исход, с началом ответа.
        DeliveryStatus none = refused("[HTTP 200] {\"status\":\"maintenance\"}");
        assertThat(none.code()).isEqualTo(Code.UNRECOGNIZED);
        assertThat(none.detail()).isEqualTo("{\"status\":\"maintenance\"}");
    }

    @Test
    @DisplayName("Тот же код в другом регистре или склеенный со словом — не код: незнакомый ответ, показанный как есть")
    void otherCaseOrGluedIsNotTheCode() {
        DeliveryStatus lower = refused("[HTTP 200] error_reason_packet_not_found");
        assertThat(lower.code()).isEqualTo(Code.UNRECOGNIZED);
        assertThat(lower.detail()).isEqualTo("error_reason_packet_not_found");

        assertThat(refused("[HTTP 200] XERROR_REASON_PACKET_NOT_FOUND").code())
                .isEqualTo(Code.UNRECOGNIZED);
        assertThat(refused("[HTTP 200] {\"error\":\"Error_Reason_Auth_Failed\"}").code())
                .isEqualTo(Code.UNRECOGNIZED);
    }

    @Test
    @DisplayName("Пустое тело: при статусе не 200 — ошибка HTTP со статусом; при 200 — незнакомый ответ")
    void emptyBody() {
        DeliveryStatus bad = refused("[HTTP 503] ");
        assertThat(bad.code()).isEqualTo(Code.HTTP_ERROR);
        assertThat(bad.httpStatus()).isEqualTo(503);
        assertThat(bad.answerForm()).isEqualTo("EMPTY");
        assertThat(bad.detail()).isNull();

        DeliveryStatus odd = refused("[HTTP 200] ");
        assertThat(odd.code()).isEqualTo(Code.UNRECOGNIZED);
        assertThat(odd.answerForm()).isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("HTML-страница ошибки — ошибка HTTP со статусом и заголовком страницы, код с неё не берётся")
    void htmlErrorPage() {
        String page = """
                <!DOCTYPE html><html><head><title>502 Bad Gateway</title></head>
                <body><h1>502 Bad Gateway</h1><hr><center>nginx</center></body></html>""";
        DeliveryStatus gateway = refused("[HTTP 502] " + page);
        assertThat(gateway.code()).isEqualTo(Code.HTTP_ERROR);
        assertThat(gateway.httpStatus()).isEqualTo(502);
        assertThat(gateway.answerForm()).isEqualTo("PAGE");
        assertThat(gateway.detail()).isEqualTo("502 Bad Gateway");

        // Страница справки, где код упомянут текстом, — не отказ площадки с этим кодом.
        String help = "<html><title>Справка</title><body>Ответ ERROR_REASON_PACKET_NOT_FOUND значит…</body></html>";
        DeliveryStatus helpPage = refused("[HTTP 200] " + help);
        assertThat(helpPage.code()).isEqualTo(Code.UNRECOGNIZED);
        assertThat(helpPage.answerForm()).isEqualTo("PAGE");
        assertThat(helpPage.detail()).isEqualTo("Справка");
    }

    @Test
    @DisplayName("Сбой соединения распадается на срок, отказ в соединении, имя и обрыв; лимит — свой исход")
    void transportFailuresAreNamed() {
        assertThat(refused("[TIMEOUT] request timed out").code()).isEqualTo(Code.TIMEOUT);
        assertThat(refused("[CONNECT] Connection refused").code()).isEqualTo(Code.NO_CONNECTION);
        assertThat(refused("[DNS] UnresolvedAddressException").code()).isEqualTo(Code.HOST_UNKNOWN);
        DeliveryStatus broken = refused("[BROKEN] Connection reset");
        assertThat(broken.code()).isEqualTo(Code.CONNECTION_BROKEN);
        assertThat(broken.detail()).isEqualTo("Connection reset");
        assertThat(broken.httpStatus()).isNull();
        assertThat(refused("[TOO_BIG] дельта превысила лимит 5242880 байт").code())
                .isEqualTo(Code.TOO_BIG);
    }

    @Test
    @DisplayName("Запись до метки читается как ответ с неизвестным статусом: известный код узнаётся")
    void untaggedLegacyRow() {
        assertThat(refused("ERROR_REASON_PACKET_NOT_FOUND").code()).isEqualTo(Code.PACKET_NOT_FOUND);
        DeliveryStatus legacy = refused("Connection refused");
        assertThat(legacy.code()).isEqualTo(Code.UNRECOGNIZED);
        assertThat(legacy.httpStatus()).isNull();
        assertThat(legacy.detail()).isEqualTo("Connection refused");
    }

    @Test
    @DisplayName("Длинный незнакомый ответ показывается обрезанным, а не целиком")
    void longAnswerIsTrimmed() {
        DeliveryStatus odd = refused("[HTTP 200] " + "ж".repeat(500));
        assertThat(odd.detail()).hasSize(201).endsWith("…");
    }

    @Test
    @DisplayName("Успех — только 200 без следа отказа; страница и любое error_reason успехом не считаются")
    void acceptance() {
        assertThat(DromAnswer.accepted(200, "")).isTrue();
        assertThat(DromAnswer.accepted(200, "OK")).isTrue();
        assertThat(DromAnswer.accepted(200, "{\"result\":\"ok\"}")).isTrue();
        assertThat(DromAnswer.accepted(200, "ERROR_REASON_PACKET_NOT_FOUND")).isFalse();
        assertThat(DromAnswer.accepted(200, "error_reason_packet_not_found")).isFalse();
        assertThat(DromAnswer.accepted(200, "<!doctype html><html><title>Вход</title></html>")).isFalse();
        assertThat(DromAnswer.accepted(204, "")).isFalse();
    }
}
