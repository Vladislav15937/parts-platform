package ru.partsflow.inventory;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Денежное поле карточки: как его зовут человеку и чем грозит ноль в нём.
 *
 * <p><b>Зачем отдельным перечислением.</b> Четыре поля двигаются одной
 * арифметикой ({@link PriceOperation}), но отказ обязан называть <b>своё</b>
 * поле своими словами. Пока слова были одни на всех, «уменьшить
 * себестоимость до нуля» отвечало «Нулевая цена в прайс не уедет — поставьте
 * цену числом»: себестоимости в прайсе нет и не было, то есть сообщение
 * объясняло отказ выдуманным последствием. Владелец, который поверит такому
 * тексту, пойдёт искать несуществующую связь между себестоимостью
 * и объявлением.
 *
 * <p>Отсюда падежи прямо в перечислении: «Отрицательной <i>себестоимости</i>
 * не бывает» — единственный способ собрать эту фразу из кусков, не соврав.
 */
public enum MoneyField {

    PRICE("price", "цена", "цены", "Нулевая цена в прайс не уедет"),

    MIN_PRICE("minPrice", "минимальная цена", "минимальной цены", null),

    COST_PRICE("costPrice", "себестоимость", "себестоимости", null),

    /**
     * Ноль здесь — след переезда, а не бесплатная установка: в выгрузке
     * прежней системы им заполняли пустоту, и у прогонного клиента таких
     * 367 из 381.
     */
    INSTALLATION_PRICE("installationPrice", "цена установки", "цены установки",
            "Ноль в цене установки читается как незаполненное поле,"
                    + " а не как бесплатная установка");

    private final String key;
    private final String nominative;
    private final String genitive;
    private final String zeroMeans;

    MoneyField(String key, String nominative, String genitive, String zeroMeans) {
        this.key = key;
        this.nominative = nominative;
        this.genitive = genitive;
        this.zeroMeans = zeroMeans;
    }

    /** Имя поля в теле запроса правки списком. */
    public String key() {
        return key;
    }

    /** «цена», «себестоимость» — как поле подписано на экране. */
    public String nominative() {
        return nominative;
    }

    /** «цены», «себестоимости» — для фразы «Отрицательной … не бывает». */
    public String genitive() {
        return genitive;
    }

    /**
     * Чем именно плох ноль в этом поле, или {@code null}, если сказать
     * нечего.
     *
     * <p>Молчание тут лучше общих слов: выдуманное последствие хуже
     * отсутствующего — по нему человек делает выводы о системе.
     */
    public String zeroMeans() {
        return zeroMeans;
    }

    /** @return поле по имени из запроса или {@code null}, если оно не денежное */
    public static MoneyField of(String key) {
        for (MoneyField field : values()) {
            if (field.key.equals(key)) {
                return field;
            }
        }
        return null;
    }

    /**
     * Имена денежных полей.
     *
     * <p>Считается по перечислению, а не пишется вторым списком: разойдясь,
     * они дали бы поле, которое одна проверка пускает, а другая нет.
     */
    public static Set<String> keys() {
        return Arrays.stream(values()).map(MoneyField::key).collect(Collectors.toUnmodifiableSet());
    }
}
