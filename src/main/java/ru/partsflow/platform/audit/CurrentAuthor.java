package ru.partsflow.platform.audit;

import ru.partsflow.platform.security.CurrentUser;

/**
 * Автор правки для журнала изменений.
 *
 * <p>Отдельный класс, а не прямой вызов {@code CurrentUser} из слушателя:
 * слушатель Hibernate живёт в платформенном слое и о безопасности знать
 * не должен, а вошедшего в фоновых задачах нет и быть не может — пусто там
 * законное значение, и это надо было где-то написать.
 *
 * <p>До 4 августа 2026 то же самое делал {@code current_setting('app.user_id')},
 * который выставлялся при выдаче соединения и сбрасывался при возврате.
 * Настройка сессии осталась — её читают ручные запросы в psql и она стоит
 * дёшево, — но журнал теперь берёт автора отсюда.
 */
final class CurrentAuthor {

    private CurrentAuthor() {
    }

    /** @return идентификатор вошедшего либо {@code null} — фоновая задача */
    static Long get() {
        return CurrentUser.memberId();
    }
}
