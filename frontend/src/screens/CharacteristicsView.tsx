/**
 * Строки «Характеристик» для показа (задача 0292). Хранимый текст не меняется —
 * это только чтение «Прочих полей» объявления Дрома, где строка устроена так:
 * `Подпись [ключ]: значение`.
 *
 * Показывается «Подпись: значение», без `[ключа]`. Строка, у которой подпись и
 * есть ключ (`model [model]`, `model~tireFirmAndModel […]`, `marking [marking]`), —
 * служебная запись Дрома, а не слово клиента: она прячется, если то же сказано
 * человеческой строкой (тот же ключ или то же значение — «Модель магнитолы
 * [model]», «Модель шины [tireFirmAndModel]», «Диаметр [marking]»). Не сказано —
 * остаётся одно значение, без подписи: имя ключа на экран не попадает, а значение
 * клиента не теряется. Пустые строки не показываются.
 */
export function characteristicLines(text: string): string[] {
  const lines = text.split('\n')
    .map((raw) => raw.trim())
    .filter((raw) => raw !== '')
    .map(parse);
  const human = lines.filter((line) => !line.service);
  return lines
    .filter((line) => !line.service || !human.some((other) =>
      (line.key !== null && other.key === line.key) || other.value === line.value))
    .map((line) => (line.service || line.label === '' ? line.value : `${line.label}: ${line.value}`));
}

interface Line {
  label: string;
  key: string | null;
  value: string;
  service: boolean;
}

function parse(raw: string): Line {
  const keyed = /^(.*?)\s*\[([^\]]+)\]:\s*(.*)$/.exec(raw);
  if (keyed !== null) {
    const label = keyed[1] ?? '';
    const key = keyed[2] ?? '';
    return { label, key, value: keyed[3] ?? '', service: label === key };
  }
  const plain = /^([^:]+):\s*(.*)$/.exec(raw);
  if (plain !== null) {
    return { label: plain[1] ?? '', key: null, value: plain[2] ?? '', service: false };
  }
  return { label: '', key: null, value: raw, service: false };
}

/** «Характеристики» строками — у шины их полтора десятка, у магнитолы две-три. */
export function CharacteristicsView({ text }: { text: string }) {
  return (
    <div className="fits">
      {characteristicLines(text).map((line, i) => <div key={i}>{line}</div>)}
    </div>
  );
}
