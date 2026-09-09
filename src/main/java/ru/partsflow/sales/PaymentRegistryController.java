package ru.partsflow.sales;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Реестр платежей — раздел «Платежи» (задача 0045).
 *
 * <p>Владелец сводит кассу по вечерам, и до этого экрана ему было нечем:
 * система пишет источник платежа с задачи 0024, а прочитать построчно —
 * что пришло, что ушло, чем платили и по какой сделке — было негде. Отчёт
 * по источникам (`/api/reports/payments`) отвечает суммами за месяц,
 * а «что это за расход в четверг» суммой не объясняется.
 *
 * <p><b>Свой путь, а не {@code /api/deals/...}</b>: платёж бывает без сделки
 * вовсе — пополнение и выдача с лицевого счёта, — и прятать реестр всех денег
 * компании под сделками значило бы называть его тем, чем он не является.
 *
 * <p>Роли — владелец и менеджер, как у отчётов: здесь видно всё, чем живёт
 * компания, включая возвраты покупателям и выдачи с лицевых счетов. Продавцу
 * своя касса видна там, где он работает, — в карточке клиента и в сделке.
 *
 * <p>Это <b>не</b> шаг к фискализации 54-ФЗ и её не приближает
 * (`docs/fiscal-54fz.md`): реестр только показывает то, что уже записано.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentRegistryController {

    private static final String READS = "hasAnyRole('OWNER','MANAGER')";

    private final SalesService sales;

    public PaymentRegistryController(SalesService sales) {
        this.sales = sales;
    }

    /**
     * @param direction воронка: {@code IN} — приходные, {@code OUT} —
     *                  расходные, пусто — все
     * @param from      начало периода; пусто — с начала времён
     * @param to        конец периода (исключая); пусто — по текущий момент
     * @param size      сколько строк вернуть; реестр читают с конца и вглубь
     *                  не листают, поэтому вместо курсора — растущий предел
     */
    @GetMapping
    @PreAuthorize(READS)
    public SalesService.PaymentsPage list(
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return sales.listPayments(parseDirection(direction), parseInstant(from), parseInstant(to),
                size);
    }

    /**
     * Направление разбирается здесь, а не автоматической конвертацией Spring:
     * незнакомое значение обязано отвечать словами, а не «Failed to convert
     * value of type String».
     */
    private static PaymentDirection parseDirection(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PaymentDirection.valueOf(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Неизвестное направление платежа: " + value + " (ожидается IN или OUT)");
        }
    }

    /**
     * Границы периода приходят строкой — тем же приёмом, что у реестра
     * возвратов: неявная конвертация {@code Instant} не гарантирована,
     * а нарушение формата обязано отвечать словами, а не пятисоткой
     * на разборе параметра.
     */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Неверный формат даты: " + value);
        }
    }
}
