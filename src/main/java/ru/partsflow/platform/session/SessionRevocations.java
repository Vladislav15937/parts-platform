package ru.partsflow.platform.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отозванные сессии: кого перестали пускать и с какого момента.
 *
 * <p><b>Зачем.</b> «Смена пароля, смена прав и блокировка сотрудника обязаны
 * убивать сессии — иначе понижённый в правах доработает смену со старыми»
 * (docs/sessions.md, §6; ответы владельца продукта от 9 сентября 2026).
 * До этого выключенный сотрудник продолжал работать до конца дня: {@code
 * TenantPrincipal} лежит в сессии снимком, и выключение его не касалось.
 *
 * <p><b>Почему в памяти, а не запросом в базу на каждый запрос.</b> Сессии
 * у нас живут в памяти приложения (docs/sessions.md, §1) — то есть ровно там
 * же, где эта отметка, и переживают они ровно одно и то же: перезапуск
 * выкидывает и сессии, и отметки, и после него отзывать некого. Проверка же
 * запросом в базу — это поход в базу на каждый клик каждого сотрудника ради
 * события, случающегося раз в месяц.
 *
 * <p><b>«Немедленно» здесь означает «на следующем запросе», и сильнее быть
 * не может.</b> Cookie-сессия наблюдаема только тогда, когда с ней приходят;
 * тот же порядок у {@code SessionRegistry} Spring Security с его
 * {@code expireNow()}. Разница лишь в том, что здесь не нужен реестр живых
 * сессий: сравнивается время входа с временем отзыва.
 *
 * <p><b>Цена названа: отзыв действует на том экземпляре приложения, где отметка
 * поставлена.</b> При сессиях в памяти это не ограничение, а тавтология —
 * сессия и так существует только на одном экземпляре, том, где человек вошёл.
 * Появится общее хранилище сессий (docs/sessions.md, §1) — отметка переедет
 * туда же, одной правкой.
 */
@Component
public class SessionRevocations {

    private final Map<String, Revocation> revoked = new ConcurrentHashMap<>();

    /**
     * Сколько держать отметку.
     *
     * <p>Сессия, которую не трогали дольше срока простоя, уже недействительна
     * сама по себе — отзывать нечего, и отметка о ней только занимает память.
     * Срок берётся тот же, что у сервлет-контейнера: разойдясь, они дали бы
     * либо живую сессию без отметки, либо вечный список.
     */
    private final Duration keepFor;

    public SessionRevocations(
            @Value("${server.servlet.session.timeout:30m}") Duration sessionTimeout) {
        // Запас на прореживание отметок активности и на неточность часов:
        // отметка обязана пережить самую живучую сессию, а не сравняться с ней.
        this.keepFor = sessionTimeout.plusMinutes(10);
    }

    /**
     * Отозвать сессии сотрудника.
     *
     * @param spareSessionKey сессия, из которой отзыв сделан: сотрудник,
     *                        сменивший свой пароль, остаётся работать.
     *                        {@code null} — отозвать все
     */
    public void revoke(String schema, long memberId, String spareSessionKey) {
        purgeExpired();
        revoked.put(id(schema, memberId), new Revocation(Instant.now(), spareSessionKey));
    }

    /**
     * Пускать ли эту сессию дальше.
     *
     * @param loggedInAt когда вошли. Сравнение по времени обязательно: без него
     *                   отметка убивала бы и те сессии, которыми человек вошёл
     *                   уже <b>после</b> отзыва — то есть смена пароля запирала
     *                   бы сотрудника навсегда
     */
    public boolean revoked(String schema, long memberId, String sessionKey, Instant loggedInAt) {
        Revocation revocation = revoked.get(id(schema, memberId));
        if (revocation == null || loggedInAt.isAfter(revocation.at())) {
            return false;
        }
        return !Objects.equals(revocation.spareSessionKey(), sessionKey);
    }

    private void purgeExpired() {
        Instant tooOld = Instant.now().minus(keepFor);
        revoked.values().removeIf(revocation -> revocation.at().isBefore(tooOld));
    }

    private static String id(String schema, long memberId) {
        return schema + '#' + memberId;
    }

    private record Revocation(Instant at, String spareSessionKey) {
    }
}
