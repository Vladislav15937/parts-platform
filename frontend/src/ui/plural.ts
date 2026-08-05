/**
 * Русское склонение существительного при числе.
 *
 * <p>«Склад 1 товаров» и «Изменить 1 позиций» — то, что владелец видит
 * каждый раз, когда отбор сузился до одной строки. Помощник в проекте уже
 * был, но лежал внутри одного экрана и знал одно слово («деталь»): рядом
 * с ним соседние экраны продолжали писать «товаров» при любом числе.
 *
 * <p>Правило русского языка на три формы: 1 — «товар», 2–4 — «товара»,
 * остальное — «товаров». Исключение — вторая десятка: «11 товаров»,
 * а не «11 товар», поэтому хвост от 11 до 14 проверяется первым.
 */
export function plural(count: number, one: string, few: string, many: string): string {
  const tail = count % 100;
  if (tail >= 11 && tail <= 14) {
    return many;
  }
  switch (count % 10) {
    case 1: return one;
    case 2:
    case 3:
    case 4: return few;
    default: return many;
  }
}

/**
 * Число с разделителем разрядов.
 *
 * <p>Тридцать пять тысяч без пробелов читаются как случайный набор цифр,
 * а на витрине склада это главное число экрана.
 */
export function count(value: number): string {
  return value.toLocaleString('ru-RU');
}

/** «35 844 товара» — число и слово вместе, как их читают. */
export function goods(value: number): string {
  return `${count(value)} ${plural(value, 'товар', 'товара', 'товаров')}`;
}

/** «35 844 позиции» — то же для правки списком. */
export function positions(value: number): string {
  return `${count(value)} ${plural(value, 'позицию', 'позиции', 'позиций')}`;
}
