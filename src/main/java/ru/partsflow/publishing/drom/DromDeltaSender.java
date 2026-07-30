package ru.partsflow.publishing.drom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Точечное обновление Дрома при продаже.
 *
 * <p>Полный прайс Дром забирает по ссылке раз в сутки, а деталь продаётся
 * сейчас. Сутки — это сутки звонков «а она у вас есть?» по позиции, которой
 * уже нет, и репутационная стоимость этого выше стоимости запроса. Поэтому
 * при выдаче сделки уходит дельта на несколько сотен байт.
 *
 * <p><b>Про идемпотентность.</b> Транспорт даёт at-least-once, и одно и то же
 * событие может прийти дважды. Здесь это безопасно по построению: дельта
 * содержит текущее состояние позиции, а не приращение. Повторная отправка
 * «эта деталь недоступна» ничего не портит — в отличие от, скажем, повторного
 * списания остатка. Дубликаты видны в {@code publication_log} и это нормально:
 * журнал отражает то, что реально уходило на площадку.
 *
 * <p><b>Кто это вызывает.</b> {@code DealIssuedHandler} — потребитель события
 * {@code deal.issued.v1}. Напрямую из кода продаж не вызывается намеренно:
 * сделка не должна знать про площадки.
 */
@Service
public class DromDeltaSender {

    private static final Logger log = LoggerFactory.getLogger(DromDeltaSender.class);

    private final JdbcTemplate jdbc;
    private final DromPriceGenerator priceGenerator;
    private final DromSyncClient syncClient;

    public DromDeltaSender(JdbcTemplate jdbc,
                           DromPriceGenerator priceGenerator,
                           DromSyncClient syncClient) {
        this.jdbc = jdbc;
        this.priceGenerator = priceGenerator;
        this.syncClient = syncClient;
    }

    /**
     * Отправляет на Дром состояние позиций выданной сделки.
     *
     * @return {@code false}, если отправить не удалось; причина — в
     *         {@code publication_log}. Исключение не бросается: сорванная
     *         выгрузка не повод откатывать выдачу товара клиенту
     */
    @Transactional
    public boolean onDealIssued(long dealId) {
        Optional<Account> account = activeAccount();
        if (account.isEmpty()) {
            // Клиент не подключал Дром — это не ошибка.
            return true;
        }

        List<Long> partIds = soldPartIds(dealId);
        if (partIds.isEmpty()) {
            return true;
        }

        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        int offers = priceGenerator.writeDelta(delta, partIds);
        if (offers == 0) {
            // Ни одна позиция сделки не публикуется на площадках — отправлять нечего.
            return true;
        }

        byte[] body = delta.toByteArray();
        if (body.length > syncClient.maxPacketBytes()) {
            // По документации недостижимо: одна позиция — сотни байт. Если всё же
            // случилось, ждать полного прайса по ссылке дешевле, чем падать.
            log.warn("Дром: дельта по сделке {} не влезла в лимит ({} байт), пропускаем — "
                    + "состояние приедет полным прайсом", dealId, body.length);
            logPublication(account.get().id(), 0, offers, false,
                    "дельта превысила лимит " + syncClient.maxPacketBytes());
            return false;
        }

        long startedAt = System.currentTimeMillis();
        DromSyncClient.Result result = syncClient.sync(
                account.get().packetId(),
                DromSyncClient.authHash(account.get().cabinetKey()),
                body,
                "delta-deal-%d.xml".formatted(dealId));
        int durationMs = (int) (System.currentTimeMillis() - startedAt);

        logPublication(account.get().id(), result.httpStatus(), offers, result.success(),
                result.success() ? null : result.body());
        markSynced(account.get().id(), partIds, result.success());
        updateAccountState(account.get().id(), result);

        if (!result.success()) {
            log.warn("Дром: дельта по сделке {} не ушла, HTTP {} за {} мс",
                    dealId, result.httpStatus(), durationMs);
        }
        return result.success();
    }

    /**
     * Активный аккаунт Дрома арендатора.
     *
     * <p><b>Ключ читается как есть.</b> Схема обещает, что {@code credentials}
     * шифруются приложением, но шифрования в проекте пока нет вообще. До того как
     * туда попадёт ключ реального клиента, это надо закрыть: дамп базы сейчас
     * даёт доступ к его кабинету на Дроме.
     */
    private Optional<Account> activeAccount() {
        List<Account> found = jdbc.query("""
                SELECT id, settings ->> 'packetId' AS packet_id, credentials
                  FROM marketplace_account
                 WHERE marketplace = 'DROM' AND status = 'ACTIVE'
                 ORDER BY id
                 LIMIT 1""",
                (rs, i) -> {
                    byte[] credentials = rs.getBytes("credentials");
                    return new Account(
                            rs.getLong("id"),
                            rs.getString("packet_id"),
                            credentials == null ? null : new String(credentials, StandardCharsets.UTF_8));
                });

        if (found.isEmpty()) {
            return Optional.empty();
        }
        Account account = found.get(0);
        if (account.packetId() == null || account.cabinetKey() == null) {
            // Аккаунт создан, но не донастроен: без packetId и ключа запрос
            // всё равно вернёт ERROR_REASON_EMPTY_REQUEST.
            log.warn("Дром: аккаунт {} активен, но не заполнены packetId или ключ", account.id());
            return Optional.empty();
        }
        return Optional.of(account);
    }

    /** Позиции, ушедшие клиенту: только они меняют доступность на площадке. */
    private List<Long> soldPartIds(long dealId) {
        return jdbc.queryForList("""
                SELECT part_id FROM deal_item
                 WHERE deal_id = ? AND status = 'ISSUED'
                 ORDER BY part_id""", Long.class, dealId);
    }

    private void logPublication(long accountId, int httpStatus, int itemCount,
                                boolean success, String error) {
        jdbc.update("""
                INSERT INTO publication_log
                    (account_id, operation, http_status, item_count, is_success, response_body)
                VALUES (?, 'SYNC', ?, ?, ?, ?)""",
                accountId, httpStatus, itemCount, success, error);
    }

    private void markSynced(long accountId, List<Long> partIds, boolean success) {
        if (!success) {
            return;
        }
        for (Long partId : partIds) {
            // Объявление на Дроме не удаляется, а помечается недоступным,
            // поэтому статус listing не меняется — обновляется только момент
            // последней синхронизации.
            jdbc.update("""
                    UPDATE listing SET last_synced_at = now()
                     WHERE part_id = ? AND account_id = ?""", partId, accountId);
        }
    }

    private void updateAccountState(long accountId, DromSyncClient.Result result) {
        jdbc.update("""
                UPDATE marketplace_account
                   SET last_sync_at = now(), last_error = ?
                 WHERE id = ?""",
                result.success() ? null : result.body(), accountId);
    }

    private record Account(long id, String packetId, String cabinetKey) {
    }
}
