package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Доля арендаторов, которую обслуживает этот экземпляр приложения.
 *
 * <p>Фоновые обходы — релей outbox, отправка дельт, разбор недоставленного —
 * идут по всем арендаторам ячейки в один поток. Вхолостую это дёшево: пятьсот
 * схем обходятся за доли секунды. Но как только в обходе появляется разговор
 * с чужим сервером, один медленный арендатор задерживает всех остальных,
 * а при недоступном брокере поток встаёт до {@code max.block.ms} на каждом.
 *
 * <p>Поэтому экземпляр берёт не всех, а свою долю: остаток от деления номера
 * арендатора на число экземпляров. Раздача статическая и без согласования —
 * никакого выбора лидера и никакой общей памяти: каждый знает только свой
 * номер, а деление на непересекающиеся доли гарантирует, что дважды никто
 * не возьмётся за одного арендатора.
 *
 * <p><b>Один экземпляр — умолчание,</b> и тогда доля равна всем: настройка
 * нужна только там, где приложение поднято в нескольких копиях.
 *
 * <p><b>Цена честная:</b> при остановке одного экземпляра его доля стоит,
 * пока он не вернётся. Это осознанный размен — событие подождёт минуту,
 * зато отказ площадки у одного клиента не держит очередь остальных.
 * Динамическая перебалансировка потребовала бы координатора, то есть
 * общего компонента, падение которого валит всю ячейку.
 */
@Component
public class TenantShare {

    private static final Logger log = LoggerFactory.getLogger(TenantShare.class);

    private final JdbcTemplate jdbc;
    private final int index;
    private final int count;

    public TenantShare(JdbcTemplate jdbc,
                       @Value("${app.instance-index:0}") int index,
                       @Value("${app.instance-count:1}") int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Экземпляров не может быть меньше одного: " + count);
        }
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException(
                    "Номер экземпляра %d вне диапазона 0..%d".formatted(index, count - 1));
        }
        this.jdbc = jdbc;
        this.index = index;
        this.count = count;
        if (count > 1) {
            log.info("Экземпляр {} из {}: обслуживает свою долю арендаторов", index, count);
        }
    }

    /**
     * Схемы арендаторов этого экземпляра.
     *
     * <p>Отбор идёт по номеру арендатора, а не по имени схемы: номер —
     * это число, и остаток от деления распределяет клиентов ровно.
     */
    public List<String> schemas() {
        return jdbc.queryForList("""
                SELECT schema_name FROM public.tenant_registry
                 WHERE status = 'ACTIVE' AND tenant_id % ? = ?
                 ORDER BY tenant_id""",
                String.class, count, index);
    }
}
