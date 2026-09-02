package ru.partsflow.publishing.drom;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.PartCondition;
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
 * <p><b>Применимость пишется отдельными тегами.</b> Эталон Дрома для раздела
 * «Автозапчасти» начинается ровно с этого: «укажите применимость», «размещайте
 * информацию о каждом параметре в отдельные теги — марка, модель, кузов,
 * двигатель». Выводить её самому из {@code oem_number} площадка умеет, но
 * только для оригинала и только если номер она знает; у б/у детали с разборки
 * половина номеров ей не известна, и без марки такая позиция не находится
 * фильтром покупателя. Данные лежат у нас с самого начала: марка и модель —
 * в машине-доноре, у контрактной — в {@code part_applicability}, которую
 * разбор наименований наполнил.
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
        element(w, "name", plainName(offer.name()));
        // Вид детали отдельным полем: по нему площадка кладёт товар в раздел,
        // и заголовка ей для этого мало — она его разбирает, а не читает.
        element(w, "partname", offer.partKind());
        element(w, "description", descriptionOf(offer));
        element(w, "price", offer.price() == null ? null : offer.price().toPlainString());

        // Остаток числом, а не только флагом наличия. Их документация по API
        // говорит про удаление буквально: «если товар нужно удалить,
        // в колонке отправить значение "0"», и колонка эта — количество.
        // Флаг available остаётся: он есть в их же эталоне для автозапчастей.
        element(w, "quantity", offer.availableQty() == null
                ? "0" : offer.availableQty().stripTrailingZeros().toPlainString());

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

        // Применимость. Порядок тегов — как в эталоне площадки: сначала
        // машина, потом её кузов и двигатель. Кузов и двигатель есть только
        // у детали с донора: у контрактной машина не одна, и приписать ей
        // чужой кузов значит соврать покупателю, который по нему и подбирает.
        element(w, "brandcars", offer.carBrand());
        element(w, "modelcars", offer.carModel());
        element(w, "bodycars", offer.bodyCode());
        element(w, "engine", offer.engineCode());
        element(w, "year", offer.year() == null ? null : String.valueOf(offer.year()));

        element(w, "color", offer.color());
        element(w, "supplier_art", offer.marking());

        // Склад, на котором деталь лежит. Имя, а не адрес: реквизитов складов
        // у нас нет вовсе, а имена клиент даёт по месту — «Ткацкая», «54 YARD».
        // Для покупателя это ответ на «куда ехать», и у клиента с филиалами
        // на разных концах города вопрос не праздный. Проданная позиция едет
        // без него: она никуда не делась только в том смысле, что объявление
        // остаётся, — лежать ей уже негде.
        element(w, "sklad", offer.warehouse());

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

    /**
     * Заголовок без сокращений и без того, что уже уехало своими полями.
     *
     * <p>Требование площадки прямое: «названия товаров должны быть максимально
     * простые и понятные, без сокращений и аббревиатур» — от этого зависит,
     * в какой раздел товар попадёт при распознавании. У нас же в заголовке
     * стоят «лев.», «перед.», «(б/у)», и стоят они там по делу: заголовок
     * читают глазами на витрине склада, на этикетке и в карточке.
     *
     * <p>Поэтому чистится только выгрузка, а собранный заголовок в системе
     * остаётся как был. Сторона и состояние при этом не теряются — они уже
     * уехали полями {@code lr}, {@code fr}, {@code ud} и {@code condition},
     * то есть в заголовке они были повторением. Тот же приём у Bazon: у него
     * заголовок чистый, а сторона живёт отдельными полями.
     */
    static String plainName(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }
        String plain = title
                .replaceAll("\\s*\\((?:б/у|новая|восст\\.)\\)", "")
                .replaceAll("\\s+(?:перед|задн|лев|прав|верх|ниж)\\.", "");
        return plain.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * Описание позиции.
     *
     * <p>Их «минимальный формат» XML — наименование, описание и цена, а у детали
     * с разборки описания обычно нет вовсе: приёмщик его не пишет, ему некогда.
     * Поэтому, когда своего описания нет, оно собирается из того, что мы и так
     * знаем: с какой машины снято, какая сторона, какой номер. Ничего сверх
     * этого — «отличное качество» и «гарантия» тут были бы обещанием,
     * которого никто не давал.
     */
    static String descriptionOf(DromOffer offer) {
        return append(own(offer), offer);
    }

    /**
     * Дописывает к описанию то, что владелец ввёл отдельными полями.
     *
     * <p><b>Зачем.</b> «Текстовый блок» и «Видео» приезжают из прежней системы
     * и правятся в карточке — владелец их пишет, а покупатель не видел вовсе:
     * в прайс уходило только `description`. Написанное «для объявления»
     * оставалось внутри системы, и заметить это можно было лишь сверив файл
     * с карточкой руками.
     *
     * <p>Дописываем, а не заменяем: своё описание остаётся первым, потому что
     * с него покупатель начинает читать. Ссылка на ролик идёт последней
     * и с подписью — голый адрес посреди текста читается как мусор.
     *
     * <p>Ничего не выдумываем: в описание попадает только то, что владелец
     * ввёл сам. Пустые поля не дают ни строки, ни подписи.
     */
    private static String append(String description, DromOffer offer) {
        List<String> parts = new java.util.ArrayList<>();
        if (description != null && !description.isBlank()) {
            parts.add(description.strip());
        }
        if (offer.textBlock() != null && !offer.textBlock().isBlank()) {
            parts.add(offer.textBlock().strip());
        }
        if (offer.videoUrl() != null && !offer.videoUrl().isBlank()) {
            parts.add("Видео: " + offer.videoUrl().strip());
        }
        return String.join("\n", parts);
    }

    /** Описание владельца, а если его нет — собранное из того, что знаем. */
    private static String own(DromOffer offer) {
        if (offer.description() != null && !offer.description().isBlank()) {
            return offer.description();
        }

        List<String> lines = new java.util.ArrayList<>();
        String vehicle = java.util.stream.Stream.of(
                        offer.carBrand(), offer.carModel(), offer.bodyCode(),
                        offer.engineCode(), offer.year() == null ? null : String.valueOf(offer.year()))
                .filter(part -> part != null && !part.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
        if (!vehicle.isBlank()) {
            // Контрактную деталь никто ни с чего не снимал: она приехала
            // контейнером, а марка у неё из применимости. «Снято с Toyota
            // Camry» про неё — неправда, и заметит её покупатель, а не мы.
            lines.add((offer.fromDonor() ? "Снято с: " : "Подходит на: ") + vehicle + ".");
        }

        String sides = java.util.stream.Stream.of(
                        longitudinalWord(offer.longitudinalSide()),
                        lateralWord(offer.lateralSide()),
                        verticalWord(offer.verticalSide()))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(", "));
        if (!sides.isBlank()) {
            lines.add("Расположение: " + sides + ".");
        }

        if (offer.oemNumber() != null && !offer.oemNumber().isBlank()) {
            lines.add("Номер производителя: " + offer.oemNumber() + ".");
        }
        lines.add("Состояние: " + (offer.condition() == PartCondition.NEW ? "новая" : "б/у") + ".");
        return String.join(" ", lines);
    }

    private static String lateralWord(LateralSide side) {
        return side == null ? null : side == LateralSide.LEFT ? "левая" : "правая";
    }

    private static String longitudinalWord(LongitudinalSide side) {
        return side == null ? null : side == LongitudinalSide.FRONT ? "передняя" : "задняя";
    }

    private static String verticalWord(VerticalSide side) {
        return side == null ? null : side == VerticalSide.UPPER ? "верхняя" : "нижняя";
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
