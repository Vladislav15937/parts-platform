package ru.partsflow.publishing.drom;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.OutputStream;
import java.util.Iterator;

/**
 * Потоковая запись прайса шин и дисков.
 *
 * <p><b>Схема здесь не наша, в отличие от прайса запчастей.</b> Там площадка
 * обещает «настроим разбор под ваш формат», и формат выбирали мы. Тут
 * у неё есть опубликованный эталон с примером XML
 * (farpost.ru/help/trebovaniya_k_price_listam_po_shinam, п. 7), и имена
 * элементов взяты оттуда дословно: {@code model}, {@code marking},
 * {@code inSet}, {@code iznos}, {@code spike}. Своя схема означала бы лишний
 * разговор с техническим специалистом при подключении и лишний повод
 * разойтись.
 *
 * <p>Порядок элементов тоже как в эталоне: сначала то, что есть у любой
 * шины, потом износ и фотография — они только у б/у.
 *
 * <p>StAX по той же причине, что и в прайсе запчастей: файл забирают целиком,
 * и собирать его в памяти нельзя.
 */
@org.springframework.stereotype.Component
public class DromWheelWriter {

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

    /** @return сколько позиций записано — попадает в {@code publication_log} */
    public int write(OutputStream out, Iterator<DromWheelOffer> offers) throws XMLStreamException {
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

    private void writeOffer(XMLStreamWriter w, DromWheelOffer offer) throws XMLStreamException {
        w.writeStartElement("offer");

        // Артикул стабилен между выгрузками: по нему площадка узнаёт позицию
        // при обновлении. Сменится — вместо обновления появится второе
        // объявление, а первое пропадёт вместе с просмотрами.
        element(w, "ordercode", offer.orderCode());
        element(w, "name", offer.name());

        w.writeStartElement("available");
        w.writeCharacters(Boolean.toString(offer.isAvailable()));
        w.writeEndElement();

        element(w, "model", offer.model());
        element(w, "marking", offer.marking());
        element(w, "inSet", offer.inSet() == null ? null : String.valueOf(offer.inSet()));
        element(w, "quantity", offer.quantity() == null
                ? null : offer.quantity().stripTrailingZeros().toPlainString());
        element(w, "price", offer.price() == null ? null : offer.price().toPlainString());
        element(w, "condition", offer.condition());
        element(w, "season", offer.season());
        element(w, "type", offer.type());
        element(w, "spike", offer.spike());
        element(w, "year", offer.year() == null ? null : String.valueOf(offer.year()));

        // Износ и фотография — по эталону это поля б/у шины. У новой их нет,
        // и пустые писать нельзя: площадка считает их заполненными пустым.
        element(w, "iznos", offer.wearPercent() == null
                ? null : String.valueOf(offer.wearPercent()));
        if (offer.photos() != null) {
            for (String photo : offer.photos()) {
                element(w, "picture", photo);
            }
        }

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
