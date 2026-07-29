package ru.partsflow.sales;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DealTest {

    private static final Long CUSTOMER = 1L;
    private static final Long MANAGER = 7L;
    private static final Long WAREHOUSE = 3L;

    private Deal dealWithItem() {
        Deal deal = new Deal(CUSTOMER, MANAGER);
        deal.addItem(100L, BigDecimal.ONE, new BigDecimal("2100"), WAREHOUSE);
        return deal;
    }

    @Nested
    @DisplayName("Резерв")
    class Reservation {

        @Test
        @DisplayName("Истёкший резерв не снимается сам — он остаётся очередью на обзвон")
        void expiredReservationStaysReserved() {
            Deal deal = dealWithItem();
            deal.reserve(Instant.now().plus(Duration.ofHours(1)));

            Instant later = Instant.now().plus(Duration.ofDays(2));

            assertThat(deal.isReservationExpired(later)).isTrue();
            // Ключевое: статус не изменился. Автоснятие превратило бы 62 сделки
            // реального склада в невидимую потерю.
            assertThat(deal.getStatus()).isEqualTo(DealStatus.RESERVED);
        }

        @Test
        @DisplayName("Резерв продлевается тем же методом")
        void extendsReservation() {
            Deal deal = dealWithItem();
            Instant first = Instant.now().plus(Duration.ofHours(1));
            Instant second = Instant.now().plus(Duration.ofDays(3));

            deal.reserve(first);
            deal.reserve(second);

            assertThat(deal.getReservedUntil()).isEqualTo(second);
            assertThat(deal.isReservationExpired(Instant.now().plus(Duration.ofHours(2)))).isFalse();
        }

        @Test
        @DisplayName("Пустую сделку резервировать нечем")
        void rejectsEmptyReservation() {
            Deal deal = new Deal(CUSTOMER, MANAGER);

            assertThatThrownBy(() -> deal.reserve(Instant.now().plus(Duration.ofDays(1))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("нет позиций");
        }

        @Test
        @DisplayName("Срок в прошлом — ошибка ввода, а не мгновенно истёкший резерв")
        void rejectsPastDeadline() {
            Deal deal = dealWithItem();

            assertThatThrownBy(() -> deal.reserve(Instant.now().minusSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("будущем");
        }
    }

    @Nested
    @DisplayName("Выдача и отмена")
    class Issuing {

        @Test
        @DisplayName("Выдача переводит и документ, и все позиции")
        void issuesDealAndItems() {
            Deal deal = dealWithItem();
            deal.reserve(Instant.now().plus(Duration.ofDays(1)));

            Instant when = Instant.now();
            deal.issue(when);

            assertThat(deal.getStatus()).isEqualTo(DealStatus.ISSUED);
            assertThat(deal.getIssuedAt()).isEqualTo(when);
            assertThat(deal.getItems()).allMatch(i -> i.getStatus() == DealItemStatus.ISSUED);
        }

        @Test
        @DisplayName("Долг выдачу не блокирует: на разборке отдают и в долг")
        void issuesWithDebt() {
            Deal deal = dealWithItem();

            deal.issue(Instant.now());

            assertThat(deal.getStatus()).isEqualTo(DealStatus.ISSUED);
            assertThat(deal.debt()).isEqualByComparingTo("2100");
        }

        @Test
        @DisplayName("Выданную сделку отменить нельзя — только возвратом")
        void cannotCancelIssued() {
            Deal deal = dealWithItem();
            deal.issue(Instant.now());

            assertThatThrownBy(() -> deal.cancel(Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("возвратом");
        }

        @Test
        @DisplayName("Отмена снимает позиции и обнуляет сумму")
        void cancelClearsTotal() {
            Deal deal = dealWithItem();

            deal.cancel(Instant.now());
            deal.recalculate();

            assertThat(deal.getStatus()).isEqualTo(DealStatus.CANCELLED);
            assertThat(deal.getTotalAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("В закрытую сделку позицию не добавить")
        void cannotAddToClosed() {
            Deal deal = dealWithItem();
            deal.issue(Instant.now());

            assertThatThrownBy(() -> deal.addItem(200L, BigDecimal.ONE, BigDecimal.TEN, WAREHOUSE))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ISSUED");
        }
    }

    @Nested
    @DisplayName("Деньги")
    class Money {

        @Test
        @DisplayName("Сумма считается от позиций и учитывает скидку")
        void totalFromItems() {
            Deal deal = new Deal(CUSTOMER, MANAGER);
            deal.addItem(100L, new BigDecimal("2"), new BigDecimal("1000"), WAREHOUSE);
            DealItem second = deal.addItem(101L, BigDecimal.ONE, new BigDecimal("500"), WAREHOUSE);
            second.setDiscount(new BigDecimal("100"));
            deal.recalculate();

            assertThat(deal.getTotalAmount()).isEqualByComparingTo("2400");
        }

        @Test
        @DisplayName("Частичная оплата отличается от полной")
        void partialPayment() {
            Deal deal = dealWithItem();

            deal.registerPayment(new BigDecimal("500"));

            assertThat(deal.isPartiallyPaid()).isTrue();
            assertThat(deal.isFullyPaid()).isFalse();
            assertThat(deal.debt()).isEqualByComparingTo("1600");
        }

        @Test
        @DisplayName("Переплата не даёт отрицательного долга")
        void overpaymentDoesNotGoNegative() {
            Deal deal = dealWithItem();

            deal.registerPayment(new BigDecimal("3000"));

            assertThat(deal.debt()).isEqualByComparingTo("0");
            assertThat(deal.isFullyPaid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Перенос позиций между сделками")
    class Transfer {

        @Test
        @DisplayName("Позиции уезжают вместе с состоянием, суммы пересчитываются в обеих")
        void movesItemsAndRecalculatesBoth() {
            Deal source = new Deal(CUSTOMER, MANAGER);
            DealItem stays = source.addItem(100L, BigDecimal.ONE, new BigDecimal("1000"), WAREHOUSE);
            DealItem moves = source.addItem(101L, BigDecimal.ONE, new BigDecimal("700"), WAREHOUSE);
            setId(moves, 55L);
            setId(stays, 54L);

            Deal target = new Deal(CUSTOMER, MANAGER);
            List<DealItem> moved = source.transferTo(target, List.of(55L));

            assertThat(moved).hasSize(1);
            assertThat(source.getItems()).extracting(DealItem::getPartId).containsExactly(100L);
            assertThat(target.getItems()).extracting(DealItem::getPartId).containsExactly(101L);
            assertThat(source.getTotalAmount()).isEqualByComparingTo("1000");
            assertThat(target.getTotalAmount()).isEqualByComparingTo("700");
        }

        @Test
        @DisplayName("Перенос в ту же сделку отбивается")
        void rejectsSelfTransfer() {
            Deal deal = dealWithItem();

            assertThatThrownBy(() -> deal.transferTo(deal, List.of(1L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Несуществующая позиция — ошибка, а не молчаливый частичный перенос")
        void rejectsUnknownItem() {
            Deal source = dealWithItem();
            Deal target = new Deal(CUSTOMER, MANAGER);

            assertThatThrownBy(() -> source.transferTo(target, List.of(999L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("позиции найдены");

            // Сделка-источник не должна пострадать от неудачного переноса.
            assertThat(source.getItems()).hasSize(1);
            assertThat(target.getItems()).isEmpty();
        }
    }

    /** Идентификаторы присваивает БД; в юнит-тесте проставляем рефлексией. */
    private static void setId(DealItem item, Long id) {
        try {
            var field = DealItem.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(item, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
