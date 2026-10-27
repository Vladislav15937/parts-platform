package ru.partsflow.publishing.drom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.crypto.SecretCipher;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Точечное обновление Дрома: изменилась позиция — уехала дельта.
 *
 * <p>Полный прайс Дром забирает по ссылке раз в трое суток (раз в сутки —
 * только при платном позиционировании), а деталь продаётся, дорожает
 * и снимается с публикации сейчас. Трое суток — это трое суток звонков
 * «а она у вас есть?» по позиции, которой уже нет, и репутационная стоимость
 * этого выше стоимости запроса. Поэтому на каждое изменение уходит дельта
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
 * которую наполняет {@link ru.partsflow.inventory.PartChangeLog},
 * и {@code DealIssuedHandler} — сразу по выдаче
 * сделки, не дожидаясь очередного захода релея. Напрямую из кода продаж
 * не вызывается намеренно: сделка не должна знать про площадки.
 *
 * <p><b>Отказ, который без человека не пройдёт, очередь не держит</b> (задача
 * 0269, решение владельца продукта 12 октября 2026 — вариант «б», «не копить
 * и не спамить Дром повторами»). Прайс-лист, ещё не подключённый поддержкой
 * к API, или неверный ключ — это состояние дней, а не минуты. Пока такой отказ
 * снимал заявку со всей пачки, позиции каждые пятнадцать секунд уходили
 * заново во <b>все</b> выгрузки, включая принявшие, а отказавшая получала
 * запрос четыре раза в минуту. Теперь она выпадает из отправки так же, как
 * выгрузка без ключа: отметки снимаются, как только приняли остальные, а её
 * пробуют снова не раньше {@code app.feeds.refused-retry} — первой же пачкой
 * после интервала. Накопленное за это время она получит полным забором прайса.
 *
 * <p><b>Временный сбой и непонятный ответ тоже пробуются раз в интервал</b>
 * (задача 0380). Пятисотка, тишина, обрыв, незнакомый код и ответ, который
 * не узнан ни успехом, ни отказом (веб-страница при 200), — «может пройти
 * само», но пока не прошло, каждый заход релея повторял пачку во <b>все</b>
 * выгрузки, и конца у этого не было. Теперь такая выгрузка ждёт тот же
 * {@code app.feeds.refused-retry}, а пачку держит в очереди, только если
 * изменение не досталось <b>никому</b>: приняла хоть одна — отметки снимаются,
 * как при отказе «нужен человек», а сбойной ставится признак неотправки.
 *
 * <p>Интервал не хранится отдельно: его отсчитывают от {@code last_sync_at}
 * и {@code last_error} самой выгрузки, которые и так пишутся на каждую
 * попытку. Поэтому он переживает перезапуск и одинаков у двух экземпляров,
 * а замена ключа или номера сбрасывает его тем же {@code forgetLastOutcome},
 * что забывает прежний исход на карточке.
 */
@Service
public class DromDeltaSender {

    private static final Logger log = LoggerFactory.getLogger(DromDeltaSender.class);

    private final JdbcTemplate jdbc;
    private final DromAccountReader accounts;
    private final DromPriceGenerator priceGenerator;
    private final DromWheelGenerator wheelGenerator;
    private final DromSyncClient syncClient;
    private final SecretCipher cipher;
    private final Duration refusedRetry;

    /**
     * Внешний адрес приложения — из него строятся ссылки на снимки в дельте,
     * те же, что в полном прайсе ({@code DromFeedController}). Запроса здесь
     * нет, поэтому и запасного пути «из запроса», как у выдачи прайса, нет:
     * без настройки (только в разработке — в боевом compose {@code PUBLIC_ORIGIN}
     * обязателен) дельта уходит без ссылок.
     */
    private final String publicUrl;

