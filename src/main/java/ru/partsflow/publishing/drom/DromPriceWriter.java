package ru.partsflow.publishing.drom;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.LongitudinalSide;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

/**
 * Потоковая запись прайса запчастей Дрома — в формате файла Bazon.
 *
 * <p><b>Почему схема чужая (задача 0333).</b> Дром обещает «настроим разбор
 * под ваш формат», и до 19 октября 2026 формат выбирали мы:
 * {@code <offers><offer>} с именами из эталона площадки и своими тегами склада
 * и срока. Но клиенты переезжают с Bazon, и у каждого на Дроме уже стоит
 * прайс-лист, разбор которого техспециалист площадки однажды настроил под
 * файл Bazon. Наш файл того же вида Дром читает тем же разбором: клиент
 * меняет в прайс-листе только адрес файла, без заявки и без ожидания
 * настройки. Решение владельца продукта от 19.10.2026 — «на 100%
 * соответствовать логике формирования … файлов выгрузки в базон», формат
 * заменён целиком (решение 1 задачи 0333), второго нет.
 *
 * <p>Образец — файлы Bazon, сохранённые владельцем
 * ({@code docs/bazon-parity.md}, «Обход 19.10.2026»): заголовок с пробелом
 * перед {@code ?>}, корень {@code <parts>}, каждая позиция {@code <part>},
 * каждый тег на своей строке, файл кончается {@code </parts>} без перевода
 * строки. Теги — {@code id, title, price, deliveryCost, condition, state,
 * partname, leftRight, frontRear, [color], [marking], [manufacturer],
 * manufactureNumber, analogsNumbers, photoUrls, description}; пустое
 * значение пишется <b>пустым элементом</b>, отсутствуют только три в скобках.
 * Тегов, которых у Bazon нет — количества, флага наличия, склада, срока,
 * применимости отдельными тегами, вертикальной оси, оригинальности, — нет
 * и у нас: разбор, настроенный под Bazon, их не читает, а отсутствующий
 * {@code id} или {@code state} ломает.
 *
 * <p><b>Почему StAX.</b> У крупного клиента до 50 000 позиций, а полный прайс
 * Дром забирает по постоянному URL и лимитом 5 МБ не ограничен — то есть файл
 * будет большим. Сборка в DOM или в String съест гигабайты и уронит
 * приложение у самого ценного клиента. Вход — {@link Iterator}, чтобы и набор
 * позиций не материализовался.
 */
@org.springframework.stereotype.Component
public class DromPriceWriter {

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

    /**
     * Заголовок пишется байтами, а не {@code writeStartDocument}: StAX ставит
     * {@code ?>} вплотную, у Bazon перед ним пробел. Разбор площадки это,
     * скорее всего, не различает, но проверить нам нечем, а «идентично»
     * стоит дешевле догадки.
     */
    private static final byte[] DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Слово наличия у Bazon — у всех позиций всех четырёх файлов, и другого
     * в файле нет (задача 0339, решение владельца 19.10.2026): позиции,
     * которую нельзя купить, в файле нет вовсе — её не пускает отбор
     * ({@code DromPriceGenerator.IN_FILE}). Отложенная, которую выгрузка
     * выгружает, и товар в пути уходят этим же словом, с приставкой первой
     * строкой описания, если она задана.
     */
    static final String IN_STOCK = "В наличии";

    /**
     * Предел строки кросс-номеров в файле, знаков: в файлах Bazon 19.10.2026
     * {@code analogsNumbers} ни разу не длиннее, и у 55 позиций он ровно
     * такой и оборван посреди номера (задача 0338, «Обход 19.10.2026»).
     */
    static final int ANALOGS_LIMIT = 1024;

    /**
     * @return сколько позиций записано — попадает в {@code publication_log},
     *         чтобы было с чем сверить «Замечания к товарам» в кабинете Дрома
     */
    public int write(OutputStream out, Iterator<DromOffer> offers) throws XMLStreamException {
        try {
            out.write(DECLARATION);
        } catch (IOException e) {
            throw new XMLStreamException("Не удалось начать прайс Дрома", e);
        }
        XMLStreamWriter writer = FACTORY.createXMLStreamWriter(out, "UTF-8");
        int written = 0;
        try {
            writer.writeStartElement("parts");
            writer.writeCharacters("\n");

            while (offers.hasNext()) {
                writePart(writer, offers.next());
                writer.writeCharacters("\n");
                written++;
            }

            // Без перевода строки в конце — как у Bazon.
            writer.writeEndElement();
            writer.writeEndDocument();
            writer.flush();
        } finally {
            writer.close();
        }
        return written;
    }

