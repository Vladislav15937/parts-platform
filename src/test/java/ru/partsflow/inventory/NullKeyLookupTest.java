package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Белые списки склада читаются по имени, пришедшему из запроса, — и это имя
 * бывает не названо.
 *
 * <p>Все они лежат в {@code Map.ofEntries}, а неизменяемая карта на чтении
 * по {@code null}-ключу бросает {@code NullPointerException}, а не отвечает
 * «нет такого»: {@code HashMap} на её месте вернул бы {@code null}, и разницу
 * не видно ни глазами, ни компилятором. Проверка «выражение не найдено»,
 * стоящая строкой ниже, до такого чтения не доживает — значит и умолчание
 * сортировки, и объяснение «по этой колонке отбор не делается» подменяются
 * пятисоткой.
 *
 * <p>Снаружи {@code null} сюда сегодня не приходит: у сортировки стоит
 * {@code defaultValue}, у колонки — обязательный параметр запроса. Это мина,
 * а не отказ, и держится она не на коде, а на том, что никто пока не позвал
 * иначе; цена её сейчас — одна строка, цена после — разбор жалобы живого
 * клиента, у которого не открылась вкладка.
 *
 * <p>Порода та же, что у истории карточки: там {@code Map.of} на движении
 * перенесённого склада (документа не было вовсе — статуса нет) роняла всю
 * ленту пятисоткой, и ловится это тестом, а не чтением кода.
 */
class NullKeyLookupTest {

    @Test
    @DisplayName("Неназванная сортировка колёс — порядок по умолчанию, а не исключение")
    void wheelSortWithoutNameFallsBackToDefault() {
        // Умолчание то же, что у витрины склада: номер позиции по возрастанию.
        assertThat(WheelService.orderOf(null, false))
                .as("порядок по умолчанию при неназванной сортировке")
                .isEqualTo(WheelService.orderOf("нет такой колонки", false));

        // И направление неназванную сортировку не меняет: умолчание одно.
        assertThat(WheelService.orderOf(null, true))
                .isEqualTo(WheelService.orderOf(null, false));
    }

    @Test
    @DisplayName("Названная сортировка колёс продолжает работать")
    void wheelSortByKnownColumnStillWorks() {
        // Иначе «починка» вида «всегда отдавать умолчание» прошла бы зелёной.
        assertThat(WheelService.orderOf("price", true)).contains("p.price DESC");
    }

    @Test
    @DisplayName("Неназванная колонка отбора колёс объясняется словами, а не пятисоткой")
    void wheelFilterWithoutColumnIsRefusedInWords() {
        assertThatThrownBy(() -> WheelService.columnExpression(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("отбор не делается");

        assertThat(WheelService.columnExpression("season")).isNotBlank();
    }

    @Test
    @DisplayName("Неназванная колонка отбора витрины объясняется словами, а не пятисоткой")
    void catalogFilterWithoutColumnIsRefusedInWords() {
        assertThatThrownBy(() -> CatalogService.columnExpression(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("отбор не делается");

        assertThat(CatalogService.columnExpression("brand")).isNotBlank();
    }
}
