package ru.partsflow.publishing.drom;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Потоковая запись файла шин и дисков в формате Bazon (задача 0340).
 *
 * <p><b>Формат чужой, и в этом весь смысл.</b> До 0340 колёсный прайс был XML
 * по схеме площадки ({@code offers}, {@code ordercode}, {@code inSet},
 * {@code iznos}); у Bazon это CSV из 25 колонок, и разбор на стороне Дрома
 * для них разный. Переехавший клиент подключает наш файл к тому же
 * прайс-листу — решение владельца 19.10.2026 «всё как в базон». Формат
 * снят с образца файла шин и дисков Bazon (65 строк): кодировка
 * <b>windows-1251</b>, разделитель {@code ;}, <b>все</b> поля в двойных
 * кавычках, в том числе пустые, строки через CRLF (одиночный LF внутри
 * комментария остаётся как набран), без BOM, первая строка — заголовок.
 * Менять любую мелочь нельзя: какие из них различает разбор площадки,
 * проверить нам нечем.
 *
 * <p>Знак, которого нет в windows-1251, уходит вопросительным знаком: другого
 * честного ответа у однобайтной кодировки нет, а ронять весь файл на одной
 * позиции хуже — площадка прочтёт обрыв как «этих товаров больше нет».
 *
 * <p>Пишется потоком по тем же причинам, что и прайс запчастей: файл забирают
 * целиком, собирать его в памяти нельзя.
 */
@org.springframework.stereotype.Component
public class DromWheelWriter {

    /** Кодировка файла. */
    static final Charset CHARSET = Charset.forName("windows-1251");

    /** Заголовки колонок дословно, в порядке файла Bazon. */
    static final List<String> HEADER = List.of(
            "Артикул",
            "Тип (диск, шина, колесо)",
            "Количество в комплекте",
            "Производитель диска",
            "Модель диска",
            "Диаметр диска",
            "Тип диска",
            "Ширина диска",
            "Вылет диска",
            "PCD диска",
            "Диаметр осевого отверстия диска",
            "Производитель шины",
            "Модель шины",
            "Ширина профиля шины",
            "Высота профиля шины",
            "Посадочный диаметр шины",
            "Сезон шины (лето, зима, шипы)",
            "Дата производства",
            "Износ шин",
            "Новое/БУ",
            "Комментарий",
            "Цена",
            "Фото",
            "Статус",
            "Номер производителя");

    // ---- слова файла: все в одном месте, потому что часть из них не видели у Bazon ----

    /** Первая буква артикула — русская «К» (U+041A), не латинская K. */
    static final String ARTICLE_PREFIX = "К";

    static final String TYPE_TYRE = "шина";
    static final String TYPE_DISC = "диск";
    static final String TYPE_ASSEMBLY = "колесо";

    static final String IN_STOCK = "В наличии";
    /**
     * Слово недоступной позиции — наше: у Bazon в образце «В наличии» у 65
     * из 65. Проданное колесо нельзя убрать из файла (объявление пропадёт
     * вместе с просмотрами), а сказать «В наличии» значило бы звать за
     * товаром, которого нет. Понимает ли его разбор — вопрос площадке.
     */
    static final String OUT_OF_STOCK = "Нет в наличии";

    static final String SEASON_SUMMER = "Летняя";
    static final String SEASON_WINTER = "Зимняя";
    static final String SEASON_FRICTION = "Зимняя (липучка)";
    /** Слова для шипов у Bazon не видели (по заголовку колонки — «шипы»); до обхода так. */
    static final String SEASON_STUDDED = "Шипы";
    static final String SEASON_ALL = "Всесезонная";

    /** Слова для нового у Bazon не видели (в образце одни «БУ» и «Контракт»); до обхода так. */
    static final String CONDITION_NEW = "Новое";
    static final String CONDITION_USED = "БУ";
    static final String CONDITION_CONTRACT = "Контракт";

    /** @return сколько позиций записано — попадает в {@code publication_log} */
    public int write(OutputStream out, Iterator<DromWheelOffer> offers) throws IOException {
        Writer writer = new OutputStreamWriter(out, CHARSET);
        int written = 0;
        writeRow(writer, HEADER);
        while (offers.hasNext()) {
            writeRow(writer, cells(offers.next()));
            written++;
        }
        writer.flush();
        return written;
    }

    private static List<String> cells(DromWheelOffer o) {
        List<String> cells = new ArrayList<>(HEADER.size());
        cells.add(o.article());
        cells.add(o.type());
        cells.add(String.valueOf(o.inSet()));
        cells.add(o.discBrand());
        cells.add(o.discModel());
        cells.add(o.discDiameter());
        cells.add(o.discType());
        cells.add(o.discWidth());
        cells.add(o.offset());
        cells.add(o.boltPattern());
        cells.add(o.hubBore());
        cells.add(o.tyreBrand());
        cells.add(o.tyreModel());
        cells.add(o.tyreWidth());
        cells.add(o.tyreHeight());
        cells.add(o.tyreDiameter());
        cells.add(o.season());
        cells.add(o.year());
        cells.add(o.wear());
        cells.add(o.condition());
        cells.add(o.comment());
        cells.add(o.price());
        cells.add(o.photos() == null ? null : String.join(", ", o.photos()));
        cells.add(o.available() ? IN_STOCK : OUT_OF_STOCK);
        // Номер производителя: у колеса его нет (основной номер — задача 0338),
        // колонка остаётся, как в образце, где она пуста.
        cells.add(null);
        return cells;
    }

    private static void writeRow(Writer w, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                w.write(';');
            }
            w.write('"');
            String value = cells.get(i);
            if (value != null) {
                w.write(value.replace("\"", "\"\""));
            }
            w.write('"');
        }
        w.write("\r\n");
    }
}
