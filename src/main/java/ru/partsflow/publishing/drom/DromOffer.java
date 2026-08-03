package ru.partsflow.publishing.drom;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.VerticalSide;

import java.math.BigDecimal;
import java.util.List;

/**
 * Позиция прайса Дрома — ровно те данные, что уходят в один {@code <offer>}.
 *
 * <p>Отдельный тип, а не {@code Part}: половина полей прайса — агрегаты
 * и соседние таблицы. Свободный остаток суммируется по складам, номера
 * приходят из {@code part_oem}. Тянуть это ленивыми связями JPA при 50 000
 * позициях значит получить 50 000 дополнительных запросов, поэтому строку
 * собирает один потоковый SQL, а писателю остаётся только форматирование.
 *
 * @param partKind     вид детали справочником — «Фара», «Стартер»; по нему
 *                     площадка раскладывает товар по разделам, и заголовка
 *                     ей для этого мало
 * @param availableQty свободный остаток, а не общий: см. {@link DromPriceWriter}
 * @param photos       постоянные ссылки на снимки, главный первым; пустой
 *                     список — снимков нет либо выдача не настроена
 * @param fromDonor    снята с машины, а не пришла контейнером. От этого
 *                     зависит формулировка описания: «снято с» у детали
 *                     с донора и «подходит на» у контрактной — её никто
 *                     ни с чего не снимал, и марка у неё из применимости
 * @param carBrand     марка машины, к которой деталь подходит; у контрактной
 *                     это список марок применимости через запятую
 * @param carModel     модель — так же, списком у контрактной
 * @param year         год машины-донора; у контрактной его нет и быть не может
 */
public record DromOffer(
        String orderCode,
        String name,
        String partKind,
        String description,
        BigDecimal price,
        BigDecimal availableQty,
        PartCondition condition,
        String manufacturer,
        String oemNumber,
        List<String> analogNumbers,
        LateralSide lateralSide,
        LongitudinalSide longitudinalSide,
        VerticalSide verticalSide,
        String color,
        String marking,
        List<String> photos,
        String carBrand,
        String carModel,
        String bodyCode,
        String engineCode,
        Integer year,
        boolean fromDonor) {

    public boolean isAvailable() {
        return availableQty != null && availableQty.signum() > 0;
    }
}
