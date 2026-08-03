package ru.partsflow.publishing.drom;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.VerticalSide;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;

/**
 * Потоковая запись канонического прайса Дрома.
 *
 * <p><b>Почему схема наша, а не Дрома.</b> Дром дословно обещает «настроим
 * разбор под ваш формат» — фиксированной схемы у него нет. Значит генератор
 * можно написать один раз только при условии, что формат выбираем мы, а при
 * подключении каждого клиента просим Дром настроить разбор под него. Обратная
 * стратегия — генератор под формат каждого клиента — умножает код на число
 * арендаторов. Подробнее в {@code docs/drom-integration.md} §3.
 *
 * <p><b>Почему StAX.</b> То же, что и в фиде Авито: у крупного клиента до
 * 50 000 позиций, а полный прайс Дром забирает по постоянному URL и лимитом
 * 5 МБ не ограничен — то есть файл будет большим. Сборка в DOM или в String
 * съест гигабайты и уронит приложение у самого ценного клиента. Вход —
 * {@link Iterator}, чтобы и набор позиций не материализовался.
 *
 * <p><b>Применимость намеренно не пишется.</b> Дром выводит её сам из
 * {@code manufacturer} и {@code oem_number}, а для неоригинала ищет кросс
 * (§4 документа). Своя применимость у нас сейчас всё равно пуста: каталог
 * марок и моделей не наполнен, а импорт из Bazon кладёт кузов и двигатель
 * только в наименование. Когда каталог появится, поля {@code brandcars},
 * {@code modelcars}, {@code bodycars}, {@code engine} и {@code year}
 * добавляются здесь же.
 */
@org.springframework.stereotype.Component
public class DromPriceWriter {

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

    /**
     * @return сколько позиций записано — попадает в {@code publication_log},
     *         чтобы было с чем сверить «Замечания к товарам» в кабинете Дрома
     */
    public int write(OutputStream out, Iterator<DromOffer> offers) throws XMLStreamException {
        XMLStreamWriter writer = FACTORY.createXMLStreamWriter(out, "UTF-8");
        int written = 0;
        try {
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("offers");

            while (offers.hasNext()) {
                writeOffer(writer, offers.next());
                written++;
            }

            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        } finally {
            writer.close();
        }
        return written;
    }

    private void writeOffer(XMLStreamWriter w, DromOffer offer) throws XMLStreamException {
        w.writeStartElement("offer");

        // ordercode должен быть стабильным между выгрузками: по нему Дром
        // узнаёт позицию при обновлении. Меняется — и вместо обновления
        // получится новое объявление вместо старого.
        element(w, "ordercode", offer.orderCode());
        element(w, "name", offer.name());
        element(w, "description", offer.description());
        element(w, "price", offer.price() == null ? null : offer.price().toPlainString());

        // available считается по свободному остатку, а не по общему. Деталь,
        // отложенную под клиента, площадка показывать не должна: иначе
        // приедут двое за одной запчастью — та самая ошибка, от которой
        // стоят reserve_stock и сверка резервов.
        w.writeStartElement("available");
        w.writeCharacters(Boolean.toString(offer.isAvailable()));
        w.writeEndElement();

        element(w, "condition", switch (offer.condition()) {
            case NEW -> "Новое";
            case USED, REFURBISHED -> "Б/у";
        });
        element(w, "manufacturer", offer.manufacturer());
        element(w, "oem_number", offer.oemNumber());
        element(w, "analog_numbers", joinAnalogs(offer.analogNumbers()));

        // Три независимые оси, а не одно поле «сторона»: «стойка передняя
        // левая нижняя» иначе не выражается.
        element(w, "lr", lateral(offer.lateralSide()));
        element(w, "fr", longitudinal(offer.longitudinalSide()));
        element(w, "ud", vertical(offer.verticalSide()));

        element(w, "color", offer.color());
        element(w, "supplier_art", offer.marking());

        // Фотографии повторяющимся элементом, а не одной строкой через
        // запятую: в ссылке может встретиться что угодно, а разбор по
        // разделителю ломается ровно на том товаре, у которого он попался.
        // Первая ссылка — главный снимок: площадка ставит его обложкой.
        if (offer.photos() != null) {
            for (String photo : offer.photos()) {
                element(w, "photo", photo);
            }
        }

        w.writeEndElement();
    }

    /** Дром ждёт номера одной строкой через запятую. */
    private String joinAnalogs(List<String> analogs) {
        if (analogs == null || analogs.isEmpty()) {
            return null;
        }
        return String.join(",", analogs);
    }

    private String lateral(LateralSide side) {
        return side == null ? null : switch (side) {
            case LEFT -> "лево";
            case RIGHT -> "право";
        };
    }

    private String longitudinal(LongitudinalSide side) {
        return side == null ? null : switch (side) {
            case FRONT -> "перед";
            case REAR -> "зад";
        };
    }

    private String vertical(VerticalSide side) {
        return side == null ? null : switch (side) {
            case UPPER -> "верх";
            case LOWER -> "низ";
        };
    }

    /** Пустые элементы не пишутся: Дром считает их заполненными пустым значением. */
    private void element(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value == null || value.isBlank()) {
            return;
        }
        w.writeStartElement(name);
        w.writeCharacters(value);
        w.writeEndElement();
    }
}
