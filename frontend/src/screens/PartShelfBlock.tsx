import { useState } from 'react';
import { ApiError } from '../api/client';
import { placePartOnShelf } from '../inventory/catalog';
import {
  ADDRESS_EXAMPLE,
  ADDRESS_SCHEME,
  normalizeAddress,
} from '../inventory/storageAddress';
import { ScanOverlay } from '../scan/ScanOverlay';
import { useMounted } from '../ui/useMounted';

/**
 * «Секция» позиции — адрес полки «склад-зона-ряд-секция-полка» — и привязка
 * к полке вводом или сканом её этикетки (задача 0368).
 *
 * <p>Адрес заранее не заводится (решение владельца 19.10.2026 «Всё как
 * у bazon», раздел «Ответ владельца» задачи 0368): проверяется запись
 * по схеме, а не существование полки. «01-01-3-1-2» — та же полка
 * `01-01-03-01-02`, это показывается до нажатия.
 *
 * <p>Сам адрес блок не повторяет: он стоит строкой «Секция» в полях
 * карточки, и прежний со свободным названием помечен там «не по схеме»
 * (`inventory/partCard.ts`).
 */
export function PartShelfBlock({ partId, section, canPlace, onChanged }: {
  partId: number;
  section: string | null;
  /** Право «Ячейка позиции» — то же, что у перестановки по ячейке. */
  canPlace: boolean;
  /** Адрес изменился — витрину надо перечитать: в ней колонка «Секция». */
  onChanged: () => void;
}) {
  const [placing, setPlacing] = useState(false);
  const [typed, setTyped] = useState('');
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const mounted = useMounted();

  const current = section === null || section.trim() === '' ? null : section;
  const wanted = typed.trim() === '' ? null : normalizeAddress(typed);
  // Одно выражение на кнопку и на подпись под ней — серая кнопка называет причину.
  const obstacle = typed.trim() === ''
    ? (current === null ? 'Введите или отсканируйте адрес полки' : null)
    : wanted === null
      ? `Адрес не по схеме «${ADDRESS_SCHEME}» — пять чисел через дефис, например ${ADDRESS_EXAMPLE}`
      : null;

  async function save(): Promise<void> {
    setError('');
    setSaving(true);
    try {
      await placePartOnShelf(partId, wanted);
      onChanged();
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError && cause.message !== ''
          ? cause.message
          : 'Поставить на полку не вышло');
      }
    } finally {
      if (mounted.current) setSaving(false);
    }
  }

  return (
    <>
      {canPlace && (placing ? (
        <>
          <label className="field">
            Адрес полки
            <input
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              placeholder={ADDRESS_EXAMPLE}
              inputMode="numeric"
              autoCapitalize="none"
            />
          </label>
          {wanted !== null && wanted !== typed.trim() && (
            <p className="note">Будет записано: {wanted}</p>
          )}
          {obstacle !== null && <p className="note">{obstacle}</p>}
          {typed.trim() === '' && current !== null && (
            <p className="note">Пустой адрес снимет позицию с полки.</p>
          )}
          {error !== '' && <p className="note note--error">{error}</p>}
          <div className="filter-row">
            <button
              type="button"
              disabled={obstacle !== null || saving}
              onClick={() => void save()}
            >
              {saving ? 'Ставим…' : typed.trim() === '' ? 'Снять с полки' : 'Поставить'}
            </button>
            <button type="button" className="button--ghost" onClick={() => setScanning(true)}>
              Сканировать
            </button>
            <button type="button" className="button--ghost" onClick={() => setPlacing(false)}>
              Отмена
            </button>
          </div>
        </>
      ) : (
        <button
          type="button"
          className="button--ghost"
          onClick={() => {
            setError('');
            setTyped('');
            setPlacing(true);
          }}
        >
          Поставить на полку
        </button>
      ))}

      {scanning && (
        <ScanOverlay
          hint="Этикетка полки"
          onScan={(text) => {
            setScanning(false);
            setTyped(text.trim());
          }}
          onClose={() => setScanning(false)}
        />
      )}
    </>
  );
}
