import type { CatalogRow } from './catalog';

/**
 * Сколько на складе, которое можно продать, и сколько отложено под клиента
 * (задача 0367): «0 (1)» — свободно ноль, в скобках один в резерве.
 *
 * <p>Без резерва — одно число, как раньше; нет записи об остатке — прочерк:
 * «на этом складе её нет» и «есть ноль штук» читаются по-разному. Скобки —
 * отдельный элемент своего цвета, а не часть строки: тем же цветом
 * подсвечена строка (`row--held`), это один знак «отложено».
 */
export function StockCount({ row, warehouseId }: { row: CatalogRow; warehouseId: number }) {
  const key = String(warehouseId);
  const total = row.stock[key];
  if (total === undefined) {
    return <>—</>;
  }
  const held = Number(row.reserved?.[key] ?? 0);
  if (held <= 0) {
    return <>{total}</>;
  }
  return (
    <>
      {Number(total) - held}
      <span className="stock-held" title="Отложено под клиента"> ({held})</span>
    </>
  );
}

/** Есть ли у позиции хоть где-то отложенное: по этому подсвечивается строка. */
export function hasHeld(row: CatalogRow): boolean {
  return Object.values(row.reserved ?? {}).some((value) => Number(value) > 0);
}
