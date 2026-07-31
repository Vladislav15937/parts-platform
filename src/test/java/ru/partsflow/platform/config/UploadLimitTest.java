package ru.partsflow.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Слишком большой файл.
 *
 * <p>Поймано прогоном переноса на настоящем объёме: выгрузка клиента
 * на 35 841 позицию весит 24,7 МБ, а умолчание Spring — мегабайт. Перенос
 * падал за сотую долю секунды с ответом «внутренняя ошибка», по которому
 * владелец не мог понять ничего — ни что дело в размере, ни каков предел.
 *
 * <p>Без контекста Spring намеренно: проверяется отображение исключения
 * на ответ, а поднимать ради этого ещё один контекст с ещё одним пулом
 * соединений — та самая плата, о которой сказано в CLAUDE.md.
 */
class UploadLimitTest {

    @Test
    @DisplayName("Файл больше предела — 413 с названным пределом, а не 500")
    void tooLargeUploadIsNamed() {
        var response = new ApiExceptionHandler()
                .tooLarge(new MaxUploadSizeExceededException(1024));

        // 500 отправил бы владельца искать поломку сервера, которой нет.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        // Предел назван: без него следующий шаг непонятен — резать файл
        // пополам или звонить.
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .as("в ответе нет предела — владелец не знает, что делать дальше")
                .contains("64");
    }
}