    private void writePart(XMLStreamWriter w, DromOffer offer) throws XMLStreamException {
        w.writeStartElement("part");
        w.writeCharacters("\n");

        // id должен быть стабильным между выгрузками: по нему Дром узнаёт
        // позицию при обновлении. Меняется — и вместо обновления получится
        // новое объявление вместо старого. Каким числом его писать у
        // переехавшего с Bazon клиента — задача 0334.
        element(w, "id", offer.orderCode());
        element(w, "title", nameOf(offer));
        element(w, "price", price(offer.price()));
        // У Bazon пуст во всех 20 324 позициях; доставку мы не считаем вовсе.
        element(w, "deliveryCost", null);
        // Без состояния («Не указано», задача 0322) элемент пуст. До писателя
        // такая позиция не доезжает — её отсекает отбор (HAS_CONDITION), —
        // но падение здесь случилось бы посреди записи ответа: 200 с обрывком
        // тела, которое площадка читает как «снять все объявления».
        element(w, "condition", condition(offer.condition()));
        // Наличие — одним словом Bazon у всех: что нельзя купить, в файл
        // не попадает (задача 0339). Отложенная деталь уходит, только если
        // выгрузка резерв выгружает, — и тогда с приставкой в описании.
        element(w, "state", IN_STOCK);
        // Вид детали отдельным полем: по нему площадка кладёт товар в раздел.
        element(w, "partname", offer.partKind());
        element(w, "leftRight", lateral(offer.lateralSide()));
        element(w, "frontRear", longitudinal(offer.longitudinalSide()));

        // Три необязательных: у Bazon тега нет, когда поле не задано.
        optional(w, "color", offer.color());
        optional(w, "marking", offer.marking());
        optional(w, "manufacturer", offer.manufacturer());

        element(w, "manufactureNumber", offer.oemNumber());
        element(w, "analogsNumbers", joinAnalogs(offer.analogNumbers()));

        // Снимки — по элементу на ссылку, главный первым: площадка ставит
        // первую ссылку обложкой. Без снимков контейнер остаётся пустым,
        // а не пропадает, — так у Bazon.
        w.writeStartElement("photoUrls");
        w.writeCharacters("\n");
        if (offer.photos() != null) {
            for (String photo : offer.photos()) {
                element(w, "photoUrl", photo);
            }
        }
        w.writeEndElement();
        w.writeCharacters("\n");

        description(w, descriptionOf(offer));
        w.writeEndElement();
    }

    /**
     * Описание — в {@code CDATA}, как у Bazon; пустое — {@code <description/>}
     * вплотную к {@code </part>}, тоже как у него (2 позиции из 1 724
     * в {@code drom-new-parts.xml}).
     *
     * <p>{@code ]]>} внутри текста закрыл бы секцию раньше времени, и остаток
     * описания площадка прочла бы разметкой — то есть файл развалился бы
     * на чужом тексте. Поэтому такая последовательность режется на две
     * секции: прочитанный текст остаётся тем же.
     */
    private void description(XMLStreamWriter w, String text) throws XMLStreamException {
        if (text == null || text.isEmpty()) {
            w.writeEmptyElement("description");
            return;
        }
        w.writeStartElement("description");
        String[] pieces = text.split("]]>", -1);
        for (int i = 0; i < pieces.length; i++) {
            String piece = (i == 0 ? "" : ">") + pieces[i] + (i < pieces.length - 1 ? "]]" : "");
            w.writeCData(piece);
        }
        w.writeEndElement();
        w.writeCharacters("\n");
    }

    /**
     * Цена — целым числом: у Bazon копеек нет ни у одной позиции. Копейки
     * округляются <b>вверх</b> (решение 2 задачи 0333, до обхода настройки
     * «Округлять до» у Bazon): вниз деталь однажды уйдёт дешевле, чем
     * владелец задал, и узнает он об этом от покупателя.
     *
     * <p>Незаполненная цена уходит «0» (задача 0339): у Bazon незаполненное
     * поле в файле стоит нулём, и позиция с ним в файле есть, а пустой
     * {@code <price>} площадка прочла бы позицией без цены.
     */
    static String price(BigDecimal price) {
        return price == null ? "0" : price.setScale(0, RoundingMode.CEILING).toPlainString();
    }

