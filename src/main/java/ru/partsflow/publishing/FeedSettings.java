package ru.partsflow.publishing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Настройки сборки прайса: то, что применяется при <b>записи</b> файла,
 * а не при отборе товара в него.
 *
 * <p><b>Почему в {@code settings}, а не колонкой.</b> Правило записано
 * в changeset {@code tenant/027-feed-filters}: колонками делается то,
 * <i>по чему фильтрует SQL прайса</i>, потому что «условие вида
 * {@code settings->>'priceFrom'} читается хуже и индексируется хуже, чем
 * колонка». Наценка в отборе не участвует вовсе — она применяется к уже
 * выбранной строке, когда та едет в поток, — значит ни в один {@code WHERE}
 * не попадает и индексировать её нечем. Колонка ради неё стоила бы миграции
 * у пятисот арендаторов.
 *
 * <p><b>Куда класть следующую настройку.</b> Сюда полем — и всё. Чтение
 * (jsonb → запись) и запись (запись → jsonb) идут Jackson'ом по составу
 * самого record'а, поэтому ни разбор, ни сохранение, ни ответ экрану править
 * не нужно; в {@code marketplace_account.settings} при этом ложится
 * <b>слияние</b>, так что соседний {@code packetId} остаётся на месте.
 * Дальше остаются два шага: применить настройку там, где она действует
 * (цена и число снимков — курсор генератора, приписки к описанию —
 * {@code DromPriceWriter}, имя файла прайса — {@code DromFeedController}),
 * и показать её полем на экране «Выгрузки».
 *
 * <p><b>Применять надо во всех генераторах сразу.</b> Настройка, доехавшая
 * до прайса запчастей и не доехавшая до колёсного или до дельты, даёт три
 * файла с разной ценой одного и того же товара: полный прайс поставит на
 * площадке одну цену, а первая же дельта перебьёт её другой — и заметить
 * это можно будет только по чужому сайту.
 *
 * @param pricePercent  наценка (плюс) или скидка (минус) в процентах;
 *                      {@code null} или ноль — цена уезжает как есть.
 *                      Площадка берёт комиссию, и продавцы закладывают её
 *                      в цену объявления: у живого клиента на прайсе Авито
 *                      стоит −20 %. Цена на складе, на витрине и у продавца
 *                      при этом не меняется — иначе комиссия площадки
 *                      уехала бы в цену для того же товара в зале
 *                      и по телефону
 * @param priceRounding шаг округления результата; {@code null} или ноль —
 *                      не округлять
 * @param photoLimit    сколько снимков уходит в объявление; {@code null} —
 *                      прежние десять, ноль — без ограничения. Площадки
 *                      считают снимки по-разному, и продавцы пользуются ими
 *                      по-разному: где-то десять лишние, где-то мало.
 *                      До этого предел был зашит в сборке прайса, и правка
 *                      его означала релиз
 * @param installationNote дописывать ли к описанию объявления стоимость
 *                      установки этой детали. {@code null} и {@code false} —
 *                      не дописывать: настройка появилась позже прайсов,
 *                      и молча приписать строку к чужим объявлениям она
 *                      не должна. Услуга у клиента заведена и стоит денег,
 *                      а покупатель о ней не знал вовсе: поле «Цена
 *                      установки» есть в карточке и в отборе, а до объявления
 *                      не доезжало ни одной строкой
 * @param installationTemplate текст приписки с подстановкой {@code {цена}};
 *                      пусто — {@link #DEFAULT_INSTALLATION_TEMPLATE}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedSettings(BigDecimal pricePercent, BigDecimal priceRounding,
                           Integer photoLimit, Boolean installationNote,
                           String installationTemplate) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Сколько ссылок на снимки уходит в объявление, пока владелец не сказал
     * иначе.
     *
     * <p>Десять — то, что было зашито в оба генератора с самого начала;
     * появление настройки не должно молча изменить прайс тем, кто её
     * не трогал. Больше в объявление всё равно не уедет, а у переехавшего
     * клиента снимков в среднем пять с половиной на позицию: при тридцати
     * пяти тысячах позиций каждая лишняя ссылка — лишний мегабайт в файле,
     * который площадка забирает целиком.
     */
    public static final int DEFAULT_PHOTO_LIMIT = 10;

    /**
     * Куда в тексте приписки встаёт стоимость установки.
     *
     * <p>По-русски и словом, а не именем чужого поля: текст пишет владелец
     * в поле на экране, и подстановка вида {@code {{Sparepart.InstallationPrice}}}
     * — это имя сущности той системы, из которой клиент переезжает,
     * а не нашей.
     */
    public static final String PRICE_PLACEHOLDER = "{цена}";

    /**
     * Текст приписки, пока владелец не написал свой.
     *
     * <p>Повторяет формулировку ориентира («Стоимость установки на нашем
     * автосервисе: …р.»), потому что клиенты переходят оттуда и ждут тех же
     * слов в своих объявлениях. Поле на экране заполнено этим текстом сразу:
     * приписка, включённая с пустым текстом, была бы включённой возможностью,
     * которая ничего не делает.
     */
    public static final String DEFAULT_INSTALLATION_TEMPLATE =
            "Стоимость установки на нашем автосервисе: " + PRICE_PLACEHOLDER + " р.";

    /** Ничего не задано: прайс собирается так, как собирался всегда. */
    public static FeedSettings none() {
        return new FeedSettings(null, null, null, null, null);
    }

    /**
     * Разбирает {@code marketplace_account.settings}.
     *
     * <p>Читается текстом и разбирается Jackson'ом, а не собирается руками:
     * {@code jsonb} возвращает не тот текст, который в него записали, — свой
     * порядок ключей и свои пробелы.
     *
     * <p>Незнакомые ключи пропускаются намеренно: рядом в тех же настройках
     * лежит {@code packetId} площадки, и падать из-за него значило бы
     * не отдать прайс вовсе.
     */
    public static FeedSettings parse(String json) {
        if (json == null || json.isBlank()) {
            return none();
        }
        try {
            return JSON.readValue(json, FeedSettings.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Настройки выгрузки не читаются: " + json, e);
        }
    }

    /**
     * Настройки для слияния в {@code settings}: {@code settings || ?::jsonb}.
     *
     * <p>Слиянием, а не заменой: в тех же настройках лежит номер прайс-листа
     * в кабинете площадки, и записанный целиком объект стёр бы его — дельты
     * перестали бы уходить вовсе, а по экрану этого не видно.
     */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Настройки выгрузки не записываются", e);
        }
    }

    /**
     * Проверяет то, что нельзя показать площадке.
     *
     * @throws IllegalArgumentException с объяснением — не пятисоткой:
     *         это ошибка ввода, а не поломка
     */
    public FeedSettings validated() {
        if (pricePercent != null && pricePercent.compareTo(new BigDecimal("-100")) <= 0) {
            // Скидка в сто процентов — это «0 ₽» в объявлении, то есть
            // публичное обещание отдать деталь даром. Ровно от этого стоит
            // и отсечка нулевой цены в самом прайсе.
            throw new IllegalArgumentException(
                    "Скидка в 100 % и больше обнуляет цену объявления: площадка покажет «0 ₽»");
        }
        if (priceRounding != null && priceRounding.signum() < 0) {
            throw new IllegalArgumentException("Шаг округления не бывает отрицательным");
        }
        if (photoLimit != null && photoLimit < 0) {
            // Не придирка к вводу: отрицательный предел уронил бы сборку файла
            // посреди записи ответа — заголовки уже отправлены, и площадка
            // получит 200 с нулём байт, то есть команду снять все объявления.
            throw new IllegalArgumentException(
                    "Число снимков не бывает отрицательным: ноль означает «без ограничения»");
        }
        if (Boolean.TRUE.equals(installationNote)
                && installationTemplate != null && !installationTemplate.isBlank()
                && !installationTemplate.contains(PRICE_PLACEHOLDER)) {
            // Приписка без подстановки — это включённая настройка, которая
            // не делает того, ради чего её включили: покупатель прочтёт
            // «стоимость установки» и не увидит суммы. Отказ словами, потому
            // что молча уехавший такой текст заметят только на чужом сайте.
            throw new IllegalArgumentException(
                    "В тексте приписки нет подстановки " + PRICE_PLACEHOLDER
                            + " — цена установки в объявление не попадёт");
        }
        return this;
    }

    /**
     * Строка про стоимость установки, которая допишется к описанию.
     *
     * <p><b>Ноль и пусто означают «услуги нет», а не «бесплатно».</b> В
     * выгрузке прежней системы незаполненная «Установка» приходит нулём,
     * и у прогонного клиента таких было 367 позиций из 381 — changeset
     * {@code tenant/040} их вычистил в {@code NULL} именно поэтому. Написать
     * «Стоимость установки: 0 р.» значит пообещать покупателю бесплатную
     * работу от лица разборки, которая её не обещала.
     *
     * @return {@code null}, если приписка выключена или цены установки нет;
     *         тогда описание собирается как раньше — ни строки, ни подписи
     */
    public String installationNoteFor(BigDecimal installationPrice) {
        if (!Boolean.TRUE.equals(installationNote)) {
            return null;
        }
        if (installationPrice == null || installationPrice.signum() <= 0) {
            return null;
        }
        String template = installationTemplate == null || installationTemplate.isBlank()
                ? DEFAULT_INSTALLATION_TEMPLATE
                : installationTemplate.strip();
        // stripTrailingZeros убирает «.00» из numeric(14,2), а toPlainString
        // не даёт ему превратиться в «1.5E+3»: в объявлении это цена, а не
        // запись числа.
        return template.replace(PRICE_PLACEHOLDER,
                installationPrice.stripTrailingZeros().toPlainString());
    }

    /**
     * Сколько ссылок на снимки уходит в один {@code <offer>}.
     *
     * <p>Ноль — «без ограничения», как у системы, с которой переходят
     * клиенты, а не «ни одного»: объявление без фотографии на разборке
     * не продаёт, и прочитать ноль как запрет значило бы обезглавить прайс
     * молча. Не задано — прежние десять: настройка появилась сегодня,
     * а прайсы у клиентов собираются с прошлого месяца.
     */
    public int photosPerOffer() {
        if (photoLimit == null) {
            return DEFAULT_PHOTO_LIMIT;
        }
        return photoLimit == 0 ? Integer.MAX_VALUE : photoLimit;
    }

    /**
     * Цена, которая уедет на площадку.
     *
     * <p>Округление <b>вверх</b> до шага, а не отбрасывание: округлив вниз,
     * мы однажды отдадим деталь дешевле, чем владелец задал, — и узнает он
     * об этом от покупателя, который приехал по объявлению.
     *
     * <p>Незаполненная цена настройкой не оживает. Ноль у нас означает
     * «цену не назначили» — в выгрузке прежней системы он стоит там, где поле
     * не заполняли, — и наценка на «не назначили» дала бы цену там, где её
     * нет. Из прайса такая позиция отбрасывается запросом, но полагаться
     * на один только {@code WHERE} тут нельзя: дельта и колёса собираются
     * своими запросами.
     */
    public BigDecimal priceFor(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            return price;
        }
        BigDecimal adjusted = price;
        if (pricePercent != null && pricePercent.signum() != 0) {
            adjusted = adjusted.multiply(
                    BigDecimal.ONE.add(pricePercent.movePointLeft(2)));
        }
        if (priceRounding != null && priceRounding.signum() > 0) {
            adjusted = adjusted.divide(priceRounding, 0, RoundingMode.CEILING)
                    .multiply(priceRounding);
        }
        if (adjusted.compareTo(price) == 0) {
            // Ничего не задано — отдаём ту же величину, что и раньше,
            // вместе с её масштабом: «8500.00» в файле не должно превратиться
            // в «8500» от одного появления настроек.
            return price;
        }
        return adjusted.setScale(2, RoundingMode.CEILING);
    }
}
