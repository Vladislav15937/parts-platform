package ru.partsflow.sales;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.shared.RetailCustomer;
import ru.partsflow.support.PostgresTestBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Двое продавцов открыли экран продажи одновременно.
 *
 * <p>Контрагента «Частное лицо» заводит провижининг, но у арендаторов,
 * созданных раньше этой возможности, его нет — и дозаводит его первое же
 * обращение к {@link CustomerService#retail()}. Первых обращений может
 * оказаться несколько сразу: продавцы открывают свой экран утром, все
 * вместе.
 *
 * <p><b>Последовательный повтор тут ничего не доказывает, и это в проекте
 * уже трижды стоило ошибки.</b> Идемпотентность приёмки, ссылка на снимок
 * и заказ с площадки работали правильно на втором запросе **подряд**
 * и ломались на двух **одновременных**: между чтением «такого ещё нет»
 * и вставкой второй запрос ещё ничего не видит. Уникального индекса
 * на {@code customer.name} нет, то есть здесь дубль не отбила бы и база:
 * в справочнике появилось бы два «Частных лица», и история розницы
 * разбилась бы пополам — половина сделок на одном, половина на другом.
 *
 * <p><b>Почему гонка настоящая, а не сымитированная.</b> Потоки встречаются
 * на защёлке **внутри** своих транзакций и с уже прогретым соединением:
 * к моменту {@code go.countDown()} каждому остаётся ровно то, что мы
 * проверяем, — «прочитать и вставить». Ждут они друг друга, держа свои
 * соединения, поэтому потоков меньше, чем соединений в тестовом пуле (их
 * пять, см. {@code src/test/resources/application.properties}): возьми
 * больше — часть потоков встала бы в очередь за соединением, которое
 * держат ждущие на защёлке, и тест завис бы, не дойдя до базы вовсе.
 *
 * <p>Кругов несколько: одна удачная попытка на гонке ничего не говорит —
 * планировщик мог развести потоки во времени. Между кругами контрагент
 * удаляется, потому что проверяется именно **первое** обращение; ссылок
 * на него в этой схеме нет — ни одной сделки здесь не заводят.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class RetailCustomerConcurrencyTest extends PostgresTestBase {

    private static final String TENANT = "t_000151";

    /**
     * Потоков меньше, чем соединений в тестовом пуле: они ждут друг друга
     * внутри транзакций, то есть с занятым соединением.
     */
    private static final int SELLERS = 4;

    /** Кругов: одна удачная попытка на гонке — это везение, а не проверка. */
    private static final int ROUNDS = 3;

    /**
     * Верхняя граница ожидания, а не ожидаемое время: на исправной машине
     * потоки встречаются за миллисекунды. Щедрая — по той же причине, что
     * и в {@code DealConcurrencyTest}: на раннере с двумя ядрами жёсткие
     * секунды превращают тест в лотерею.
     */
    private static final int WAIT_SECONDS = 60;

    @Autowired
    private CustomerService customers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @Test
    @DisplayName("Одновременное первое обращение заводит одного контрагента, а не четырёх")
    void simultaneousFirstCallsCreateOneContractor() throws Exception {
        // Прогрев: первое обращение к метамодели и прокси репозитория стоит
        // заметных секунд на холодном контексте, и выпади они на поток внутри
        // защёлки — он ждал бы соседей, которые ещё только просыпаются.
        inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class));

        for (int round = 1; round <= ROUNDS; round++) {
            // Проверяется именно первое обращение: контрагента быть не должно.
            inTenant(() -> jdbc.update(
                    "DELETE FROM customer WHERE name = ?", RetailCustomer.NAME));

            // Список пишут четыре потока разом — синхронизированный, иначе
            // проверка «все получили одного» зависела бы от того, чья запись
            // потерялась.
            List<Long> ids = java.util.Collections.synchronizedList(new ArrayList<>());

            CountDownLatch ready = new CountDownLatch(SELLERS);
            CountDownLatch go = new CountDownLatch(1);
            List<AtomicReference<Throwable>> failures = new ArrayList<>();
            List<Thread> sellers = new ArrayList<>();

            for (int i = 0; i < SELLERS; i++) {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                failures.add(failure);
                sellers.add(new Thread(() -> openSaleScreen(ready, go, failure, ids)));
            }

            sellers.forEach(Thread::start);
            assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("потоки не дошли до защёлки за %d с — проверять гонку не на чем",
                            WAIT_SECONDS)
                    .isTrue();
            go.countDown();
            for (Thread seller : sellers) {
                seller.join(WAIT_SECONDS * 1000L);
            }

            for (AtomicReference<Throwable> failure : failures) {
                assertThat(failure.get())
                        .as("круг %d: обращение к контрагенту розничной продажи отказало", round)
                        .isNull();
            }

            // Главное утверждение: в справочнике одна строка. Их было бы
            // четыре — по одной на продавца, — и отчёт по клиентам показал бы
            // четыре «Частных лица» вместо одного.
            assertThat(retailRows())
                    .as("круг %d: одновременное открытие экрана завело больше "
                            + "одного контрагента розничной продажи", round)
                    .isEqualTo(1);

            // И все четверо получили одного и того же: разные номера означают,
            // что сделки разъедутся по разным контрагентам ещё до того,
            // как лишние строки кто-нибудь заметит.
            assertThat(ids).hasSize(SELLERS);
            assertThat(ids.stream().distinct().toList())
                    .as("круг %d: продавцы получили разных контрагентов %s", round, ids)
                    .hasSize(1);
        }
    }

    /**
     * Один продавец: открыл экран, дождался остальных и спросил контрагента.
     *
     * <p>Соединение прогревается **до** защёлки — тем же приёмом, что
     * в {@code DealConcurrencyTest}: иначе после {@code go} потоки разошлись
     * бы во времени на установку {@code search_path} и выдачу соединения,
     * то есть на всё, кроме проверяемого.
     */
    private void openSaleScreen(CountDownLatch ready, CountDownLatch go,
                                AtomicReference<Throwable> failure, List<Long> ids) {
        TenantContext.set(TENANT);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT count(*) FROM customer", Integer.class);
                ready.countDown();
                try {
                    go.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ids.add(customers.retail().id());
            });
        } catch (Throwable e) {
            failure.set(e);
        } finally {
            TenantContext.clear();
        }
    }

    private int retailRows() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM customer WHERE name = ?",
                Integer.class, RetailCustomer.NAME));
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
