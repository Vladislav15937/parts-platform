package ru.partsflow.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.partsflow.inventory.StockReservationRepository;

import java.util.stream.Collectors;

/**
 * Доменные нарушения превращаются в коды ответа.
 *
 * <p><b>Зачем это нужно именно офлайн-очереди.</b> Приёмка с телефона повторяет
 * неудачные запросы, и повторять надо только то, что имеет шанс: сеть отвалилась,
 * приложение перезапускается. Нарушение правила повторять бессмысленно — «пароль
 * короткий» или «донор списан» через час будут ровно теми же. Пока такие ошибки
 * приезжали как 500, очередь считала их временными и переотправляла вечно,
 * забивая себя одной битой записью.
 *
 * <p>Отсюда разделение: 400 — запрос неверен, 409 — состояние не позволяет,
 * 500 — сломались мы. Первые два очередь помечает «требует внимания», третье
 * повторяет.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Роль не позволяет операцию.
     *
     * <p>Обработчик обязателен и должен стоять раньше общего: перехватчик
     * контроллеров срабатывает прежде, чем Spring Security превратит
     * {@code AccessDeniedException} в 403, и без этого метода продавец, пытаясь
     * создать владельца, получал бы 500 — то есть «повторите позже» вместо
     * «вам нельзя».
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(
            org.springframework.security.access.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("Недостаточно прав"));
    }

    /** Неверные данные в запросе: количество, роль, пустое наименование. */
    /**
     * Ответ, который код выбрал сам.
     *
     * <p>Обязателен и стоит рано: перехватчик {@code Exception} иначе съедает
     * и {@code ResponseStatusException} тоже, превращая осознанный 404 в 500.
     * Ошибиться тут легко и заметно не сразу — сообщение об ошибке при этом
     * выглядит правдоподобно.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> chosenStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(new ApiError(e.getReason() == null ? "Запрос отклонён" : e.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }

    /**
     * Состояние не позволяет операцию: выданную сделку не отменяют, проведённый
     * документ не отменяют, разбор нельзя начать дважды. Повтор не поможет,
     * поэтому 409, а не 500.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    /**
     * Свободного остатка не хватило — деталь успел забрать другой продавец.
     * Это тоже конфликт состояния, и клиенту надо показать, а не повторять.
     */
    @ExceptionHandler(StockReservationRepository.InsufficientStockException.class)
    public ResponseEntity<ApiError> insufficientStock(
            StockReservationRepository.InsufficientStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.getMessage()));
    }

    /** Ошибки разбора тела запроса: собираем все поля сразу, а не по одному. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ApiError(message));
    }

    /**
     * Не хватает параметра запроса.
     *
     * <p>Это ошибка вызывающего, а не наша: без обработчика она попадала
     * в общий {@code Exception} и уезжала пятисоткой. Шаг развёртывания,
     * забывший передать секрет, видел «внутреннюю ошибку» и шёл искать
     * поломку в приложении — вместо «нет параметра token».
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("Не указан параметр запроса: " + e.getParameterName()));
    }

    /**
     * Всё остальное — наша поломка.
     *
     * <p>Наружу уходит общая формулировка: текст исключения может содержать
     * имена таблиц и фрагменты запросов, а это подсказки тому, кто ищет способ
     * забраться внутрь. Подробности остаются в логе.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Необработанная ошибка", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("Внутренняя ошибка"));
    }

    /** Тело ответа об ошибке. */
    public record ApiError(String message) {
    }
}
