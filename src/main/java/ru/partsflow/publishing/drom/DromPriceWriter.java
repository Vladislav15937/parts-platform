package ru.partsflow.publishing.drom;

import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.PartCondition;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.VerticalSide;

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

    /** Слово наличия у Bazon — у всех позиций всех четырёх файлов. */
    static final String IN_STOCK = "В наличии";

    /**
     * Слово для позиции без свободного остатка — <b>наше</b>, у Bazon его
     * не видели: в его файле такой позиции нет вовсе. У нас она в файле есть
     * (до задачи 0339 — и в полном прайсе, а дельта иначе снять с продажи
     * не умеет: количества в формате Bazon нет). Понимает ли его разбор,
     * настроенный под Bazon, — вопрос площадке, названный в задаче 0333.
     */
    static final String OUT_OF_STOCK = "Нет в наличии";

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
        // Наличие — словом, а не числом: количества у Bazon в файле нет.
        // Считается по свободному остатку, а не по общему: деталь, отложенную
        // под клиента, площадка показывать не должна — иначе приедут двое
        // за одной запчастью.
        element(w, "state", offer.isAvailable() ? IN_STOCK : OUT_OF_STOCK);
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
     */
    static String price(BigDecimal price) {
        return price == null ? null : price.setScale(0, RoundingMode.CEILING).toPlainString();
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
     *
     * <p>Позиция, перенесённая с объявления клиента на Дроме, — исключение:
     * её описание уходит как лежит в базе, без сборки и приписок; почему —
     * в {@link #nameOf} (с 0335 заголовок клиента в файл не идёт, описание — дословно).
     */
    static String descriptionOf(DromOffer offer) {
        if (offer.clientText()) {
            return offer.description();
        }
        return append(own(offer), offer);
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
     *
     * <p><b>Стоимость установки идёт перед ссылкой на ролик, а не после.</b>
     * Ссылка обязана оставаться последней — за ней текста уже не читают, —
     * а приписка про установку это утверждение о товаре, того же рода,
     * что и текстовый блок. Решает про неё выгрузка: строка приезжает
     * готовой (см. {@code FeedSettings.installationNoteFor}), потому что
     * включена приписка или нет — свойство прайс-листа, а не позиции.
     *
     * <p><b>А «ожидается поступление» идёт первой строкой, перед описанием.</b>
     * Все остальные приписки рассказывают про деталь и потому дописываются
     * в конец; эта рассказывает про то, когда деталь можно забрать, и ниже
     * описания её прочитают последней или не прочитают вовсе — а объявление
     * о товаре, которого на складе нет, без этой строки просто неправда.
     * Решение владельца продукта от 5 сентября 2026
     * ({@code tasks/0007-tovar-v-peremeshchenii-v-obyavlenii.md}): «выгружаем
     * с припиской», и приписка стоит в начале.
     */
    private static String append(String description, DromOffer offer) {
        List<String> parts = new java.util.ArrayList<>();
        if (offer.expectedNote() != null && !offer.expectedNote().isBlank()) {
            parts.add(offer.expectedNote().strip());
        }
        if (description != null && !description.isBlank()) {
            parts.add(description.strip());
        }
        if (offer.textBlock() != null && !offer.textBlock().isBlank()) {
            parts.add(offer.textBlock().strip());
        }
        if (offer.installationNote() != null && !offer.installationNote().isBlank()) {
            parts.add(offer.installationNote().strip());
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
        // Своим словом, а не «б/у» на всё, что не новое: контрактную деталь
        // покупатель ищет именно этим словом, и собранное описание —
        // единственное место, где оно до него доходит (поле condition
        // у площадки двузначное, см. выше).
        if (offer.condition() != null) {
            lines.add("Состояние: " + offer.condition().title() + ".");
        }
        return String.join(" ", lines);
    }

    private static String lateralWord(LateralSide side) {
        return side == null ? null : switch (side) {
            case LEFT -> "левая";
            case RIGHT -> "правая";
            case BOTH -> "левая и правая";
        };
    }

    private static String longitudinalWord(LongitudinalSide side) {
        return side == null ? null : switch (side) {
            case FRONT -> "передняя";
            case REAR -> "задняя";
            case BOTH -> "передняя и задняя";
        };
    }

    private static String verticalWord(VerticalSide side) {
        return side == null ? null : switch (side) {
            case UPPER -> "верхняя";
            case LOWER -> "нижняя";
            case BOTH -> "верхняя и нижняя";
        };
    }

    /**
     * Кросс-номера одной строкой через запятую. Разделитель Bazon — запятая
     * с пробелом и предел в 1 024 знака; это задача 0338.
     */
    private String joinAnalogs(List<String> analogs) {
        if (analogs == null || analogs.isEmpty()) {
            return null;
        }
        return String.join(",", analogs);
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
