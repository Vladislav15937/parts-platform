import { useEffect, useState } from 'react';
import { donorTitle, listDonors, type DonorEntry } from '../intake/donors';
import { count } from '../ui/plural';

/** Сколько машин показывать в выборе: у переехавшего клиента их 441. */
const SHOWN = 50;

/**
 * Выбор «Номера донора» у позиции с машиной-донором (задача 0365): список машин
 * с поиском — по номеру клиента, марке с моделью, заметке и VIN, как на экране
 * машин (`donorTitle`). Список ищется, а не прокручивается: машин сотни.
 *
 * <p>Три состояния различимы — грузим, не смогли узнать, ничего не нашлось;
 * обрезанный список говорит, сколько показано из скольких.
 */
export function DonorPick({ value, onChange }: {
  /** Выбранная машина; сначала — та, что стоит у позиции. */
  value: number | null;
  onChange: (donorId: number) => void;
}) {
  const [donors, setDonors] = useState<DonorEntry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let alive = true;
    void listDonors()
      .then((all) => { if (alive) setDonors(Array.isArray(all) ? all : []); })
      .catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, []);

  if (failed) {
    return <span className="note note--error">Не удалось загрузить список машин — обновите страницу</span>;
  }
  if (donors === null) {
    return <span className="muted">Загружаем машины…</span>;
  }
  const needle = query.trim().toLowerCase();
  const found = needle === '' ? donors
    : donors.filter((d) => `${donorTitle(d)} ${d.vin ?? ''}`.toLowerCase().includes(needle));
  // Выбранная остаётся в списке и тогда, когда поиск её не находит: иначе
  // выбор показывал бы другую машину, а уехала бы эта.
  const chosen = donors.find((d) => d.id === value);
  const shown = found.slice(0, SHOWN);
  if (chosen !== undefined && !shown.includes(chosen)) shown.unshift(chosen);
  return (
    <span className="donor-pick">
      <input aria-label="Найти машину" placeholder="Номер, марка, модель, VIN" autoFocus
             value={query} onChange={(e) => setQuery(e.target.value)} />
      {found.length === 0
        ? <span className="muted">Машин по этому поиску нет</span>
        : (
          <select aria-label="Номер донора" value={value ?? ''}
                  onChange={(e) => onChange(Number(e.target.value))}>
            {shown.map((d) => <option key={d.id} value={d.id}>{donorTitle(d)}</option>)}
          </select>
        )}
      {found.length > SHOWN && (
        <span className="muted">
          Показаны первые {count(SHOWN)} из {count(found.length)} — уточните поиск
        </span>
      )}
    </span>
  );
}
