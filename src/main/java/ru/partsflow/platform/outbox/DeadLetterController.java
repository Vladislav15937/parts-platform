package ru.partsflow.platform.outbox;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.time.Instant;
import java.util.List;

/**
 * Разбор того, что не доехало.
 *
 * <p>Событие, которое обработчик не принял, до этого ложилось в таблицу
 * и там оставалось: достать его можно было только запросом в базу. На языке
 * владельца это значит «сделка выдана, а объявление на площадке висит
 * доступным» — и узнавал он об этом от клиента, приехавшего за проданным.
 *
 * <p>Роль — владелец или менеджер: повтор отправляет данные на площадку,
 * а снятие с разбора закрывает вопрос без отправки. Ни то, ни другое
 * не работа приёмщика.
 */
@RestController
@RequestMapping("/api/events/dead-letters")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class DeadLetterController {

    /** Больше полусотни строк разбора — это уже не разбор, а авария. */
    private static final int LIMIT = 50;

    private final DeadLetterService deadLetters;

    public DeadLetterController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public Page list() {
        return new Page(deadLetters.unresolved(LIMIT).stream().map(View::of).toList(),
                deadLetters.unresolvedCount());
    }

    /**
     * Повтор по кнопке.
     *
     * <p>Отвечает 409, если снова не вышло: для клиента это не «сервер сломался»,
     * а «причина не устранена», и различать эти два случая он обязан — иначе
     * экран покажет «отправлено» там, где ничего не отправилось.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Result> retry(@PathVariable long id) {
        return deadLetters.retry(id)
                .map(error -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new Result(false, error)))
                .orElseGet(() -> ResponseEntity.ok(new Result(true, null)));
    }

    /** Снять с разбора без доставки: событие потеряло смысл. */
    @PostMapping("/{id}/discard")
    public Result discard(@PathVariable long id,
                          @RequestParam(required = false) String reason) {
        deadLetters.discard(id, CurrentUser.memberId());
        return new Result(true, reason);
    }

    public record Page(List<View> items, long total) {
    }

    /**
     * @param needsAttention робот отступился, дальше решает человек
     * @param nextAttemptAt когда робот попробует снова; в прошлом — значит
     *                      попробует ближайшим заходом
     */
    public record View(long id, String handler, long eventId, String eventType,
                       String aggregateType, long aggregateId, String error,
                       int attempts, boolean needsAttention,
                       Instant createdAt, Instant nextAttemptAt) {

        static View of(DeadLetterService.DeadLetter letter) {
            return new View(letter.id(), letter.handler(), letter.eventId(),
                    letter.eventType(), letter.aggregateType(), letter.aggregateId(),
                    letter.error(), letter.attempts(), letter.needsAttention(),
                    letter.createdAt(), letter.nextAttemptAt());
        }
    }

    /**
     * @param message причина отказа. Названо так же, как поле в общем ответе
     *                об ошибке: клиент достаёт текст из одного места, а не
     *                гадает, какой контракт ему приехал
     */
    public record Result(boolean delivered, String message) {
    }
}
