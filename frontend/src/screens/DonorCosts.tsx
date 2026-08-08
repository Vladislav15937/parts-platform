import { useCallback, useEffect, useState } from 'react';
import { ApiError, request } from '../api/client';

/**
 * Затраты по машине: покупка, доставка, растаможка, разбор, хранение.
 *
 * <p><b>Журналом, а не одним полем «стоимость».</b> Машину покупают за одну
 * сумму, потом платят за эвакуатор, потом за разбор. Одно поле пришлось бы
 * переписывать при каждом платеже, а вопрос «из чего сложились эти сто тысяч»
 * остался бы без ответа.
 *
 * <p>Отсюда считает отчёт окупаемости. Пока вносить затраты было негде,
 * он показывал «вложено 0 ₽» у всех машин — ему нечего было читать.
 */
export interface Cost {
  id: number;
  type: CostType;
  amount: number;
  incurredOn: string;
  note: string | null;
}

export type CostType = 'PURCHASE' | 'DELIVERY' | 'CUSTOMS' | 'DISMANTLING' | 'STORAGE' | 'OTHER';

export const COST_TYPES: Array<{ type: CostType; title: string }> = [
  { type: 'PURCHASE', title: 'Покупка' },
  { type: 'DELIVERY', title: 'Доставка' },
  { type: 'CUSTOMS', title: 'Растаможка' },
  { type: 'DISMANTLING', title: 'Разбор' },
  { type: 'STORAGE', title: 'Хранение' },
  { type: 'OTHER', title: 'Прочее' },
];

export function costTitle(type: string): string {
  return COST_TYPES.find((t) => t.type === type)?.title ?? type;
}

export function DonorCosts({ donorId, title }: { donorId: number; title: string }) {
  const [costs, setCosts] = useState<Cost[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const [type, setType] = useState<CostType>('PURCHASE');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');

  const reload = useCallback(() => {
    request<Cost[]>(`/api/intake/donors/${donorId}/costs`)
      .then((found) => {
        setCosts(found);
        setError('');
      })
      .catch((cause) => setError(describe(cause, 'Затраты не загрузились')));
  }, [donorId]);

  useEffect(reload, [reload]);

  const total = (costs ?? []).reduce((sum, cost) => sum + cost.amount, 0);

  return (
    <section className="card">
      <h3>Затраты · {title}</h3>

      {error !== '' && <p className="note note--error">{error}</p>}

      {costs === null ? (
        error !== '' ? null : <p className="note">Загружаем…</p>
      ) : costs.length === 0 ? (
        <p className="note">
          Затрат нет. Пока их не внести, окупаемость этой машины покажет
          «вложено 0 ₽» — считать не из чего.
        </p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Вид</th>
              <th>Дата</th>
              <th className="num">Сумма</th>
              <th>Примечание</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {costs.map((cost) => (
              <tr key={cost.id}>
                <td>{costTitle(cost.type)}</td>
                <td>{new Date(cost.incurredOn).toLocaleDateString('ru-RU')}</td>
                <td className="num">{cost.amount.toLocaleString('ru-RU')}</td>
                <td>{cost.note ?? ''}</td>
                <td>
                  <button
                    type="button"
                    className="button--ghost"
                    disabled={busy}
                    onClick={() => void drop(cost.id)}
                  >
                    убрать
                  </button>
                </td>
              </tr>
            ))}
            <tr>
              <td colSpan={2}><strong>Всего вложено</strong></td>
              <td className="num"><strong>{total.toLocaleString('ru-RU')}</strong></td>
              <td colSpan={2} />
            </tr>
          </tbody>
        </table>
      )}

      <div className="row">
        <label className="field">
          Вид затрат
          <select value={type} onChange={(e) => setType(e.target.value as CostType)}>
            {COST_TYPES.map((t) => (
              <option key={t.type} value={t.type}>{t.title}</option>
            ))}
          </select>
        </label>
        <label className="field">
          Сумма
          <input
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value.replace(',', '.'))}
          />
        </label>
      </div>
      <label className="field">
        Примечание
        <input value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
      <button type="button" disabled={busy || !isAmount(amount)} onClick={() => void add()}>
        {busy ? 'Записываем…' : 'Записать затрату'}
      </button>
    </section>
  );

  async function add(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      setCosts(await request<Cost[]>(`/api/intake/donors/${donorId}/costs`, {
        method: 'POST',
        body: { type, amount: Number(amount), note: note.trim() === '' ? null : note.trim() },
      }));
      setAmount('');
      setNote('');
    } catch (cause) {
      setError(describe(cause, 'Затрата не записана'));
    } finally {
      setBusy(false);
    }
  }

  async function drop(id: number): Promise<void> {
    setBusy(true);
    try {
      setCosts(await request<Cost[]>(`/api/intake/donors/${donorId}/costs/${id}`, {
        method: 'DELETE',
      }));
    } catch (cause) {
      setError(describe(cause, 'Не убралось'));
    } finally {
      setBusy(false);
    }
  }
}

/** Сумма — число и не отрицательная: остальное сервер отобьёт, но позже. */
function isAmount(value: string): boolean {
  const parsed = Number(value);
  return value.trim() !== '' && Number.isFinite(parsed) && parsed >= 0;
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
