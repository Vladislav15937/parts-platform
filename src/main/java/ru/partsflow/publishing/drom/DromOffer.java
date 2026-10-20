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
 * @param placement    где лежит и через сколько дней можно забрать —
 *                     см. {@link Placement}. {@code null} — про размещение
 *                     сказать нечего; то же значит и {@code Placement}
 *                     с пустыми полями, поэтому прайс всегда собирает объект,
 *                     а {@code null} остаётся для тех, кто про склад не знает
 * @param partKind     вид детали справочником — «Фара», «Стартер»; по нему
 *                     площадка раскладывает товар по разделам, и заголовка
 *                     ей для этого мало
 * @param availableQty свободный остаток, а не общий. В файл он не идёт — у Bazon
 *                     количества нет, а наличие одним словом (задача 0339)
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
 * @param bazonFits    готовый блок «&lt;Вид&gt; подходит:» из файла выгрузки Bazon,
 *                     дословно (задача 0337); {@code null} — блока нет. Идёт в описание
 *                     после «Запчасть снята с:», до текстового блока
 * @param textBlock    дополнительный текст объявления, введённый владельцем.
 *                     Приезжает из прежней системы колонкой «Текстовый блок»
 *                     и до правки не доходил до покупателя вовсе: владелец
 *                     писал, а видел это только он сам
 * @param videoUrl     ссылка на ролик о детали, тоже из прежней системы
 * @param installationNote готовая строка про стоимость установки — та,
 *                     что владелец включил у этой выгрузки, уже с подставленной
 *                     ценой. Именно строка, а не сама цена: {@code DromOffer}
 *                     по определению «ровно те данные, что уходят в один
 *                     offer», и цена установки — поле внутреннее, решение
 *                     о её показе принадлежит выгрузке, а не позиции.
 *                     {@code null} — приписка выключена либо услуги нет
 * @param expectedNote приставка в начало объявления, написанная владельцем
 *                     у этой выгрузки: у товара в пути — «ожидается
 *                     поступление», у отложенного целиком — своя (задача 0339,
 *                     переключатель «Выгружать товары в резерве»). Встаёт
 *                     <b>первой</b> в описании: остальное рассказывает
 *                     про деталь, а это — про то, когда её забрать.
 *                     {@code null} — приставки нет
 * @param authenticity оригинальность словом площадки, дословно («Оригинал»),
 *                     как её написал клиент на карточке Дрома (задача 0277).
 *                     {@code null} — не указана, и тег не пишется
 * @param clientText   заголовок и описание — текст клиента, перенесённый
 *                     с его объявления на Дроме, и уходят они дословно:
 *                     без чистки заголовка, без собранного описания и без
 *                     приписок (задача 0275). Признак — номер объявления
 *                     Дрома у позиции: другого носителя у «это объявление
 *                     клиента» нет, а схему ради второго заводить незачем.
 *                     С 0335 касается только описания: название в файле
 *                     собирается по формуле Bazon у всех
 * @param partName     вид детали так, как его ввёл клиент ({@code part_name.name}),
 *                     — первое слово названия в файле (задача 0335); {@code null} —
 *                     наименование не заведено, тогда берётся {@code partKind}
 * @param yearText     «Год выпуска» текстом как у Bazon ({@code 2010}, {@code 03-08});
 *                     пуст — берётся {@code year}
 * @param titleBrand   марка для названия: своя, иначе донора, иначе ПЕРВАЯ
 *                     из применимости по порядку строк
 * @param titleModel   модель для названия — так же
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
        Placement placement,
        List<String> photos,
        String carBrand,
        String carModel,
        String bodyCode,
        String engineCode,
        Integer year,
        boolean fromDonor,
        String textBlock,
        String videoUrl,
        String installationNote,
        String expectedNote,
        String authenticity,
        boolean clientText,
        String partName,
        String yearText,
        String titleBrand,
        String titleModel,
        String bazonFits) {

    /** Состав до задачи 0337: без готового блока «подходит» из файлов Bazon. */
    public DromOffer(String orderCode, String name, String partKind, String description,
                     BigDecimal price, BigDecimal availableQty, PartCondition condition,
                     String manufacturer, String oemNumber, List<String> analogNumbers,
                     LateralSide lateralSide, LongitudinalSide longitudinalSide,
                     VerticalSide verticalSide, String color, String marking,
                     Placement placement, List<String> photos, String carBrand,
                     String carModel, String bodyCode, String engineCode, Integer year,
                     boolean fromDonor, String textBlock, String videoUrl,
                     String installationNote, String expectedNote, String authenticity,
                     boolean clientText, String partName, String yearText,
                     String titleBrand, String titleModel) {
        this(orderCode, name, partKind, description, price, availableQty, condition,
                manufacturer, oemNumber, analogNumbers, lateralSide, longitudinalSide,
                verticalSide, color, marking, placement, photos, carBrand, carModel,
                bodyCode, engineCode, year, fromDonor, textBlock, videoUrl,
                installationNote, expectedNote, authenticity, clientText,
                partName, yearText, titleBrand, titleModel, null);
    }

    /** Прежний состав до задачи 0335: без полей названия в файле. */
    public DromOffer(String orderCode, String name, String partKind, String description,
                     BigDecimal price, BigDecimal availableQty, PartCondition condition,
                     String manufacturer, String oemNumber, List<String> analogNumbers,
                     LateralSide lateralSide, LongitudinalSide longitudinalSide,
                     VerticalSide verticalSide, String color, String marking,
                     Placement placement, List<String> photos, String carBrand,
                     String carModel, String bodyCode, String engineCode, Integer year,
                     boolean fromDonor, String textBlock, String videoUrl,
                     String installationNote, String expectedNote, String authenticity,
                     boolean clientText) {
        this(orderCode, name, partKind, description, price, availableQty, condition,
                manufacturer, oemNumber, analogNumbers, lateralSide, longitudinalSide,
                verticalSide, color, marking, placement, photos, carBrand, carModel,
                bodyCode, engineCode, year, fromDonor, textBlock, videoUrl,
                installationNote, expectedNote, authenticity, clientText,
                null, null, null, null, null);
    }

    /**
     * Позиция без оригинальности и с текстом, который собираем мы, —
     * прежний состав, до задач 0277 и 0275.
     *
     * <p>Оригинальность приезжает только с переносом с Дрома; у позиции,
     * заведённой приёмкой или переносом из прежней системы, её нет,
     * и всякое место, собирающее предложение руками, писало бы {@code null}.
     * Признак «текст клиента» нужен ровно одному источнику (прайсу,
     * читающему номер объявления), а остальным «текст наш» и есть прежнее
     * поведение.
     */
    public DromOffer(String orderCode, String name, String partKind, String description,
                     BigDecimal price, BigDecimal availableQty, PartCondition condition,
                     String manufacturer, String oemNumber, List<String> analogNumbers,
                     LateralSide lateralSide, LongitudinalSide longitudinalSide,
                     VerticalSide verticalSide, String color, String marking,
                     Placement placement, List<String> photos, String carBrand,
                     String carModel, String bodyCode, String engineCode, Integer year,
                     boolean fromDonor, String textBlock, String videoUrl,
                     String installationNote, String expectedNote) {
        this(orderCode, name, partKind, description, price, availableQty, condition,
                manufacturer, oemNumber, analogNumbers, lateralSide, longitudinalSide,
                verticalSide, color, marking, placement, photos, carBrand, carModel,
                bodyCode, engineCode, year, fromDonor, textBlock, videoUrl,
                installationNote, expectedNote, null, false, null, null, null, null, null);
    }

    /**
     * Где деталь лежит и через сколько дней её можно забрать.
     *
     * <p><b>Почему одним типом, а не тремя полями рядом.</b> Текст наличия
     * и вилка дней принадлежат <b>одному</b> складу — тому, по которому
     * уезжает срок. Разложенные по трём соседним компонентам, они могли бы
     * приехать от разных складов: «в наличии» с ближнего и «2–4 дня»
     * с дальнего — объявление, которое врёт покупателю, при полностью
     * исправном складе. Собранные в один тип, разойтись они не могут
     * структурно, и это сильнее любого теста.
     *
     * @param warehouses      где деталь лежит — <b>все</b> склады с остатком,
     *                        через запятую: раскладка ведётся по складам,
     *                        и назвать один из двух значило бы отправить
     *                        половину покупателей не туда. {@code null} —
     *                        лежать негде (проданная позиция остаётся
     *                        в прайсе, но склада у неё уже нет)
     * @param availabilityNote текст наличия <b>ближнего</b> из них: «в наличии»,
     *                        «под заказ». {@code null} — владелец этому складу
     *                        текста не задавал, и тогда в объявление не уходит
     *                        ничего: молча ничего не выдумываем
     * @param orderDaysFrom   нижняя граница вилки дней заказа того же склада;
     *                        ноль — «забрать можно сегодня», {@code null} —
     *                        «срок не называем»
     * @param orderDaysTo     верхняя граница вилки
     */
    public record Placement(String warehouses, String availabilityNote,
                            Integer orderDaysFrom, Integer orderDaysTo) {

        /**
         * Сказано ли про срок что-нибудь, что стоит везти покупателю.
         *
         * <p>Вилка «0–0» и незаданная вилка значат для него одно: ждать
         * не надо, — и элемент со нулём в объявлении был бы шумом. А вот
         * «0–4» значимо целиком: нижняя граница тут говорит «может быть,
         * и сегодня», и терять её нельзя.
         */
        public boolean namesLeadTime() {
            return orderDaysFrom != null && orderDaysFrom > 0
                    || orderDaysTo != null && orderDaysTo > 0;
        }
    }
}
