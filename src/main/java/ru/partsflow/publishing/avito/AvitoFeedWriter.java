package ru.partsflow.publishing.avito;

import ru.partsflow.inventory.Part;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.Iterator;

/**
 * Потоковая запись XML-фида Авито.
 *
 * <p><b>Почему StAX, а не шаблонизатор или DOM.</b> У крупного клиента до 50 000
 * объявлений в одном файле. Сборка такого документа в памяти (DOM или конкатенация
 * строк) съест гигабайты и рано или поздно уронит приложение по OOM — причём
 * сначала у самого большого и самого ценного клиента. StAX пишет напрямую в поток,
 * потребление памяти не зависит от размера фида.
 *
 * <p>На вход подаётся {@link Iterator}, а не {@code List}: набор объявлений тоже
 * не должен материализоваться целиком. В боевом коде это курсор JDBC с
 * {@code fetchSize}.
 *
 * <p>Формат описан на autoload.avito.ru/format. Обязательно прогоняй результат
 * через официальный валидатор перед публикацией: отказы модерации почти всегда
 * приходят из-за категорий и параметров, а не из-за структуры.
 */
public class AvitoFeedWriter {

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

    public int write(OutputStream out, Iterator<Part> parts, AvitoMappingResolver mapping)
            throws XMLStreamException {

        XMLStreamWriter writer = FACTORY.createXMLStreamWriter(out, "UTF-8");
        int written = 0;
        try {
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("Ads");
            writer.writeAttribute("formatVersion", "3");
            writer.writeAttribute("target", "Avito.ru");

            while (parts.hasNext()) {
                writeAd(writer, parts.next(), mapping);
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

    private void writeAd(XMLStreamWriter w, Part part, AvitoMappingResolver mapping)
            throws XMLStreamException {

        AvitoMappingResolver.Mapping m = mapping.resolve(part.getCategoryId());

        w.writeStartElement("Ad");

        // Id должен быть стабильным между выгрузками, иначе площадка сочтёт
        // объявление новым, снимет старое и обнулит накопленные просмотры.
        element(w, "Id", part.getPublicCode());
        element(w, "DateBegin", LocalDate.now().toString());
        element(w, "Category", m.category());
        element(w, "Goods Type", m.goodsType());
        element(w, "Title", part.getTitle());
        element(w, "Description", part.getDescription());
        element(w, "Price", part.getPrice() == null ? null : part.getPrice().toPlainString());

        // Б/у против нового — параметр Condition, обязателен для запчастей.
        element(w, "Condition", switch (part.getCondition()) {
            case NEW -> "Новое";
            case USED, REFURBISHED -> "Б/у";
        });

        // TODO: Images, ContactPhone, Address, OEM — добавляются, когда будут
        // готовы обработка фото и настройки аккаунта арендатора.

        w.writeEndElement();
    }

    private void element(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value == null || value.isBlank()) {
            return;
        }
        w.writeStartElement(name);
        w.writeCharacters(value);
        w.writeEndElement();
    }
}
