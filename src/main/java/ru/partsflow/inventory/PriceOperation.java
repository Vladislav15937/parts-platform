package ru.partsflow.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Как двигают цену: заменой, процентом, суммой или округлением.
 *
 * <p><b>Зачем это на сервере, а не в браузере.</b> Тех же шесть операций
 * просят карточка позиции и правка списком, и посчитанные в двух местах они
 * разойдутся на первом же округлении: 27 000 − 10 % в одном месте станет
 * 24 300, а в другом 24 299,99 — и в прайс уедет цена на копейку ниже той,
 * что владелец видел на экране. Поэтому расчёт один, а поверхностей две.
 *
 * <p><b>Считается в {@code BigDecimal}, а не в {@code double}.</b> Проценты
 * от 27 000 в двоичной плавающей дают 24 299,999999999996 — цена, которую
 * никто не назначал.
 *
 * <p><b>Отрицательной цены не бывает, и это отказ словами, а не ноль
 * и не минус.</b> «Уменьшить на сумму» больше цены — самая частая опечатка
 * (разряд мимо), и молча положить ноль значит выставить деталь даром:
 * нулевая цена в прайс не уезжает, то есть объявление пропадёт, и владелец
 * узнает об этом через несколько дней по опустевшей выдаче площадки.
 *
 * <p><b>Отказы здесь двух пород, и разница видна вызывающему.</b>
 * {@link Refused} — про <b>одну позицию</b>: её цена такова, что операция
 * дала бы минус или ноль. Правка списком такую позицию пропускает и называет
 * человеку, а всю пачку не отменяет — иначе одна дешёвая деталь остановила бы
 * переоценку всего склада. Обычное {@code IllegalArgumentException} — про
 * <b>запрос целиком</b>: отрицательный процент или округление без шага
 * не выполнимы ни у одной позиции, и делать половину работы тут нечего.
 *
 * <p><b>Округление арифметическое, а не всегда вверх.</b> Это цена, а не
 * наценка: «округлить до 1000» от 24 800 даёт 25 000, от 24 300 — 24 000.
 * Всегда вверх было бы тихой наценкой в пользу продавца, о которой
 * на экране не написано.
 */
public enum PriceOperation {

    /** Заменить значение. Умолчание: самый частый случай остаётся одним движением. */
    SET("Изменить"),
    INCREASE_PERCENT("Увеличить на %"),
    DECREASE_PERCENT("Уменьшить на %"),
    INCREASE_AMOUNT("Увеличить на сумму"),
    DECREASE_AMOUNT("Уменьшить на сумму"),
    ROUND_TO("Округлить до");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final String title;

    PriceOperation(String title) {
        this.title = title;
    }

    /** Как операция называется человеку — теми же словами, что в списке на экране. */
    public String title() {
        return title;
    }

    /** Арифметика, а не замена: считать её надо от прежнего значения. */
    public boolean arithmetic() {
        return this != SET;
    }

    /**
     * Задано ли значение, с которым операция что-то сделает.
     *
     * <p>Ноль здесь — то же, что пусто: «увеличить на 0 %» не меняет ничего,
     * а шаг округления в ноль не существует.
     */
    public boolean hasOperand(BigDecimal operand) {
        return operand != null && operand.signum() != 0;
    }

