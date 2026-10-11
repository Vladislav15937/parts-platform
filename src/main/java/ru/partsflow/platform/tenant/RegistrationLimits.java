package ru.partsflow.platform.tenant;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Пределы открытой регистрации компании (задача 0184).
 *
 * <p>Это <b>свои</b> пределы, а не пределы управляющего контура
 * ({@link ProvisioningLimits}). Контур закрыт секретом и сетью, ходит им один
 * оператор, и его числа выбраны под перенос клиентов пачкой. Форма регистрации
 * открыта любому, и числа у неё другие по существу: человек регистрируется
 * один раз в жизни, а не десятью разборками подряд.
 *
 * <p><b>Предел частоты — {@code app.registration-max-per-hour}, 10 в час с
 * одного адреса (IPv6 — с одной подсети /64, {@link #rateKey}).</b> Законный пользователь тратит одну попытку; ещё две-три
 * уходят на опечатки в коде компании и на «код занят». Десять — это целая
 * контора с общим адресом (коворкинг, один провайдер на нескольких клиентов),
 * заводящая компании по очереди, и это запас на ошибки, а не на поток.
 * Повторное нажатие не расходует место: {@link AddressWindow#refund}.
 *
 * <p><b>Потолок — {@code app.registration-max-tenants}, 200 компаний на
 * ячейку</b> (решение владельца, 11 октября 2026). Нижняя граница плана
 * 200–300 с запасом: проба на 500 шла без снимков. Считаются все записи
 * реестра, включая брошенные — они не снимаются, и схема остаётся на диске и
 * под каждой выкладкой навсегда. Отказ — «регистрация временно закрыта», 409,
 * а не 500 и не молчаливая двести первая компания.
 *
 * <p><b>Потолок закрывает форму и для настоящих клиентов, и это цена, а не
 * дефект.</b> Занять его мусорными регистрациями можно — с разных адресов,
 * пройдя вычислительную преграду ({@link RegistrationChallenge}). Поэтому на
 * подходе к потолку владелец получает предупреждение (метрика и тревога
 * {@code ЯчейкаЗаполняется}), и поднять потолок или закрыть мусор руками он
 * успевает до отказа, а не после.
 *
 * <p>Потолок проверяется с допуском, как и у контура: одновременные запросы
 * читают счёт до вставки соседа, и «201 вместо 200» не стоит блокировки на всю
 * регистрацию. Он защищает диск, а не инвариант.
 *
 * <p><b>Адрес — тот, что видит приложение.</b> За терминатором Tomcat
 * подставляет его из {@code X-Forwarded-For} сам ({@code FORWARD_HEADERS=native}),
 * а Caddy заголовок клиента перезаписывает своим: подделать адрес строкой в
 * запросе нельзя. В разработке терминатора нет — это адрес самого браузера.
 */
@Component
public class RegistrationLimits {

    private static final Logger log = LoggerFactory.getLogger(RegistrationLimits.class);

    /**
     * Сколько ключей держим под счётом — защита нашей памяти, не предел для
     * человека. Заполнив карту, новый ключ вытесняет самый давний, а не
     * получает отказ: отказ тут же стал бы отказом всем, кто пришёл позже
     * (429 на час — оборотная сторона защиты, которая и должна была такое
     * отбивать). Цена: тот, кто держит 4096 подсетей, обнуляет чужие
     * счётчики — но он и без этого получает по десять попыток на каждую,
     * а общий предел ячейке задаёт потолок числа компаний.
     */
    private static final int MAX_TRACKED_ADDRESSES = 4096;

    public static final String REASON_RATE = "rate";
    public static final String REASON_CLOSED = "closed";

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final int maxPerHour;
    private final int maxTenants;
    private final AddressWindow window =
            new AddressWindow(ProvisioningLimits.WINDOW, MAX_TRACKED_ADDRESSES, true);

    public RegistrationLimits(JdbcTemplate jdbc, MeterRegistry metrics,
                              @Value("${app.registration-max-per-hour:10}") int maxPerHour,
                              @Value("${app.registration-max-tenants:200}") int maxTenants) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.maxPerHour = maxPerHour;
        this.maxTenants = maxTenants;
    }

    /** Потолок числа компаний — для метрики и тревоги. */
    public int maxTenants() {
        return maxTenants;
    }

    /**
     * Сколько компаний в ячейке уже есть: все записи реестра, включая брошенные.
     *
     * <p>Таблица общая, арендатора у неё нет, поэтому {@code JdbcTemplate}
     * вне транзакции здесь законен — ловушка про {@code search_path}
     * относится к схемам арендаторов.
     */
    public long tenantCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM public.tenant_registry", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Открыта ли регистрация. Форма спрашивает это при загрузке: человеку
     * незачем заполнять поля и считать проверку, чтобы в конце услышать отказ.
     *
     * @throws ProvisioningLimits.CellFull потолок достигнут — 409
     */
    public void requireOpen() {
        long tenants = tenantCount();
        if (tenants >= maxTenants) {
            metrics.counter("partsflow.registration.rejected", "reason", REASON_CLOSED).increment();
            log.warn("Регистрация закрыта потолком: в ячейке {} компаний при потолке {}",
                    tenants, maxTenants);
            throw new ProvisioningLimits.CellFull(
                    "Регистрация временно закрыта. Напишите нам — мы подключим вашу компанию сами");
        }
    }

    /**
     * Можно ли принять ещё одну регистрацию с этого адреса — и если да, занимает
     * место в окне.
     *
     * <p>Частота спрашивается первой: она живёт в памяти, а потолок — запрос
     * к базе. Отбитый по частоте запрос обязан стоить как можно меньше.
     *
     * @throws ProvisioningLimits.TooManyRequests частота исчерпана — 429
     * @throws ProvisioningLimits.CellFull        потолок достигнут — 409
     */
    public void beforeRegister(String address) {
        String key = rateKey(address);
        int used = window.take(key);
        if (used > maxPerHour) {
            window.refund(key);
            metrics.counter("partsflow.registration.rejected", "reason", REASON_RATE).increment();
            log.warn("Регистрации: с адреса {} за час попыток {} при пределе {}",
                    address, used, maxPerHour);
            throw new ProvisioningLimits.TooManyRequests(
                    ("С вашего адреса за последний час было слишком много попыток регистрации "
                            + "(не больше %d). Повторите через час или напишите нам")
                            .formatted(maxPerHour));
        }
        requireOpen();
    }

    /** Попытка оказалась повтором уже принятой: место возвращается. */
    public void replayed(String address) {
        window.refund(rateKey(address));
    }

    /**
     * Ключ, по которому считается предел. IPv4 — адрес целиком; IPv6 — подсеть
     * /64: хосту провайдер выдаёт её всю (2^64 адресов), и счёт по полному
     * адресу давал бы ему по свежему счётчику на каждую попытку. Адрес вида
     * {@code ::ffff:1.2.3.4} — это IPv4 в записи IPv6, считается как IPv4.
     * Не адрес (имя, пустое) остаётся как есть и в DNS не ходит.
     */
    static String rateKey(String address) {
        if (address == null || address.indexOf(':') < 0) {
            return address;
        }
        byte[] raw;
        try {
            // Строка с двоеточием — литерал IPv6: разбор в DNS не ходит.
            raw = java.net.InetAddress.getByName(address.replaceFirst("%.*$", "")).getAddress();
        } catch (java.net.UnknownHostException e) {
            return address;
        }
        if (raw.length == 4) {
            // getByName сворачивает ::ffff:a.b.c.d в Inet4Address.
            return (raw[0] & 0xff) + "." + (raw[1] & 0xff) + "." + (raw[2] & 0xff) + "." + (raw[3] & 0xff);
        }
        StringBuilder key = new StringBuilder("v6/64:");
        for (int i = 0; i < 8; i++) {
            key.append("%02x".formatted(raw[i] & 0xff));
        }
        return key.toString();
    }

    /**
     * Компания заведена — считаем и предупреждаем, если ячейка заполняется.
     *
     * <p>Порог предупреждения — четыре пятых потолка: 160 из 200. Число выбрано
     * исполнителем (владелец назвал «на подходе», но не число): запас в сорок
     * компаний — это недели при обычном потоке и часы при мусорном, то есть
     * успеть можно в обоих случаях; ближе к потолку предупреждение пришло бы
     * вместе с отказом.
     */
    public void registered(String address) {
        metrics.counter("partsflow.registration.succeeded").increment();
        long tenants = tenantCount();
        log.info("Зарегистрирована компания с адреса {}; в ячейке {} из {}",
                address, tenants, maxTenants);
        if (tenants >= warnAt()) {
            log.warn("Ячейка заполняется: {} компаний из {}. Регистрация закроется на потолке — "
                    + "поднимите app.registration-max-tenants или заведите следующую ячейку",
                    tenants, maxTenants);
        }
    }

    /** С какого числа компаний предупреждать. */
    public long warnAt() {
        return maxTenants * 4L / 5L;
    }
}
