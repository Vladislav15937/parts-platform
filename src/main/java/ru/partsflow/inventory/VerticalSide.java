package ru.partsflow.inventory;

/** Сторона по вертикальной оси: верх или низ. См. {@link LateralSide}. */
public enum VerticalSide {
    UPPER,
    LOWER,
    /**
     * Подходит и сверху, и снизу — «Верхнее, Нижнее» на карточке Дрома
     * (задача 0283). Третье значение, а не две отметки, по тому же доводу,
     * что {@link LateralSide#BOTH}.
     */
    BOTH
}
