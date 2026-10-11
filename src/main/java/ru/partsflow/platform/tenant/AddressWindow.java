package ru.partsflow.platform.tenant;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Скользящее окно попыток по адресам.
 *
 * <p>Вынесено из {@link ProvisioningLimits} (задача 0184) без изменения
 * поведения, потому что у публичной регистрации ({@link RegistrationLimits})
 * нужен <b>свой</b> счёт, а не тот же: общий делил бы между собой управляющий
 * контур и открытую форму. Адресов под счётом ограниченное число
 * ({@link #maxTracked}), и тот, кто ходил бы с тысячи разных адресов на форму
 * регистрации, заполнил бы карту — и оператор подключения получил бы отказ
 * «адресов больше, чем ячейка готова различать» на своём, законном пути.
 * Раздельные окна делают так, чтобы открытая сторона не могла занять ни
 * одного места закрытой.
 *
 * <p>Состояние процесса, а не ячейки — со всеми оговорками, записанными
 * в {@link ProvisioningLimits}: у каждой из двух сборок свой счёт.
 */
final class AddressWindow {

    private final Duration window;
    private final int maxTracked;
    private final Map<String, Deque<Long>> recent = new HashMap<>();

    AddressWindow(Duration window, int maxTracked) {
        this.window = window;
        this.maxTracked = maxTracked;
    }

    /**
     * Занимает место в окне и возвращает, сколько их стало вместе с этим.
     *
     * <p>Минус единица — адресов под счётом больше, чем держим: заполненная
     * карта (уже после уборки устаревшего) сама признак долбёжки, и запрос
     * в этом состоянии отбивается вызывающим.
     *
     * <p>Под замком: чтение окна и запись в него не должны разъезжаться —
     * то же правило, что у остатка склада, только цена ниже.
     */
    synchronized int take(String address) {
        long now = System.currentTimeMillis();
        long since = now - window.toMillis();

        // Уборка устаревшего на каждом заходе: иначе карта растёт адресами,
        // которые больше не придут.
        recent.values().forEach(times -> {
            while (!times.isEmpty() && times.peekFirst() < since) {
                times.pollFirst();
            }
        });
        recent.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        Deque<Long> times = recent.get(address);
        if (times == null) {
            if (recent.size() >= maxTracked) {
                return -1;
            }
            times = new ArrayDeque<>();
            recent.put(address, times);
        }
        times.addLast(now);
        return times.size();
    }

    /**
     * Возвращает самое свежее место: попытка оказалась повтором уже принятой.
     *
     * <p>Место занимается до того, как известно, новая это регистрация или
     * повтор прежней (два нажатия подряд приходят в одну секунду, и узнать
     * об этом можно только арбитром в базе). Повтор ничего не завёл, и
     * держать его за расход предела значило бы отказывать человеку, дважды
     * нажавшему одну кнопку.
     */
    synchronized void refund(String address) {
        Deque<Long> times = recent.get(address);
        if (times != null && !times.isEmpty()) {
            times.pollLast();
            if (times.isEmpty()) {
                recent.remove(address);
            }
        }
    }

    synchronized int tracked() {
        return recent.size();
    }
}
