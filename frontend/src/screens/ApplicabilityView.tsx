import { useState } from 'react';
import { count, plural } from '../ui/plural';

/** Сколько строк видно, пока список свёрнут: три-четыре блока на карточку Дрома, а длинные — сотни. */
export const FITS_COLLAPSED_LINES = 5;

/**
 * Строки «Подходит» для показа (задача 0287). Хранимый текст не меняется — это
 * только чтение: «Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285» читается
 * как «Toyota 4Runner — 5 поколение (2009–2013) — TRN285, GRN285», пустые части и
 * пустые строки не показываются, «Для двигателей | 1GR-FE, …» остаётся своей строкой.
 */
export function fitsLines(text: string): string[] {
  return text.split('\n')
    .map((line) => line.split('|').map((part) => part.trim()).filter((part) => part !== '').join(' — '))
    .filter((line) => line !== '');
}

/**
 * «Подходит» блоками, свёрнутое: у отдельных карточек клиента текст — 34–42 тысячи
 * знаков, и развёрнутым он растягивает карточку на десятки экранов.
 */
export function ApplicabilityView({ text }: { text: string }) {
  const [all, setAll] = useState(false);
  const lines = fitsLines(text);
  const folded = lines.length > FITS_COLLAPSED_LINES;
  const shown = folded && !all ? lines.slice(0, FITS_COLLAPSED_LINES) : lines;
  return (
    <div className="fits">
      {shown.map((line, i) => <div key={i}>{line}</div>)}
      {folded && (
        <button type="button" className="button--ghost" onClick={() => setAll(!all)}>
          {all
            ? 'Свернуть'
            : `Показать все (${count(lines.length)} ${plural(lines.length, 'строка', 'строки', 'строк')})`}
        </button>
      )}
    </div>
  );
}
