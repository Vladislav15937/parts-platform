package ru.partsflow.publishing.drom;

import ru.partsflow.catalog.OemNumbers;
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
        element(w, "name", nameOf(offer));
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

        // У площадки это поле двузначное: «Новое» или «Б/у». Контрактная
        // и восстановленная для неё б/у — а то, что контрактная не то же,
        // что восстановленная, покупатель читает в наименовании и описании.
        // Уедет ли «Контракт» в прайс своим словом — вопрос владельцу
        // продукта, названный в tasks/0039 и tasks/0166: сегодня поведение
        // прежнее, и нового обещания покупателю мы не даём.
        //
        // Без состояния («Не указано», задача 0322) элемента нет. До писателя
        // такая позиция не доезжает — её отсекает отбор (HAS_CONDITION), —
        // но падение здесь случилось бы посреди записи ответа: 200 с обрывком
        // тела, которое площадка читает как «снять все объявления».
        element(w, "condition", offer.condition() == null ? null : switch (offer.condition()) {
            case NEW -> "Новое";
            case USED, REFURBISHED, CONTRACT -> "Б/у";
        });
        // Оригинальность своим тегом, словом клиента дословно (задача 0277).
        // Имя тега наше, как у sklad и nalichie: формат прайса запчастей
        // выбираем мы, а техспециалист Дрома настраивает разбор под него.
        // Взято по имени поля самой карточки Дрома (autoPartsAuthenticity),
        // чтобы при настройке его было с чем сопоставить; ответа площадки
        // о своём имени тега нет (tasks/0280).
        element(w, "authenticity", offer.authenticity());
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
        placement(w, offer.placement());

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
     * Где лежит и через сколько дней можно забрать.
     *
     * <p><b>Зачем срок.</b> До этого в прайс уходило булево «есть», и
     * покупатель, приехавший за деталью сегодня, узнавал про дорогу
     * на дальний склад уже по телефону. Текст наличия и вилку дней задаёт
     * владелец каждому складу отдельно (задача 0008).
     *
     * <p><b>Отдельными тегами, а не строкой в описании.</b> Эталон площадки
     * для «Автозапчастей» просит этого прямо: «размещайте информацию о каждом
     * параметре в отдельные теги». Склеенное в одну фразу площадка не разберёт
     * ни фильтром, ни сортировкой, а разбирает она именно теги.
     *
     * <p><b>Имена элементов наши, и это решение исполнителя.</b> Схему прайса
     * запчастей выбираем мы — Дром обещает «настроим разбор под ваш формат»,
     * и его техспециалист настраивает разбор под нас при подключении клиента
     * (в отличие от прайса колёс, где схема опубликована площадкой и имена
     * взяты дословно). Соседний элемент склада назван {@code sklad},
     * поэтому и эти три — тем же письмом.
     *
     * <p><b>Незаданное не пишется вовсе.</b> Склад, которому владелец ничего
     * не задавал, ведёт себя ровно как до этой задачи: пустой
     * {@code <nalichie>} площадка читает как заполненное пустым значением,
     * а выдуманное нами «в наличии» уехало бы покупателю от имени разборки,
     * которая этого не обещала.
     */
    private void placement(XMLStreamWriter w, DromOffer.Placement placement)
            throws XMLStreamException {
        if (placement == null) {
            return;
        }
        element(w, "sklad", placement.warehouses());
        element(w, "nalichie", placement.availabilityNote());

        // Ноль дней и незаданные дни покупателю говорят одно — ждать не надо;
        // элемент с нулём был бы шумом в объявлении. Зато у вилки «0–4»
        // значима и нижняя граница, поэтому решает пара целиком, а не каждое
        // число по себе.
        if (placement.namesLeadTime()) {
            element(w, "srok_zakaza_ot", number(placement.orderDaysFrom()));
            element(w, "srok_zakaza_do", number(placement.orderDaysTo()));
        }
    }

    /** Ноль обязан дойти как «0», а не потеряться пустой строкой. */
    private static String number(Integer value) {
        return value == null ? null : String.valueOf(value);
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
     * у {@link #nameOf}.
     */
    static String descriptionOf(DromOffer offer) {
        if (offer.clientText()) {
            return offer.description();
        }
        return append(own(offer), offer);
    }

    /**
     * Наименование в прайсе: у перенесённой с Дрома позиции — заголовок
     * клиента как есть, у прочих — очищенный {@link #plainName}; у обеих
     * без номеров позиции на конце ({@link #withoutNumbers}, задача 0331 —
     * единственное исключение из 0275 ниже).
     *
     * <p><b>Текст клиента не чистится и не дополняется (задача 0275).</b>
     * Решение владельца продукта от 13 октября 2026, записано в задаче
     * дословно: «мы должны повторить объявление клиента на сто процентов…
     * И именно в том виде как это выглядит у него». Чистка заголовка
     * и собранное описание существуют ради позиций, текст которых пишем мы
     * (приёмщику некогда, заголовок собран генератором); у перенесённой оба
     * написал клиент, и любая наша правка — это объявление, которого
     * у него не было: «Состояние: б/у.» там, где описания нет вовсе,
     * «(восст.)», вырезанное из его заголовка, приписки, которых он
     * не писал. Пустое описание так и уходит пустым — элемента нет.
     */
    static String nameOf(DromOffer offer) {
        return offer.clientText()
                ? withoutNumbers(offer.name(), offer)
                : withoutNumbers(plainName(offer.name()), offer);
    }

    /**
     * Наименование без номеров этой позиции на конце (задача 0331).
     *
     * <p>Решение владельца продукта 18.10.2026, дословно: «В объявление дром
     * номера должны идти отдельно а названи отдельно». Номер производителя
     * и кросс-номера уже уходят своими тегами {@code oem_number}
     * и {@code analog_numbers}, а генератор заголовка ставит номер последним
     * словом — в названии объявления он был повторением. Заголовок в базе
     * не меняется: витрина, этикетка и карточка читают его глазами, и номер
     * там нужен. Перенесённые с Дрома позиции (0275) теряют только номер —
     * ответ владельца «Всё да» на вопрос 1 задачи; прочий текст клиента
     * уходит как есть.
     *
     * <p><b>Срезаются номера позиции, а не «похожее на номер».</b> Регулярка
     * срезала бы год «2006», кузов «E46», модель «2106». Сравнение — по
     * {@link OemNumbers#normalize}, то есть «81170-33670» в заголовке узнаётся
     * и при хранимом «8117033670», и русская «С» — при латинской.
     *
     * <p><b>Только с конца и каждый номер один раз</b> (решение исполнителя,
     * предложенное задачей): номер попадает в заголовок последним словом,
     * и срез ровно его повторяет — номер, совпавший со словом в середине
     * («Дверь Kia Rio 2012 2012», основной «2012»), там и остаётся. Цена
     * названа: заголовок, где номер совпал с последним словом не-номера
     * (основной «2012» у «Дверь Kia Rio 2012»), потеряет это слово.
     *
     * <p>Срез, после которого не осталось ничего, не делается: объявление
     * без названия хуже названия с номером.
     */
    static String withoutNumbers(String title, DromOffer offer) {
        if (title == null || title.isBlank()) {
            return title;
        }
        java.util.Set<String> left = numbersOf(offer);
        String rest = title;
        while (!left.isEmpty()) {
            int end = rest.length();
            while (end > 0 && isNameSeparator(rest.charAt(end - 1))) {
                end--;
            }
            int longest = left.stream().mapToInt(String::length).max().orElse(0);
            int cut = -1;
            String matched = null;
            for (int start = end - 1; start >= 0; start--) {
                if (isNameSeparator(rest.charAt(start))
                        || (start > 0 && !isNameSeparator(rest.charAt(start - 1)))) {
                    continue;
                }
                String tail = rest.substring(start, end);
                if (!looksLikeNumber(tail)) {
                    break;
                }
                String normalized = OemNumbers.normalize(tail);
                if (normalized != null && left.contains(normalized)) {
                    cut = start;
                    matched = normalized;
                    break;
                }
                if (normalized != null && normalized.length() > longest) {
                    break;
                }
            }
            if (cut < 0) {
                break;
            }
            int head = cut;
            while (head > 0 && isNameSeparator(rest.charAt(head - 1))) {
                head--;
            }
            if (head == 0) {
                break;
            }
            rest = rest.substring(0, head);
            left.remove(matched);
        }
        return rest;
    }

    /**
     * Приведённые номера позиции: основной и кросс-номера, каждый ещё и по частям
     * {@link OemNumbers#list} — основной, сохранённый до задачи 0323 строкой
     * «8414012530, 8414012531», в заголовке стоит теми же двумя словами.
     */
    private static java.util.Set<String> numbersOf(DromOffer offer) {
        java.util.Set<String> numbers = new java.util.LinkedHashSet<>();
        List<String> all = new java.util.ArrayList<>();
        if (offer.oemNumber() != null) {
            all.add(offer.oemNumber());
        }
        if (offer.analogNumbers() != null) {
            all.addAll(offer.analogNumbers());
        }
        for (String number : all) {
            addNormalized(numbers, number);
            for (String piece : OemNumbers.list(number)) {
                addNormalized(numbers, piece);
            }
        }
        return numbers;
    }

    private static void addNormalized(java.util.Set<String> numbers, String number) {
        String normalized = OemNumbers.normalize(number);
        if (normalized != null) {
            numbers.add(normalized);
        }
    }

    /** Пробел любого вида, запятая или точка с запятой — между словами названия. */
    private static boolean isNameSeparator(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == ',' || c == ';';
    }

    /**
     * Хвост, который может быть номером: каждое слово после {@link OemNumbers#clean}
     * — латиница, цифры и знаки внутри номера. Без этой проверки «2106 перед»
     * привёлся бы к «2106» (русские буквы приведение выкидывает) и совпал
     * с номером «2106».
     */
    private static boolean looksLikeNumber(String tail) {
        for (String word : tail.split("[\\s\\u00A0\\u2007\\u202F,;]+")) {
            if (!word.isEmpty() && !OemNumbers.clean(word).matches("[A-Za-z0-9./_\\-]+")) {
                return false;
            }
        }
        return true;
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
            // «Левая, Правая» на карточке клиента. Какое слово Дром ждёт
            // для «обе», не известно (вопрос Д в tasks/0280, ответа нет):
            // пишем оба своих слова через запятую — так же, как номера
            // в analog_numbers, — чтобы техспециалисту площадки было что
            // сопоставить при настройке разбора. Промолчать было бы хуже:
            // сторона у клиента на карточке есть, а у нас пропала бы.
            case BOTH -> "лево,право";
        };
    }

    private String longitudinal(LongitudinalSide side) {
        return side == null ? null : switch (side) {
            case FRONT -> "перед";
            case REAR -> "зад";
            // Слово Дрома для «обе» неизвестно (вопрос Д2 в tasks/0280) —
            // временно, той же формой, что у поперечной оси: оба своих слова
            // через запятую (задача 0283).
            case BOTH -> "перед,зад";
        };
    }

    private String vertical(VerticalSide side) {
        return side == null ? null : switch (side) {
            case UPPER -> "верх";
            case LOWER -> "низ";
            case BOTH -> "верх,низ";
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
