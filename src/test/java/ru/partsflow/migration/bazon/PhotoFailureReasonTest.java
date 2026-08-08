package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Отказ переноса снимка называет причину.
 *
 * <p><b>Зачем.</b> Рядом с «не вышло» стоит кнопка «вернуть в очередь»,
 * и решение нажимать её или нет целиком зависит от причины: лежавший CDN
 * чинится повтором, удалённый файл — нет, а закрытая наружу сеть означает,
 * что чинить надо не здесь. Пока причина бралась из {@code getMessage()},
 * такого решения принять было нельзя.
 *
 * <p>Поймано прогоном подключения: CDN прежней системы перестал отвечать
 * на обоих портах, и все двести заданий пачки записали в журнал
 * «неизвестная ошибка» — то есть перенос отчитался ровно ничем, при том
 * что причина известна и называется одним словом.
 */
class PhotoFailureReasonTest {

    @Test
    @DisplayName("отказ соединения не превращается в «неизвестную ошибку»")
    void connectionRefusedIsNamed() {
        // У ConnectException из HttpClient сообщения может не быть вовсе —
        // ровно так и было на живом прогоне.
        String reason = PhotoMigration.reason(new ConnectException());

        assertThat(reason)
                .as("причина отказа не названа — оператору нечем решить, повторять ли")
                .isNotBlank()
                .doesNotContain("неизвестная")
                .contains("не отвечает");
    }

    @Test
    @DisplayName("таймаут и неизвестное имя различимы между собой")
    void otherNetworkFailuresAreDistinguishable() {
        assertThat(PhotoMigration.reason(new HttpConnectTimeoutException("")))
                .contains("не отвечает");
        assertThat(PhotoMigration.reason(new UnknownHostException("export-content.baz-on.ru")))
                .contains("не найден по имени")
                .contains("export-content.baz-on.ru");
    }

    @Test
    @DisplayName("осмысленное сообщение уходит как есть")
    void ownMessageSurvives() {
        // Ответ 404 и пустой файл объясняют себя сами — их текст сильнее
        // любой нашей общей формулировки.
        assertThat(PhotoMigration.reason(new IllegalStateException("ответ 404")))
                .isEqualTo("ответ 404");
    }

    @Test
    @DisplayName("пустое сообщение заменяется типом, а не молчанием")
    void blankMessageFallsBackToType() {
        assertThat(PhotoMigration.reason(new IllegalStateException("   ")))
                .isEqualTo("IllegalStateException");
    }
}
