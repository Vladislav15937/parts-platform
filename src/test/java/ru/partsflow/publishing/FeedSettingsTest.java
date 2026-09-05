package ru.partsflow.publishing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Наценка на прайс-лист и округление.
 *
 * <p>Без Spring: считает это чистая функция, и поднимать ради неё контекст
 * значит добавить ещё один пул соединений к тем, что и так упираются
 * в {@code max_connections}.
 */
class FeedSettingsTest {

    @Test
    @DisplayName("Наценка с округлением: 4 500 уезжает как 4 950")
    void markupWithRounding() {
        FeedSettings tenPercent = new FeedSettings(new BigDecimal("10"), new BigDecimal("10"));

        assertThat(tenPercent.priceFor(new BigDecimal("4500.00")))
                .isEqualByComparingTo("4950");
    }

    @Test
    @DisplayName("Округление идёт вверх до шага, а не отбрасыванием")
    void roundingGoesUp() {
        FeedSettings tenPercent = new FeedSettings(new BigDecimal("10"), new BigDecimal("10"));

        // 4 505 + 10 % = 4 955,50. Вниз — это 4 950, то есть деталь, отданная
        // дешевле, чем владелец задал; узнает он об этом от покупателя,
        // приехавшего по объявлению.
        assertThat(tenPercent.priceFor(new BigDecimal("4505.00")))
                .isEqualByComparingTo("4960");
    }

    @Test
    @DisplayName("Скидка уменьшает цену, а не увеличивает")
    void discountLowersPrice() {
        // Ровно то, что стоит у живого клиента на прайсе Авито: −20 %
        // на комиссию площадки.
        FeedSettings discount = new FeedSettings(new BigDecimal("-20"), null);

        assertThat(discount.priceFor(new BigDecimal("5000.00")))
                .isEqualByComparingTo("4000");
    }

    @Test
    @DisplayName("Незаданная наценка не трогает ни цену, ни её вид в файле")
    void withoutSettingsPriceIsUntouched() {
        // Не «то же число», а та же величина с тем же масштабом: «8500.00»
        // в прайсе не должно превратиться в «8500» от одного появления
        // настроек — площадка разбирает файл, а не читает его.
        assertThat(FeedSettings.none().priceFor(new BigDecimal("8500.00")))
                .hasToString("8500.00");
    }

    @Test
    @DisplayName("Одно округление без наценки тоже работает")
    void roundingAloneWorks() {
        FeedSettings toHundred = new FeedSettings(null, new BigDecimal("100"));

        assertThat(toHundred.priceFor(new BigDecimal("4501.00")))
                .isEqualByComparingTo("4600");
    }

    @Test
    @DisplayName("Наценка на «цену не назначили» цены не создаёт")
    void zeroPriceStaysZero() {
        FeedSettings tenPercent = new FeedSettings(new BigDecimal("10"), new BigDecimal("10"));

        // Ноль у нас означает незаполненное поле — в выгрузке прежней системы
        // он стоит именно там. Округлив его вверх до шага, мы получили бы
        // «10 ₽» на детали, которой цену не назначали вовсе.
        assertThat(tenPercent.priceFor(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(tenPercent.priceFor(null)).isNull();
    }

    @Test
    @DisplayName("Скидка в сто процентов отбивается словами, а не обнуляет цену")
    void fullDiscountIsRefused() {
        assertThatThrownBy(() -> new FeedSettings(new BigDecimal("-100"), null).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 ₽");
    }

    @Test
    @DisplayName("Соседние настройки в jsonb разбор не ломают")
    void unknownKeysAreIgnored() {
        // Рядом лежит packetId прайс-листа площадки. Упасть на нём значило бы
        // не отдать прайс вовсе — то есть снять с сайта все объявления.
        FeedSettings parsed = FeedSettings.parse(
                "{\"packetId\": \"777\", \"pricePercent\": 10}");

        assertThat(parsed.pricePercent()).isEqualByComparingTo("10");
        assertThat(parsed.priceRounding()).isNull();
    }

    @Test
    @DisplayName("Записываются только свои ключи — чужие остаются в базе")
    void writesOnlyItsOwnKeys() {
        // Настройки кладутся слиянием (settings || …), и объект, несущий
        // чужие ключи, затёр бы их значениями по умолчанию.
        assertThat(new FeedSettings(new BigDecimal("10"), null).toJson())
                .contains("pricePercent")
                .doesNotContain("packetId");
    }
}
