package ru.partsflow.publishing.drom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.crypto.SecretCipher;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Точечное обновление Дрома: изменилась позиция — уехала дельта.
 *
 * <p>Полный прайс Дром забирает по ссылке раз в сутки, а деталь продаётся,
 * дорожает и снимается с публикации сейчас. Сутки — это сутки звонков «а она
 * у вас есть?» по позиции, которой уже нет, и репутационная стоимость этого
 * выше стоимости запроса. Поэтому на каждое изменение уходит дельта
 * на несколько сотен байт.
 *
 * <p><b>Дельта уходит во все выгрузки площадки, а не в первую попавшуюся.</b>
 * Прайс-листов на Дром у клиента несколько — у живого их пять, разложенных
 * по ценовым диапазонам, — и у каждого свой отбор. Позиция, подорожавшая
 * с двух тысяч до двадцати, покидает один прайс-лист и появляется в другом;
 * пока отправитель брал {@code LIMIT 1} и слал всё с отбором «всё подряд»,
 * оба прайса оставались неправдой, а деталь могла уехать в тот, в котором
 * её быть не должно.
 *
 * <p><b>Про идемпотентность.</b> Дельта содержит текущее состояние позиции,
 * а не приращение, поэтому повтор безопасен по построению — в отличие от,
 * скажем, повторного списания остатка. Дубликаты видны
 * в {@code publication_log} и это нормально: журнал отражает то, что реально
 * уходило на площадку.
 *
 * <p><b>Кто это вызывает.</b> {@link FeedDeltaRelay} — по очереди изменений,
 * которую наполняет триггер, и {@code DealIssuedHandler} — сразу по выдаче
 * сделки, не дожидаясь очередного захода релея. Напрямую из кода продаж
 * не вызывается намеренно: сделка не должна знать про площадки.
 */
@Service
public class DromDeltaSender {

    private static final Logger log = LoggerFactory.getLogger(DromDeltaSender.class);

    private final JdbcTemplate jdbc;
    private final DromAccountReader accounts;
    private final DromPriceGenerator priceGenerator;
    private final DromSyncClient syncClient;
    private final SecretCipher cipher;

    public DromDeltaSender(JdbcTemplate jdbc,
                           DromAccountReader accounts,
                           DromPriceGenerator priceGenerator,
                           DromSyncClient syncClient,
                           SecretCipher cipher) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.priceGenerator = priceGenerator;
        this.syncClient = syncClient;
        this.cipher = cipher;
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
        List<Long> partIds = soldPartIds(dealId);
        // Момент до отправки: снимать отметку можно только с того, что после
        // неё не менялось. Внутри транзакции now() — это её начало, то есть
        // заведомо раньше запроса к площадке.
        Timestamp startedAt = jdbc.queryForObject("SELECT now()", Timestamp.class);