    public DromDeltaSender(JdbcTemplate jdbc,
                           DromAccountReader accounts,
                           DromPriceGenerator priceGenerator,
                           DromWheelGenerator wheelGenerator,
                           DromSyncClient syncClient,
                           SecretCipher cipher,
                           @Value("${app.feeds.refused-retry:15m}") Duration refusedRetry,
                           @Value("${app.public-url:}") String publicUrl) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.priceGenerator = priceGenerator;
        this.wheelGenerator = wheelGenerator;
        this.syncClient = syncClient;
        this.cipher = cipher;
        this.refusedRetry = refusedRetry;
        this.publicUrl = publicUrl;
    }

    /** Чем кончилась отправка в одну выгрузку. */
    private enum Outcome {
        /** Площадка приняла. */
        ACCEPTED,
        /** Ни одна позиция не проходит отбор выгрузки — слать нечего. */
        NOTHING_TO_SEND,
        /**
         * Отказ, который без человека не пройдёт ({@link DromSyncRecord#NEEDS_PERSON}).
         * Очередь не держит: изменения этой выгрузке не копятся (вариант «б»).
         */
        NEEDS_PERSON,
        /**
         * Выгрузка ждёт интервала после отказа «нужен человек»: не пробуем,
         * изменения ей не достаются — это неотправка (задача 0271).
         */
        RESTING,
        /**
         * Выгрузка ждёт интервала после временного сбоя или непонятного ответа
         * (задача 0380): не пробуем. Как {@link #FAILED}, держит очередь, если
         * изменение не досталось никому, — иначе прошедший сам сбой потерял бы
         * его до полного забора.
         */
        RESTING_AFTER_FAILURE,
        /**
         * Дельта сверх лимита. Очередь не держит (задача 0271, по решению
         * «б» из 0269): пачка и её размер на следующем заходе те же, и повтор
         * не уйдёт никогда, а тащил бы её во все исправные выгрузки.
         */
        TOO_BIG,
        /**
         * Временный сбой (5xx, тишина, обрыв, незнакомый ответ): очередь
         * держит, только если изменение не приняла ни одна выгрузка, и
         * пробуется не раньше интервала (задача 0380). Признак неотправки ставится
         * и здесь (ответ владельца 26.10.2026, tasks/0271, «Вопрос владельцу —
         * временный сбой и признак»): эта попытка не дошла, а гасит признак
         * только полный забор прайса позже неё.
         */
        FAILED
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
            // Продажу отмечает и очередь изменений — SalesService кладёт
            // отметку на каждую списанную позицию. Без уборки релей через
            // несколько секунд отправил бы то же самое второй раз.
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
        jdbc.update("DELETE FROM part_change WHERE part_id IN (" + places + ") AND marked_at <= ?",
                args);
    }

    /**
     * Отправляет текущее состояние перечисленных позиций во все выгрузки.
     *
     * @param label чем помечен файл дельты — попадает в имя вложения и
     *              оттуда в «Замечания к товарам» кабинета площадки
     * @return {@code false}, если изменение не приняла ни одна выгрузка, а хоть
     *         одной помешал сбой, который может пройти сам (сейчас или интервал
     *         после него); тогда отметки снимать нельзя — позиция уйдёт пробой
     *         после интервала (задача 0380). Приняла хоть одна — {@code true}:
     *         держать очередь значило бы повторять пачку в принявшие каждым
     *         заходом. Отказ, которому нужен человек, выгрузка, ждущая интервала
     *         после такого отказа, и дельта сверх лимита результат не портят —
     *         как и выгрузка без ключа. Каждая неотправка ставит выгрузке
     *         признак (задача 0271)
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

        Map<Long, Rest> resting = resting();
        String origin = feedOrigin();
        boolean delivered = false;
        boolean mayPassByItself = false;
        for (Ready target : targets) {
            Outcome outcome = sendToAccount(target, partIds, label, origin,
                    resting.get(target.account().id()));
            delivered |= outcome == Outcome.ACCEPTED;
            mayPassByItself |= outcome == Outcome.FAILED || outcome == Outcome.RESTING_AFTER_FAILURE;
        }
        // Держать очередь ради сбойной выгрузки, когда исправная изменение
        // приняла, значит слать его исправной заново каждым заходом (0380).
        // Сбойной оно не достанется до полного забора — это помнит признак.
        return delivered || !mayPassByItself;
    }

    /** Почему выгрузка ждёт интервала. */
    private enum Rest {
        /** Отказ, который без человека не пройдёт (0269). */
        NEEDS_PERSON,
        /** Временный сбой или ответ, не узнанный ни успехом, ни отказом (0380). */
        AFTER_FAILURE
    }

    /**
     * Выгрузки, чья последняя попытка не прошла меньше интервала назад.
     *
     * <p>Часы — базы ({@code now()}), как и у {@code last_sync_at}: сравнивать
     * время записи с часами приложения значило бы сдвинуть интервал на их
     * расхождение. Род отказа решает {@link DromSyncRecord#needsPerson} по
     * тексту, то есть тем же разбором, что и карточка.
     *
     * <p><b>Дельта сверх лимита выгрузку не усыпляет.</b> Запрос к Дрому тогда
     * не уходил, и стучаться не во что: велика была пачка, а не выгрузка
     * больна. Усни она на интервал — следующие малые дельты, которые Дром
     * принял бы, ждали бы четверть часа ни за что.
     */
    private Map<Long, Rest> resting() {
        Map<Long, Rest> resting = new HashMap<>();
        jdbc.query("""
                SELECT id, last_error FROM marketplace_account
                 WHERE last_error IS NOT NULL
                   AND last_sync_at > now() - ?::interval""",
                rs -> {
                    String lastError = rs.getString("last_error");
                    if (DromSyncRecord.needsPerson(lastError)) {
                        resting.put(rs.getLong("id"), Rest.NEEDS_PERSON);
                    } else if (DromSyncRecord.read(lastError).kind() != DromSyncRecord.Kind.TOO_BIG) {
                        resting.put(rs.getLong("id"), Rest.AFTER_FAILURE);
                    }
                },
                refusedRetry.toSeconds() + " seconds");
        return resting;
    }

    /**
     * Начало адреса выдачи этой компании — {@code https://…/feeds/drom/<код>/};
     * {@code null}, если внешний адрес не задан.
     *
     * <p>Код компании берётся из реестра по текущей схеме, как у ссылки
     * на прайс ({@code MarketplaceAccountService.feedPath}): подставленный
     * откуда-то ещё, он дал бы ссылки на снимки чужого арендатора. Метод
     * зовётся внутри {@code @Transactional sendParts}: без транзакции
     * {@code current_schema()} ответил бы {@code public}.
     */
    private String feedOrigin() {
        if (publicUrl == null || publicUrl.isBlank()) {
            return null;
        }
        // По схеме самой транзакции, а не по TenantContext: ссылки обязаны
        // принадлежать тому же арендатору, чьи позиции читает дельта, а их
        // читает search_path этого соединения.
        List<String> company = jdbc.queryForList(
                "SELECT code FROM public.tenant_registry WHERE schema_name = current_schema()",
                String.class);
        if (company.isEmpty() || company.get(0) == null) {
            // Без записи в реестре нет и адреса прайса — ссылкам вести некуда.
            // Дельта всё равно уходит: цена и наличие важнее снимков, которые
            // площадка и так получит полным прайсом.
            log.warn("Дром: у схемы арендатора нет кода компании в реестре — дельта уходит без снимков");
            return null;
        }
        return "%s/feeds/drom/%s/".formatted(publicUrl.replaceFirst("/+$", ""), company.get(0));
    }

    /** Адрес выдачи снимков выгрузки — тот же, что прописан в её полном прайсе. */
    private static String photoBase(String origin, DromAccountReader.Account account, String kind) {
        if (origin == null || account.feedToken() == null) {
            return null;
        }
        return origin + account.feedToken() + "/" + kind + "/";
    }

    /**
     * @param resting почему выгрузка ждёт интервала, или {@code null}, если
     *                не ждёт: дельта собирается только затем, чтобы узнать,
     *                досталось ли бы ей что-нибудь, — и не отправляется
     */
    private Outcome sendToAccount(Ready target, List<Long> partIds, String label, String origin,
                                  Rest resting) {
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        // Выгрузка колёс собирается своим генератором: у шины свои поля,
        // и дельта обязана быть в том же формате, что и полный прайс —
        // площадка разбирает её той же настройкой.
        // Настройками той же выгрузки, что и полный прайс. Дельта несёт
        // текущее состояние и перебивает то, что стоит на площадке: уйдя
        // без наценки, она вернула бы объявлению складскую цену через
        // несколько секунд после того, как прайс поставил цену с наценкой.
        int offers = target.account().isWheelFeed()
                ? wheelGenerator.writeDelta(delta, partIds, target.account().filter(),
                        target.account().settings(),
                        photoBase(origin, target.account(), "photo"))
                // Со снимками, как полный прайс: в формате Bazon пустой
                // <photoUrls> значит «снимков нет», а не «не трогать» (0333).
                // Адреса те же, что у выдачи прайса этой выгрузки, — по её токену.
                : priceGenerator.writeDelta(delta, partIds, target.account().filter(),
                        target.account().settings(),
                        photoBase(origin, target.account(), "photo"),
                        photoBase(origin, target.account(), "donor-photo"));
        if (offers == 0) {
            // Ни одна позиция не проходит отбор этой выгрузки — слать нечего.
            // Обычный случай: пять прайс-листов по ценовым диапазонам, деталь
            // попадает в один. И сюда же попадает фара, отправленная
            // в колёсную выгрузку, и шина — в выгрузку запчастей.
            return Outcome.NOTHING_TO_SEND;
        }

        if (resting != null) {
            // Последняя попытка не прошла меньше интервала назад: не стучимся.
            // Обработчик выдачи сделки идёт сюда же и мимо интервала не шлёт.
            // Отметки с этих позиций снимутся, как только примут остальные, —
            // значит этой выгрузке они не достались, и карточка обязана это
            // помнить (задача 0271). Причина — та самая неудача.
            log.debug("Дром: выгрузка {} ждёт интервала после неудачной попытки — дельту не шлём",
                    target.account().id());
            markUnsentByLastRefusal(target.account().id(), partIds);
            return resting == Rest.NEEDS_PERSON ? Outcome.RESTING : Outcome.RESTING_AFTER_FAILURE;
        }

        byte[] body = delta.toByteArray();
        if (body.length > syncClient.maxPacketBytes()) {
            // Пачку режет релей, но отбор выгрузки может оказаться шире
            // ожидаемого. Ждать полного прайса по ссылке дешевле, чем падать.
            log.warn("Дром: дельта {} для выгрузки {} не влезла в лимит ({} байт) — "
                            + "состояние приедет полным прайсом",
                    label, target.account().id(), body.length);
            String tooBig = DromSyncRecord.tooBig(syncClient.maxPacketBytes());
            logPublication(target.account().id(), 0, offers, false, tooBig);
            // Попытка была, и карточка должна её показать: без этого выгрузка
            // с дельтой-великаном выглядела бы «ещё не отправляли».
            updateAccountState(target.account().id(), false, tooBig);
            markUnsent(target.account().id(), tooBig);
            return Outcome.TOO_BIG;
        }

        DromSyncClient.Result result = syncClient.sync(
                target.account().packetId(),
                DromSyncClient.authHash(target.cabinetKey()),
                body,
                "delta-%s.%s".formatted(label, target.account().isWheelFeed() ? "csv" : "xml"));

        logPublication(target.account().id(), result.httpStatus(), offers, result.success(),
                result.success() ? null : result.body());
        markSynced(target.account().id(), partIds, result.success());
        String failure = DromSyncRecord.of(result);
        updateAccountState(target.account().id(), result.success(), failure);

        if (result.success()) {
            return Outcome.ACCEPTED;
        }
        if (DromSyncRecord.needsPerson(failure)) {
            // Отметки снимутся без отправки сюда (0269, «б») — неотправка.
            markUnsent(target.account().id(), failure);
            log.warn("Дром: выгрузка {} отказала так, что без человека не пройдёт (HTTP {}) — "
                            + "следующая проба не раньше чем через {}",
                    target.account().id(), result.httpStatus(), refusedRetry);
            return Outcome.NEEDS_PERSON;
        }
        // Временный сбой или непонятный ответ: следующая проба — не раньше
        // интервала (0380), очередь держится, только если не принял никто.
        // Признак ставится и на него — «любая неотправка» (ответ владельца
        // 26.10.2026, tasks/0271): дошёл ли повтор, карточка не утверждает,
        // а гасит признак только полный забор прайса позже этой попытки
        // (ответ владельца 27.10.2026, там же).
        markUnsent(target.account().id(), failure);
        log.warn("Дром: дельта {} в выгрузку {} не ушла, HTTP {} — следующая проба не раньше чем через {}",
                label, target.account().id(), result.httpStatus(), refusedRetry);
        return Outcome.FAILED;
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

    /**
     * Исход пишется по выгрузке: у пяти прайс-листов он свой. Текст отказа
     * собирает {@link DromSyncRecord} — с меткой статуса или сбоя соединения
     * и потому никогда не пустой: {@code NULL} в этой колонке значит «принято».
     */
    private void updateAccountState(long accountId, boolean success, String failure) {
        jdbc.update("""
                UPDATE marketplace_account
                   SET last_sync_at = now(), last_error = ?
                 WHERE id = ?""",
                success ? null : failure, accountId);
    }

    /**
     * Изменения этой выгрузки не ушли на Дром (задача 0271): сняты с очереди
     * без отправки (лимит, отказ «нужен человек») или остались в ней после
     * временного сбоя — признак ставится на любую неотправку (ответы
     * владельца 12.10.2026 и 26.10.2026).
     *
     * <p>Хранится отдельно от {@code last_error}: тот говорит про последнюю
     * попытку, и следующая принятая малая дельта честно пишет туда «принято»,
     * хотя изменения неотправленной так и не уехали. Признак не копит
     * изменения и ничего не шлёт повторно (решение «б» из 0269) — это только
     * память карточки. Гаснет он на чтении, когда Дром скачал полный прайс
     * позже {@code unsent_at} (ответ владельца 12.10.2026, вариант «а»);
     * поэтому момент — последний, а не первый: изменения, не ушедшие после
     * забора, им не покрыты.
     */
    private void markUnsent(long accountId, String reason) {
        jdbc.update("""
                UPDATE marketplace_account
                   SET unsent_at = now(), unsent_error = ?
                 WHERE id = ?""", reason, accountId);
    }

    /**
     * То же при пропуске в интервале: причина — отказ, из-за которого ждём.
     *
     * <p>Момент — самое позднее из не ушедших изменений ({@code marked_at}
     * пачки), а не «сейчас», и назад он не двигается (задача 0382). Проход
     * идёт каждые 15 секунд, а держащаяся после сбоя пачка — та же самая:
     * с «сейчас» момент уезжал вперёд любого забора прайса, и признак
     * не гас, хотя Дром уже скачал всё, что лежит в очереди. Изменение
     * до забора им покрыто, правка после — нет и поднимает признак снова.
     * Позиции без отметки в очереди (выдача сделки, отметка уже убрана) —
     * «сейчас», как раньше.
     */
    private void markUnsentByLastRefusal(long accountId, List<Long> partIds) {
        String places = String.join(",", java.util.Collections.nCopies(partIds.size(), "?"));
        Object[] args = new Object[partIds.size() + 1];
        for (int i = 0; i < partIds.size(); i++) {
            args[i] = partIds.get(i);
        }
        args[partIds.size()] = accountId;
        jdbc.update("UPDATE marketplace_account"
                        + " SET unsent_at = changed.at, unsent_error = last_error"
                        + " FROM (SELECT COALESCE(max(marked_at), now()) AS at"
                        + " FROM part_change WHERE part_id IN (" + places + ")) changed"
                        + " WHERE id = ? AND (unsent_at IS NULL OR unsent_at < changed.at)",
                args);
    }

    /** Выгрузка с уже расшифрованным ключом: расшифровка делается один раз. */
    private record Ready(DromAccountReader.Account account, String cabinetKey) {
    }
}
