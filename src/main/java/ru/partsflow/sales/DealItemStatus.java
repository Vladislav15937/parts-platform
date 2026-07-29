package ru.partsflow.sales;

/**
 * Состояние позиции сделки.
 *
 * <p>Отдельно от статуса документа: часть сделки выдают, часть возвращают,
 * часть отменяют — и в разрезе «по товарам» продавец смотрит именно на позицию.
 */
public enum DealItemStatus {
    RESERVED,
    ISSUED,
    RETURNED,
    CANCELLED
}
