package ru.partsflow.platform.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * Отказ в праве, который называет причину словами — 403, но не «Недостаточно
 * прав». Обычный {@link AccessDeniedException} уходит наружу общей фразой
 * намеренно: отказ {@code @PreAuthorize} по пути ничего, кроме «нельзя», сказать
 * не может. А «выдать можно только свои права» (задача 0044) — правило, и без
 * названных прав человек не поймёт, что снять, чтобы сохранить.
 */
public class RightsRefusal extends AccessDeniedException {

    public RightsRefusal(String message) {
        super(message);
    }
}