        boolean sent = sendParts(partIds, "deal-%d".formatted(dealId));
        if (sent) {
            // Продажу отмечает и триггер очереди — движение склада меняет
            // остаток и статус позиции. Без уборки релей через несколько
            // секунд отправил бы то же самое второй раз.
            clearDirty(partIds, startedAt);
        }
        return sent;
    }

    private void clearDirty(List<Long> partIds, Timestamp before) {
        if (partIds.isEmpty() || before == null) {
            return;
        }
        String places = String.join(",", java.util.Collections.nCopies(partIds.size(), "?"));
        Object[] args = new Object[partIds.size() + 1];
        for (int i = 0; i < partIds.size(); i++) {
            args[i] = partIds.get(i);
        }
        args[partIds.size()] = before;
        jdbc.update("DELETE FROM feed_dirty WHERE part_id IN (" + places + ") AND marked_at <= ?",
                args);
    }

    /**
     * Отправляет текущее состояние перечисленных позиций во все выгрузки.
     *
     * @param label чем помечен файл дельты — попадает в имя вложения и
     *              оттуда в «Замечания к товарам» кабинета площадки
     * @return {@code false}, если хотя бы одна выгрузка не приняла дельту;
     *         тогда отметки об изменении снимать нельзя — позиция должна
     *         уехать следующим заходом
     */
    @Transactional
    public boolean sendParts(List<Long> partIds, String label) {
        if (partIds == null || partIds.isEmpty()) {
            return true;
        }

        List<Ready> targets = readyAccounts();
        if (targets.isEmpty()) {
            // Дром не подключён или прайс-лист ещё не завёл его специалист.
            // Это не ошибка: полный прайс по ссылке и так отдаёт актуальное.
            return true;
        }

        boolean allSent = true;
        for (Ready target : targets) {
            allSent &= sendToAccount(target, partIds, label);
        }
        return allSent;
    }

    private boolean sendToAccount(Ready target, List<Long> partIds, String label) {
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        int offers = priceGenerator.writeDelta(delta, partIds, target.account().filter());
        if (offers == 0) {
            // Ни одна позиция не проходит отбор этой выгрузки — слать нечего.
            // Обычный случай: пять прайс-листов по ценовым диапазонам, деталь
            // попадает в один. И сюда же попадают колёса: у прайса запчастей
            // свой вид товара, а выгрузки для шин и дисков у нас пока нет.
            return true;
        }

        byte[] body = delta.toByteArray();
        if (body.length > syncClient.maxPacketBytes()) {
            // Пачку режет релей, но отбор выгрузки может оказаться шире
            // ожидаемого. Ждать полного прайса по ссылке дешевле, чем падать.
            log.warn("Дром: дельта {} для выгрузки {} не влезла в лимит ({} байт) — "
                            + "состояние приедет полным прайсом",
                    label, target.account().id(), body.length);
            logPublication(target.account().id(), 0, offers, false,
                    "дельта превысила лимит " + syncClient.maxPacketBytes());
            return false;
        }

        DromSyncClient.Result result = syncClient.sync(
                target.account().packetId(),
                DromSyncClient.authHash(target.cabinetKey()),
                body,
                "delta-%s.xml".formatted(label));

        logPublication(target.account().id(), result.httpStatus(), offers, result.success(),
                result.success() ? null : result.body());
        markSynced(target.account().id(), partIds, result.success());
        updateAccountState(target.account().id(), result);

        if (!result.success()) {
            log.warn("Дром: дельта {} в выгрузку {} не ушла, HTTP {}",
                    label, target.account().id(), result.httpStatus());
        }
        return result.success();
    }

    /**
     * Выгрузки, которым есть куда и чем слать.
     *
     * <p>Ключ расшифровывается {@link SecretCipher}. Не расшифровался — значит
     * ключ шифрования не тот или данные испорчены; молча уехать с мусором
     * вместо ключа хуже, чем не уехать вовсе.
     */
    private List<Ready> readyAccounts() {
        List<Ready> ready = new ArrayList<>();
        for (DromAccountReader.Account account : accounts.active(null)) {
            if (!account.canReceiveDelta()) {
                // Кабинет заведён, но прайс-лист на стороне площадки ещё
                // не настроен: без packetId и ключа запрос всё равно вернёт
                // ERROR_REASON_EMPTY_REQUEST. Пишем в debug, а не в warn:
                // до подключения это нормальное состояние, и warn каждые
                // несколько секунд превращает лог в шум.
                log.debug("Дром: у выгрузки {} нет packetId или ключа — дельты не шлём",
                        account.id());
                continue;
            }
            String cabinetKey;
            try {
                cabinetKey = cipher.decrypt(account.credentials());
            } catch (RuntimeException e) {
                log.error("Дром: ключ кабинета {} не расшифровывается — выгрузка невозможна",
                        account.id(), e);
                continue;
            }
            if (cabinetKey == null) {
                continue;
            }
            ready.add(new Ready(account, cabinetKey));
        }
        return ready;
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

    /** Выгрузка с уже расшифрованным ключом: расшифровка делается один раз. */
    private record Ready(DromAccountReader.Account account, String cabinetKey) {
    }
}