    /**
     * Состояние словами Bazon: {@code контрактный}, {@code б/у}, {@code новый}.
     *
     * <p>Контрактная уходит своим словом, а не «б/у», — у Bazon так у 20 321
     * позиции из 20 324 (закрывает вопрос задач 0039 и 0166). Восстановленной
     * в файлах Bazon не видели; прежде она уходила «Б/у», и так остаётся,
     * пока не увидим (повестка обхода).
     */
    static String condition(PartCondition condition) {
        return condition == null ? null : switch (condition) {
            case NEW -> "новый";
            case USED, REFURBISHED -> "б/у";
            case CONTRACT -> "контрактный";
        };
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
     * уехали полями {@code leftRight}, {@code frontRear} и {@code condition}
     * (верх и низ — строкой «Расположение» в собранном описании), то есть
     * в заголовке они были повторением. С 0335 название в файле собирается
     * по формуле Bazon ({@link DromTitle}); этот метод остаётся запасным,
     * когда у позиции нет вида.
     */
    static String plainName(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }
        // Пометки состояния берутся у перечисления, а не перечислены здесь
        // регуляркой: забытая в ней пометка уехала бы покупателю прямо
        // в названии товара (задача 0039).
        String marks = PartCondition.titleMarks().stream()
                .map(java.util.regex.Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        String plain = title
                .replaceAll("\\s*(?:" + marks + ")", "")
                // Без регистра: с 24.10.2026 стороны в названии с заглавной
                // («Перед. Лев.», ValueWords), а у собранных раньше — строчными.
                .replaceAll("(?iu)\\s+(?:перед|задн|лев|прав|верх|ниж)\\.", "");
        return plain.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * Описание позиции — по устройству Bazon (задача 0337).
     *
     * <p>Начинается переводом строки; дальше блоки через пустую строку, пустой
     * блок не пишется вместе со своим разделителем, конца строки после
     * последнего нет:
     * <ol>
     *   <li>«Комментарий (для клиентов)» карточки — как набран, переводы строк
     *       внутри не трогаем;</li>
     *   <li>{@code Запчасть снята с: Марка Модель [Год] Кузов Двигатель} —
     *       первые значения полей, те же, что в названии (0335); без номера
     *       и положения;</li>
     *   <li>«Текстовый блок» — дословно.</li>
     * </ol>
     * Четвёртого блока, «&lt;Вид&gt; подходит:», пока нет: его текст у Bazon —
     * из справочника поколений, которого у нас нет (0341), а перенесённый текст
     * ждёт ответа на вопрос, где его хранить (названо в PR задачи 0337).
     *
     * <p>Чего здесь нет намеренно, хотя раньше собиралось: номера производителя,
     * состояния, расположения, ссылки на ролик. Человек, переехавший с Bazon,
     * не писал их и не видел у себя в объявлении; решение владельца
     * 19.10.2026 — «как в базон». Приписки «ожидается поступление» и про
     * установку остались только там, где выгрузка их включила сама
     * (переключатели, по умолчанию выключены): первая — первым блоком, вторая —
     * после текстового блока. Где им место у Bazon, не снято — решает 0339.
     *
     * <p>Позиция, перенесённая с объявления клиента на Дроме, идёт тем же
     * путём: её собственный текст — это «Комментарий» (вывод из «как в базон»,
     * решение 3 задачи 0337).
     */
    static String descriptionOf(DromOffer offer) {
        List<String> blocks = new java.util.ArrayList<>();
        addBlock(blocks, offer.expectedNote());
        addBlock(blocks, offer.description());
        addBlock(blocks, vehicleBlock(offer));
        addBlock(blocks, offer.bazonFits());
        addBlock(blocks, offer.textBlock());
        addBlock(blocks, offer.installationNote());
        return blocks.isEmpty() ? "" : "\n" + String.join("\n\n", blocks);
    }

    private static void addBlock(List<String> blocks, String block) {
        if (block != null && !block.isBlank()) {
            blocks.add(block);
        }
    }

    /**
     * «Запчасть снята с: …». Блока нет у новых деталей (снимать не с чего)
     * и у позиций без машины — нужны марка и модель. Берётся из полей
     * позиции, а не из донора: у «Донор не задан» блок тоже есть.
     */
    static String vehicleBlock(DromOffer offer) {
        if (offer.condition() == PartCondition.NEW) {
            return null;
        }
        String brand = DromTitle.first(offer.titleBrand());
        String model = DromTitle.first(offer.titleModel());
        if (brand == null || model == null) {
            return null;
        }
        List<String> words = new java.util.ArrayList<>(List.of(brand, model));
        String year = DromTitle.year(offer);
        if (year != null) {
            words.add(year);
        }
        String body = DromTitle.first(offer.bodyCode());
        if (body != null) {
            words.add(body);
        }
        String engine = DromTitle.first(offer.engineCode());
        if (engine != null) {
            words.add(engine);
        }
        return "Запчасть снята с: " + String.join(" ", words);
    }

    /**
     * Наименование в прайсе — по формуле Bazon, у всех позиций одинаково
     * (задача 0335, {@link DromTitle}).
     *
     * <p><b>Заголовок клиента с Дрома в файл больше не идёт</b> (решение
     * владельца 19.10.2026 «Всё да, всё как в базон», решение 1 задачи 0335;
     * до этого — 0275, 13 октября: «в том виде как это выглядит у него»).
     * Исходный заголовок остаётся у позиции, но название в файле собирается
     * из её полей, как у Bazon. Описание перенесённой позиции по-прежнему
     * уходит дословно ({@link #descriptionOf}).
     *
     * <p>Нет вида ни от клиента, ни из справочника — собрать нечего, и тогда
     * уходит очищенное хранимое название ({@link #plainName}): пустой
     * {@code <title>} площадка читает как позицию без названия.
     */
    static String nameOf(DromOffer offer) {
        String built = DromTitle.of(offer);
        return built != null ? built : plainName(offer.name());
    }

    /**
     * Кросс-номера одной строкой, как у Bazon (задача 0338): через запятую
     * с пробелом, в порядке карточки, повторы и варианты написания не
     * склеиваются, строка режется <b>по знакам ровно до {@value #ANALOGS_LIMIT}</b> —
     * номер может оборваться посередине.
     *
     * <p>Обрыв посреди номера — не недосмотр, а решение владельца 19.10.2026
     * («Всё как у bazon», {@code tasks/0338}, второй раздел «Ответ владельца»):
     * файлы Bazon режут ровно так («…, 11950-9»), а ввод не ограничивают.
     * Рекомендация «резать на последней целой записи» отклонена. Режется
     * по кодовым точкам, а не по {@code char}: половина суррогатной пары
     * сломала бы XML посреди потока, то есть 200 с обрывком файла.
     */
    private String joinAnalogs(List<String> analogs) {
        if (analogs == null || analogs.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", analogs);
        if (joined.codePointCount(0, joined.length()) <= ANALOGS_LIMIT) {
            return joined;
        }
        return joined.substring(0, joined.offsetByCodePoints(0, ANALOGS_LIMIT));
    }

    /**
     * Сторона по-английски, как у Bazon: {@code left}, {@code right}.
     *
     * <p>Слова для «обе» в файлах Bazon не встретилось (повестка обхода),
     * поэтому — той же формой, что раньше русскими словами: оба через запятую.
     * Промолчать было бы хуже: сторона у клиента на карточке есть, а у нас
     * пропала бы.
     */
    private String lateral(LateralSide side) {
        return side == null ? null : switch (side) {
            case LEFT -> "left";
            case RIGHT -> "right";
            case BOTH -> "left,right";
        };
    }

    /** {@code front}, {@code rear}; «обе» — как у поперечной оси. */
    private String longitudinal(LongitudinalSide side) {
        return side == null ? null : switch (side) {
            case FRONT -> "front";
            case REAR -> "rear";
            case BOTH -> "front,rear";
        };
    }

    /**
     * Элемент на своей строке; пустое значение — пустым элементом
     * {@code <x></x>}, а не пропуском: так у Bazon, и разбор площадки,
     * настроенный под его файл, видит в каждой позиции один и тот же набор.
     */
    private void element(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        w.writeStartElement(name);
        if (value != null && !value.isBlank()) {
            w.writeCharacters(value);
        }
        w.writeEndElement();
        w.writeCharacters("\n");
    }

    /** Необязательный элемент: незаданного нет вовсе — {@code color}, {@code marking}, {@code manufacturer}. */
    private void optional(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value == null || value.isBlank()) {
            return;
        }
        element(w, name, value);
    }
}
