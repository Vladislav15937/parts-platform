package ru.partsflow.inventory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Инвентаризация против настоящего склада.
 *
 * <p>Центральная проверка — {@link #saleDuringCountingIsNotShortage()}: пересчёт
 * не останавливает продажи, и наивная разница «посчитали минус сняли при
 * открытии» списала бы каждую проданную за время пересчёта деталь как
 * недостачу.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class InventoryServiceTest extends PostgresTestBase {

    private static final String TENANT = "t_000051";

    @Autowired
    private StockLedger ledger;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockReservationRepository reservations;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long warehouse;
    private Long otherWarehouse;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        inTenant(() -> {
            // Сессии между тестами не мешают: каждый тест берёт свой склад.
            Long branch = jdbc.queryForObject(
                    "INSERT INTO branch (name) VALUES ('Филиал') RETURNING id", Long.class);
            warehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, 'Ткацкая') RETURNING id",
                    Long.class, branch);
            otherWarehouse = jdbc.queryForObject(
                    "INSERT INTO warehouse (branch_id, name) VALUES (?, '54 YARD') RETURNING id",
                    Long.class, branch);
            return null;
        });
    }

    @Test
    @DisplayName("Открытие снимает учётный остаток склада")
    void openSnapshotsStock() {
        Long partId = partWithStock("Фара левая", 3);

        InventorySession session = inTenant(() -> inventory.open(warehouse, null));

        assertThat(session.getStatus()).isEqualTo(InventorySession.SessionStatus.OPEN);
        assertThat(session.getStartedAt()).as("момент открытия не вычитан из БД").isNotNull();
        assertThat(session.getLines()).singleElement().satisfies(line -> {
            assertThat(line.getPartId()).isEqualTo(partId);
            assertThat(line.getQtyExpected()).isEqualByComparingTo("3");
            assertThat(line.isCounted()).as("строка посчитана до пересчёта").isFalse();
        });
    }

    @Test
    @DisplayName("Сошедшаяся позиция движений не порождает")
    void matchingCountProducesNoMovement() {
        Long partId = partWithStock("Бампер передний", 2);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        assertThat(inTenant(() -> inventory.discrepancies(sessionId))).isEmpty();
        assertThat(inTenant(() -> inventory.apply(sessionId)).adjusted()).isZero();
        assertThat(adjustments(partId)).isZero();
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Недостача списывается корректировкой")
    void shortageIsWrittenOff() {
        Long partId = partWithStock("Стартер 1NZ-FE", 5);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("3"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        assertThat(inTenant(() -> inventory.discrepancies(sessionId))).singleElement()
                .satisfies(d -> {
                    assertThat(d.delta()).isEqualByComparingTo("-2");
                    assertThat(d.shortage()).isTrue();
                    // Наименование, а не «деталь 4»: расхождение разбирает
                    // человек, стоя у полки.
                    assertThat(d.title()).isEqualTo("Стартер 1NZ-FE");
                    assertThat(d.applied()).isFalse();
                });

        assertThat(inTenant(() -> inventory.apply(sessionId)).adjusted()).isEqualTo(1);
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("3");
    }

    /**
     * Одновременное проведение не списывает недостачу дважды.
     *
     * <p>Отметка {@code applied_at} на строке защищает от повтора, но читается
     * она в начале транзакции: двойное нажатие или второй менеджер видят её
     * пустой оба. Проверено на живом складе — остаток 20 при недостаче 2 стал
     * 16 вместо 18, и оба ответа сказали «скорректировано 1». То есть пересчёт
     * испортил склад тем самым действием, которым его чинят, и молча: ошибки
     * не показал никто, а заметить это можно только следующим пересчётом.
     *
     * <p>Недостача берётся заведомо меньше половины остатка. С большой
     * ошибка прячется: второму не хватает свободного, и его отбивает
     * условие в {@code StockLedger} — то есть тест был бы зелёным при
     * сломанном коде.
     *
     * <p>Проигравший получает то же, что и при последовательном повторе:
     * «пересчёт уже проведён». Свой ответ на гонку означал бы, что одно
     * и то же действие объясняется по-разному в зависимости от того,
     * с какой скоростью нажали.
     */
    @Test
    @DisplayName("Одновременное проведение пересчёта списывает недостачу один раз")
    void concurrentApplyWritesTheShortageOnce() throws Exception {
        Long partId = partWithStock("Радиатор одновременный", 20);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("18"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        List<Integer> adjusted = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> refused = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable applying = () -> {
            try {
                together.await();
                adjusted.add(inTenant(() -> inventory.apply(sessionId)).adjusted());
            } catch (Throwable e) {
                refused.add(e);
            }
        };

        Thread first = new Thread(applying);
        Thread second = new Thread(applying);
        first.start();
        second.start();
        first.join();
        second.join();

        assertThat(qtyOf(partId, warehouse))
                .as("недостача списана дважды: пересчёт испортил склад")
                .isEqualByComparingTo("18");
        assertThat(adjusted).as("прошли оба").containsExactly(1);
        // Отказ по правилу, то есть 409, а не поломка сервера: пятисотку
        // офлайн-очередь повторяет вечно.
        assertThat(refused).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(refused.get(0)).hasMessageContaining("завершённый пересчёт");
    }

    /**
     * Двое считают одну и ту же позицию, которой не было в снимке.
     *
     * <p>Строка такой позиции заводится подсчётом, и уникальный индекс
     * {@code inventory_line_uk} отбивает вторую. Наружу это ехало как
     * «Операция нарушает целостность данных» — на телефон кладовщика, где
     * очередь читает 409 как «требует внимания»: подсчёт пропадал, а почему,
     * по такому ответу не понять.
     *
     * <p>Последовательно посчитать позицию дважды законно, побеждает
     * последний, — и от скорости нажатия поведение зависеть не должно.
     * Поэтому повтор, а не отказ.
     */
    @Test
    @DisplayName("Одновременный подсчёт позиции вне снимка не теряет работу")
    void concurrentCountOfAnUnlistedPartRetries() throws Exception {
        Long inSnapshot = partWithStock("Бампер в снимке", 1);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        // Деталь заводится после открытия сессии: в снимок она не попала,
        // и строку для неё создаст первый же подсчёт.
        Long found = partWithStock("Фара, найденная на полке", 1);

        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable counting = () -> {
            try {
                together.await();
                // Без внешней транзакции: повтор обязан идти новой, а внутри
                // чужой она была бы помечена на откат.
                TenantContext.set(TENANT);
                inventory.count(sessionId, found, new BigDecimal("1"), null);
            } catch (Throwable e) {
                failures.add(e);
            } finally {
                TenantContext.clear();
            }
        };

        Thread first = new Thread(counting);
        Thread second = new Thread(counting);
        first.start();
        second.start();
        first.join();
        second.join();

        assertThat(failures)
                .as("подсчёт кладовщика потерян с ответом про целостность данных")
                .isEmpty();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM inventory_line WHERE session_id = ? AND part_id = ?",
                Integer.class, sessionId, found)))
                .as("строк позиции больше одной").isEqualTo(1);
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT qty_counted FROM inventory_line WHERE session_id = ? AND part_id = ?",
                BigDecimal.class, sessionId, found)))
                .as("подсчёт не записан").isEqualByComparingTo("1");
        // Снимок открывался до появления найденной детали, и в нём должна
        // остаться только та позиция: иначе тест проверяет не то, что задумано.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT qty_expected FROM inventory_line WHERE session_id = ? AND part_id = ?",
                BigDecimal.class, sessionId, inSnapshot))).isEqualByComparingTo("1");
    }

    /**
     * Недостача, обнулившая остаток, закрывает карточку.
     *
     * <p>Прежде она оставалась «в наличии» с нулём — то есть врала про самое
     * важное, про наличие. Списать её было нечем: остаток уже ноль, списывать
     * нечего, а правило «разбирается руками» рук не имело. Причина при этом
     * не теряется: в журнале стоит корректировка, а не списание, и «не нашли
     * при пересчёте» отличимо от «разбили при разборе».
     */
    @Test
    @DisplayName("Недостача до нуля закрывает карточку, а не оставляет её в наличии")
    void shortageToZeroClosesThePart() {
        Long partId = partWithStock("Фара, которой не нашли", 1);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, BigDecimal.ZERO, null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("0");
        assertThat(statusOf(partId)).isEqualTo("WRITTEN_OFF");
        assertThat(movementTypes(partId))
                .as("причина недостачи потеряна: в журнале должно остаться INVENTORY_ADJUST")
                .contains("INVENTORY_ADJUST")
                .doesNotContain("WRITE_OFF");
    }

    // Частичная недостача остаток оставляет — карточка остаётся в наличии.
    @Test
    @DisplayName("Частичная недостача карточку не закрывает")
    void partialShortageKeepsThePart() {
        Long partId = partWithStock("Стартер, недосчитались одного", 3);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(statusOf(partId)).isEqualTo("IN_STOCK");
    }

    /**
     * Недостача по обещанной детали не проводится, но и не держит остальные.
     *
     * <p>Списать её нельзя — остаток уйдёт ниже резерва, и это отобьёт триггер
     * склада. Останавливать из-за неё всю инвентаризацию значит держать
     * посчитанный склад непроведённым, пока продавец говорит с покупателем,
     * — а кладовщик, который считал, снять резерв не может по роли.
     */
    /**
     * По этой строке кладовщик идёт действовать — снимать резерв, — значит она
     * обязана называть документы так, как их спросят у продавца. Перечень
     * номеров под словом в единственном числе («по сделке 1, 14») читается
     * как один документ с составным номером, и искать его будут напрасно.
     */
    @Test
    @DisplayName("Несколько сделок названы во множественном числе и с номерами")
    void promisedNamesEveryDeal() {
        assertThat(InventoryService.promisedBy(List.of()))
                .as("резерв без сделки не должен выдумывать её номер")
                .isEmpty();
        assertThat(InventoryService.promisedBy(List.of(7L)))
                .isEqualTo(", обещана по сделке №7");
        assertThat(InventoryService.promisedBy(List.of(1L, 14L)))
                .isEqualTo(", обещана по сделкам №1, №14");
    }

    @Test
    @DisplayName("Обещанная деталь не блокирует проведение остальных позиций")
    void promisedShortageDoesNotBlockOthers() {
        Long promised = partWithStock("Фара, обещанная покупателю", 1);
        Long other = partWithStock("Стартер, просто пропавший", 1);
        inTenant(() -> {
            reservations.reserve(promised, warehouse, BigDecimal.ONE);
            return null;
        });

        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        inTenant(() -> inventory.count(sessionId, promised, BigDecimal.ZERO, null));
        inTenant(() -> inventory.count(sessionId, other, BigDecimal.ZERO, null));
        inTenant(() -> inventory.finishCounting(sessionId));

        var applied = inTenant(() -> inventory.apply(sessionId));

        assertThat(applied.adjusted())
                .as("непроведённая обещанная деталь остановила и остальные")
                .isEqualTo(1);
        assertThat(applied.blocked()).singleElement()
                .asString().contains("Фара, обещанная покупателю");

        assertThat(applied.blocked()).singleElement().asString()
                .as("резерв без сделки не должен выдумывать её номер")
                .doesNotContain("сделк");

        assertThat(qtyOf(other, warehouse)).isEqualByComparingTo("0");
        assertThat(qtyOf(promised, warehouse))
                .as("обещанное списалось, хотя резерв на месте")
                .isEqualByComparingTo("1");
        // Сессия не закрыта: пересчёт доведут после снятия резерва.
        assertThat(statusOfSession(sessionId)).isEqualTo("COUNTED");
    }

    /**
     * Повтор после снятия резерва дописывает только застрявшее.
     *
     * <p>Ради этого отметка о проведении стоит на строке, а не на сессии:
     * без неё второй проход списал бы уже проведённое ещё раз, то есть
     * испортил бы склад ровно тем действием, которым его чинят.
     */
    @Test
    @DisplayName("Повтор после снятия резерва не проводит уже проведённое дважды")
    void repeatedApplyAdjustsOnlyBlocked() {
        Long promised = partWithStock("Фара, снятая с резерва", 1);
        Long other = partWithStock("Стартер, пропавший рядом", 2);
        inTenant(() -> {
            reservations.reserve(promised, warehouse, BigDecimal.ONE);
            return null;
        });

        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        inTenant(() -> inventory.count(sessionId, promised, BigDecimal.ZERO, null));
        inTenant(() -> inventory.count(sessionId, other, BigDecimal.ONE, null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        inTenant(() -> {
            reservations.release(promised, warehouse, BigDecimal.ONE);
            return null;
        });
        var second = inTenant(() -> inventory.apply(sessionId));

        assertThat(second.adjusted()).isEqualTo(1);
        assertThat(second.blocked()).isEmpty();
        assertThat(qtyOf(promised, warehouse)).isEqualByComparingTo("0");
        assertThat(qtyOf(other, warehouse))
                .as("проведённая строка списалась второй раз — склад испорчен")
                .isEqualByComparingTo("1");
        assertThat(adjustments(other))
                .as("на одну строку записано больше одной корректировки")
                .isEqualTo(1);
        assertThat(statusOfSession(sessionId)).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("Излишек приходуется корректировкой")
    void surplusIsAdded() {
        Long partId = partWithStock("Генератор 2AZ-FE", 1);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("4"), null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("Продажа во время пересчёта не превращается в недостачу")
    void saleDuringCountingIsNotShortage() {
        Long partId = partWithStock("Капот Camry V40", 10);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        // Кладовщик считает полку и находит все десять — учёт с фактом сошёлся.
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("10"), null));

        // Потом продавец продаёт две. Склад работает, пересчёт его не морозит.
        // Время движения явное: момент подсчёта ставят часы JVM, а now()
        // движения — часы Postgres, и их расхождение в миллисекунды делало
        // порядок случайным. Тест при этом падал раз в десяток прогонов
        // и выглядел как поломка расчёта.
        saleAt(partId, warehouse, 2, countedAtOf(sessionId, partId).plusSeconds(1));

        inTenant(() -> inventory.finishCounting(sessionId));

        // Наивная разница «10 посчитали минус 10 сняли при открытии» дала бы
        // ноль корректировки — и это верно. Но если сравнивать с остатком
        // на момент проведения (8), получится излишек +2, и склад раздуется.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .as("продажа во время пересчёта попала в расхождения")
                .isEmpty();
        assertThat(inTenant(() -> inventory.apply(sessionId)).adjusted()).isZero();
        assertThat(qtyOf(partId, warehouse))
                .as("корректировка затёрла продажу").isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Продажа до подсчёта уже учтена: недостачи нет")
    void saleBeforeCountingIsAccountedFor() {
        Long partId = partWithStock("Радиатор кондиционера", 10);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        // Сначала продали две, потом кладовщик дошёл до полки и нашёл восемь.
        // Время движения явное по той же причине: иначе продажа могла оказаться
        // позже подсчёта по часам другой машины.
        saleAt(partId, warehouse, 2, startedAtOf(sessionId).plusMillis(1));
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("8"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        // Против снимка при открытии это выглядело бы недостачей в две штуки.
        // Против учёта на момент подсчёта — всё сошлось.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .as("проданное до подсчёта посчитали недостачей")
                .isEmpty();
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Подсчёт из офлайн-очереди сравнивается с моментом подсчёта, а не получения")
    void offlineCountUsesItsOwnMoment() {
        Long partId = partWithStock("Бампер передний", 10);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        backdateSession(sessionId, Duration.ofHours(2));

        // Кладовщик посчитал десять час назад — всё сошлось. Связи в ангаре
        // не было, запись пролежала в очереди телефона. Полчаса назад
        // продавец выдал две: склад на разборке работает всегда.
        saleAt(partId, warehouse, 2, Instant.now().minus(30, ChronoUnit.MINUTES));

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("10"), null,
                Duration.ofHours(1)));
        inTenant(() -> inventory.finishCounting(sessionId));

        // По времени получения запроса учёт даёт восемь, факт десять — излишек,
        // и проведение вернуло бы на склад уже проданное.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .as("продажа за время офлайна стала излишком")
                .isEmpty();
        assertThat(inTenant(() -> inventory.apply(sessionId)).adjusted()).isZero();
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Продажа после подсчёта не прячет недостачу")
    void saleAfterOfflineCountStaysShortage() {
        Long partId = partWithStock("Крыло переднее левое", 10);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        backdateSession(sessionId, Duration.ofHours(2));

        // Час назад кладовщик нашёл на полке восемь вместо десяти — недостача.
        // Уже после этого продали ещё две.
        saleAt(partId, warehouse, 2, Instant.now().minus(10, ChronoUnit.MINUTES));

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("8"), null,
                Duration.ofHours(1)));
        inTenant(() -> inventory.finishCounting(sessionId));

        // Без учёта давности продажа съела бы недостачу: учёт восемь, факт
        // восемь, всё «сошлось» — и две пропавшие детали остались бы в остатке.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .singleElement()
                .extracting(InventoryService.DiscrepancyLine::delta)
                .isEqualTo(new BigDecimal("-2.000"));
    }

    @Test
    @DisplayName("Давность больше возраста сессии подрезается до её открытия")
    void impossibleAgeIsClamped() {
        Long partId = partWithStock("Радиатор основной", 10);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        sale(partId, warehouse, 2);

        // Подсчёт якобы за десять лет до открытия сессии: давность посчитана
        // неверно. Отказ потерял бы уже пройденную полку, поэтому сравниваем
        // со снимком — как это делалось до появления телефона.
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("10"), null,
                Duration.ofDays(3650)));
        inTenant(() -> inventory.finishCounting(sessionId));

        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .as("подсчёт с невозможной давностью приняли как есть")
                .isEmpty();
    }

    @Test
    @DisplayName("Перемещение на другой склад не считается недостачей дважды")
    void moveIsAccountedPerWarehouse() {
        Long partId = partWithStock("Дверь задняя левая", 4);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        // Две уехали на второй склад до подсчёта.
        inTenant(() -> ledger.record(StockMovement.move(
                partId, new BigDecimal("2"), warehouse, otherWarehouse, null)));

        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        // У перемещения qty_delta положительный, но для склада-источника это
        // расход: смотреть на знак нельзя, только на пару складов.
        assertThat(inTenant(() -> inventory.discrepancies(sessionId))).isEmpty();
        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("2");
        assertThat(qtyOf(partId, otherWarehouse)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Непосчитанная строка не списывается")
    void uncountedLineIsNotWrittenOff() {
        Long counted = partWithStock("Крыло переднее правое", 2);
        Long skipped = partWithStock("Поддомкратник", 7);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, counted, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        // «Не дошли до полки» и «не нашли» — разные вещи. Подмена одного другим
        // списала бы полсклада.
        assertThat(qtyOf(skipped, warehouse))
                .as("непосчитанную позицию списали").isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("Посчитанный ноль — это недостача, а не пропуск")
    void countedZeroIsShortage() {
        Long partId = partWithStock("Ступица передняя", 3);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, partId, BigDecimal.ZERO, null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(qtyOf(partId, warehouse)).isEqualByComparingTo("0");
        assertThat(statusOf(partId))
                .as("обнулённая инвентаризацией деталь не должна выглядеть проданной")
                .isNotEqualTo("SOLD");
    }

    @Test
    @DisplayName("Найденная лишняя позиция добавляется строкой с нулевым учётом")
    void unknownPartBecomesSurplusLine() {
        partWithStock("Фара правая", 1);
        Long unknown = partWithStock("Найдена на полке", 0);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        inTenant(() -> inventory.count(sessionId, unknown, new BigDecimal("2"), null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThat(qtyOf(unknown, warehouse)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("Вторая инвентаризация того же склада не открывается")
    void secondSessionOnSameWarehouseIsRejected() {
        partWithStock("Амортизатор", 1);
        inTenant(() -> inventory.open(warehouse, null));

        // Две сессии дадут двойную корректировку на одно расхождение.
        assertThatThrownBy(() -> inTenant(() -> inventory.open(warehouse, null)))
                .hasMessageContaining("уже идёт инвентаризация");
    }

    @Test
    @DisplayName("Незавершённый пересчёт не проводится")
    void openSessionCannotBeApplied() {
        partWithStock("Фильтр воздушный", 1);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        assertThatThrownBy(() -> inTenant(() -> inventory.apply(sessionId)))
                .hasMessageContaining("состоянии OPEN");
    }

    @Test
    @DisplayName("Проведённую инвентаризацию не отменяют")
    void appliedSessionCannotBeCancelled() {
        Long partId = partWithStock("Тормозной диск", 2);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        inTenant(() -> inventory.count(sessionId, partId, BigDecimal.ONE, null));
        inTenant(() -> inventory.finishCounting(sessionId));
        inTenant(() -> inventory.apply(sessionId));

        assertThatThrownBy(() -> inTenant(() -> inventory.cancel(sessionId)))
                .hasMessageContaining("не отменяют");
    }

    /**
     * Проданное сразу после подсчёта не считается пропавшим до него.
     *
     * <p><b>Зачем.</b> Расхождение считается за окно «открытие сессии —
     * подсчёт строки», и обе границы окна ставит база: {@code started_at}
     * и {@code created_at} движений — это её {@code now()}. Момент подсчёта
     * при этом брался по часам приложения, а они с часами базы расходятся:
     * на машине разработчика 87 мс, между разными машинами — секунды.
     *
     * <p>Стоит базе отставать, и движение, случившееся <b>после</b> подсчёта,
     * получает отметку раньше него, попадает в окно и уменьшает учётный
     * остаток. Деталь, проданную через полсекунды после того, как кладовщик
     * прошёл полку, пересчёт объявляет недостачей — и проведение её
     * списывает. Порча склада ровно тем действием, которым его сверяют.
     *
     * <p><b>Чего этот тест не доказывает.</b> В контейнере тестов часы базы
     * и приложения совпадают до миллисекунды, поэтому от возврата к часам
     * JVM он не падает — проверено откатом. Он держит другое, тоже нужное:
     * окно считается «до подсчёта», а не «до текущего момента». Само
     * расхождение часов закрывается правилом, а не проверкой: обе границы
     * окна спрашиваются у базы.
     */
    @Test
    @DisplayName("Проданное сразу после подсчёта не превращается в недостачу")
    void saleRightAfterCountIsNotCountedBeforeIt() {
        Long partId = partWithStock("Клапан EGR", 5);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());

        // Считаем ровно то, что лежит: расхождения нет.
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("5"), null));
        // И тут же продаём — это уже после подсчёта, сколько бы часы
        // приложения и базы ни расходились.
        inTenant(() -> ledger.record(
                StockMovement.sale(partId, BigDecimal.ONE, warehouse, null)));

        assertThat(inTenant(() -> inventory.discrepancies(sessionId)))
                .as("проданное после подсчёта записано в недостачу — проведение её спишет")
                .isEmpty();
    }

    @Test
    @DisplayName("Расхождения считаются одинаково до и после проведения")
    void discrepanciesAreStableAfterApply() {
        Long partId = partWithStock("Насос омывателя", 5);
        Long sessionId = inTenant(() -> inventory.open(warehouse, null).getId());
        inTenant(() -> inventory.count(sessionId, partId, new BigDecimal("4"), null));
        inTenant(() -> inventory.finishCounting(sessionId));

        List<InventoryService.DiscrepancyLine> before = inTenant(() -> inventory.discrepancies(sessionId));
        inTenant(() -> inventory.apply(sessionId));
        List<InventoryService.DiscrepancyLine> after = inTenant(() -> inventory.discrepancies(sessionId));

        // Считается по неизменяемому журналу, поэтому ответ не зависит от того,
        // когда спросили: кладовщику можно показать итог и после проведения.
        assertThat(after).hasSameSizeAs(before);
        assertThat(after.get(0).delta()).isEqualByComparingTo(before.get(0).delta());
        // Но проведённая строка помечена: иначе экран показывает
        // «скорректировано» рядом с той же минусовой строкой.
        assertThat(before.get(0).applied()).isFalse();
        assertThat(after.get(0).applied()).isTrue();
    }

    // ---------- фикстуры ----------

    private Long partWithStock(String title, int qty) {
        return inTenant(() -> {
            Long partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, cost_price)
                    VALUES (NULL, ?, 5000, 2000) RETURNING id""", Long.class, title);
            if (qty > 0) {
                ledger.record(StockMovement.intake(partId, java.math.BigDecimal.valueOf(qty), warehouse, null));
            }
            return partId;
        });
    }

    private void sale(Long partId, Long warehouseId, int qty) {
        inTenant(() -> ledger.record(StockMovement.sale(partId, java.math.BigDecimal.valueOf(qty), warehouseId, null)));
    }

    /** Когда сервер записал подсчёт строки. */
    private Instant countedAtOf(Long sessionId, Long partId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT counted_at FROM inventory_line
                 WHERE session_id = ? AND part_id = ?""",
                Instant.class, sessionId, partId));
    }

    private Instant startedAtOf(Long sessionId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT started_at FROM inventory_session WHERE id = ?", Instant.class, sessionId));
    }

    /**
     * Открывает сессию задним числом.
     *
     * <p>Проверки давности иначе бессмысленны: сессия, открытая мгновение
     * назад, оставляет всё прошлое за границей окна {@code (открытие, подсчёт]},
     * и любая реализация даёт одинаково пустой результат. На живом складе
     * пересчёт идёт часами.
     */
    private void backdateSession(Long sessionId, Duration by) {
        inTenant(() -> jdbc.update(
                "UPDATE inventory_session SET started_at = started_at - ?::interval WHERE id = ?",
                by.toMinutes() + " minutes", sessionId));
    }

    /**
     * Продажа задним числом.
     *
     * <p>Нужна ровно для проверок давности: без явного времени все движения
     * теста попадают в одну-две миллисекунды, и отличить «до подсчёта»
     * от «после» становится нечем.
     */
    private void saleAt(Long partId, Long warehouseId, int qty, Instant when) {
        inTenant(() -> {
            StockMovement movement = ledger.record(StockMovement.sale(
                    partId, java.math.BigDecimal.valueOf(qty), warehouseId, null));
            // Момент правится после записи: иначе все движения теста
            // попадают в одну миллисекунду и «до подсчёта» от «после»
            // не отличить. Правка идёт прямым SQL мимо репозитория — тот
            // менять журнал не умеет вовсе, и это ровно то, что нужно:
            // в приложении такой правки быть не может, а тесту она нужна.
            jdbc.update("UPDATE stock_movement SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(when), movement.getId());
            return null;
        });
    }

    private BigDecimal qtyOf(Long partId, Long warehouseId) {
        return inTenant(() -> {
            List<BigDecimal> found = jdbc.queryForList(
                    "SELECT qty FROM part_stock WHERE part_id = ? AND warehouse_id = ?",
                    BigDecimal.class, partId, warehouseId);
            return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
        });
    }

    private int adjustments(Long partId) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM stock_movement
                 WHERE part_id = ? AND movement_type = 'INVENTORY_ADJUST'""",
                Integer.class, partId));
    }

    private java.util.List<String> movementTypes(Long partId) {
        return inTenant(() -> jdbc.queryForList(
                "SELECT movement_type FROM stock_movement WHERE part_id = ?",
                String.class, partId));
    }

    private String statusOfSession(Long sessionId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM inventory_session WHERE id = ?", String.class, sessionId));
    }

    private String statusOf(Long partId) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT status FROM part WHERE id = ?", String.class, partId));
    }

    private <T> T inTenant(Supplier<T> action) {
        try {
            TenantContext.set(TENANT);
            return transactionTemplate.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }
}