    /**
     * Новое значение поля.
     *
     * <p>{@code null} в ответе означает «менять нечего»: человек выбрал
     * операцию и не ввёл значения. Это не ошибка — поле остаётся как было,
     * ровно как и раньше при пустой цене.
     *
     * @param current прежнее значение; {@code null} — считать не от чего,
     *                и это {@link Refused}: пропускать такую позицию молча
     *                решает вызывающий, а не расчёт
     * @param operand что ввёл человек: процент, сумма или шаг округления
     * @param field   какое поле двигают: от него зависят слова отказа —
     *                у себестоимости и цены разные последствия
     * @param code    публичный код позиции: по нему её видно на витрине
     *                и на этикетке, по внутреннему номеру — нигде
     * @throws Refused операция невыполнима <b>у этой позиции</b>
     * @throws IllegalArgumentException запрос невыполним ни у одной позиции
     */
    public BigDecimal apply(BigDecimal current, BigDecimal operand, MoneyField field,
                            String code) {
        if (this == SET) {
            return operand;
        }
        if (!hasOperand(operand)) {
            // Округление без шага — единственная операция, которую нельзя
            // выполнить «никак»: делить на ноль не на что, а промолчать
            // значит сказать «сохранено» там, где ничего не произошло.
            if (this == ROUND_TO) {
                throw new IllegalArgumentException(
                        "«%s»: не задан шаг округления — например 100, 500 или 1000. %s не изменена"
                                .formatted(title, capitalized(field.nominative())));
            }
            return null;
        }
        if (operand.signum() < 0) {
            throw new IllegalArgumentException(
                    "«%s»: значение не может быть отрицательным. %s не изменена"
                            .formatted(title, capitalized(field.nominative())));
        }
        String subject = "%s позиции %s".formatted(field.nominative(), code);
        if (current == null) {
            throw new Refused(
                    "«%s»: %s не заполнена — считать не от чего".formatted(title, subject));
        }

        BigDecimal result = switch (this) {
            case INCREASE_PERCENT -> percent(current, HUNDRED.add(operand));
            case DECREASE_PERCENT -> percent(current, HUNDRED.subtract(operand));
            case INCREASE_AMOUNT -> current.add(operand);
            case DECREASE_AMOUNT -> current.subtract(operand);
            case ROUND_TO -> current.divide(operand, 0, RoundingMode.HALF_UP).multiply(operand);
            // Замена ушла первой строкой метода: считать там нечего.
            case SET -> operand;
        };

        if (result.signum() < 0) {
            throw new Refused(
                    "«%s» на %s: %s сейчас %s, и после операции получилось бы %s. Отрицательной %s не бывает — ничего не изменено"
                            .formatted(title, plain(operand), capitalized(subject),
                                    plain(current), plain(result), field.genitive()));
        }
        if (result.signum() == 0) {
            // Последствие называется только там, где оно есть: выдуманное
            // хуже отсутствующего — по нему человек делает выводы о системе.
            String means = field.zeroMeans() == null ? "" : field.zeroMeans() + ". ";
            throw new Refused(
                    "«%s» на %s: %s сейчас %s, и после операции получился бы ноль. %sПоставить ноль можно операцией «Изменить» — сейчас ничего не изменено"
                            .formatted(title, plain(operand), capitalized(subject),
                                    plain(current), means));
        }
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Операция невыполнима у <b>этой</b> позиции — но выполнима у соседних.
     *
     * <p>Отдельный тип нужен ровно за тем, чтобы правка списком отличала
     * «дальше идти нельзя» от «эту пропустить». Одна деталь за сто рублей,
     * попавшая в отбор «скинуть пятьсот со всего склада», не должна
     * отменять переоценку тридцати тысяч позиций — тот же довод, по которому
     * {@code StockDocumentService.moveBatch} везёт остальные, а отложенную
     * называет вызывающему.
     *
     * <p>Наследует {@code IllegalArgumentException}, потому что вне пачки —
     * в правке одной карточки — это обычный отказ словами, то есть 400.
     */
    public static class Refused extends IllegalArgumentException {
        public Refused(String message) {
            super(message);
        }
    }

    /**
     * Доля от прежнего значения.
     *
     * <p>Две копейки округления здесь и есть та разница, ради которой расчёт
     * живёт на сервере: делить надо явно заданным правилом, иначе
     * {@code BigDecimal} бросит «Non-terminating decimal expansion»
     * на трети процента.
     */
    private static BigDecimal percent(BigDecimal current, BigDecimal share) {
        return current.multiply(share).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** Число в отказе — как его пишет человек, без хвоста нулей от numeric. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String capitalized(String subject) {
        return subject.isEmpty() ? subject
                : Character.toUpperCase(subject.charAt(0)) + subject.substring(1);
    }
}
