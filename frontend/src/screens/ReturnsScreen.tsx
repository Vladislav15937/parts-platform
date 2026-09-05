import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { endOfDay, listReturns, startOfDay } from '../sales/sales';
import type { ReturnListRow, ReturnsPage } from '../sales/sales';

/**
 * Реестр возвратов: обзор без входа в сделку клиента.
 *
 * <p>Раньше возврат было не найти иначе, чем через клиента и его сделку —
 * `GET /api/deals/{id}/returns` показывает документы одной открытой сделки,
 * и до неё ещё надо было дойти. Если продавец, оформивший возврат, сменился,
 * найти его было нельзя вовсе. Роли те же, что у продажи: оформляет возврат
 * продавец, ему же и искать (`SELLING_ROLES` в {@link HomeScreen}).
 *
 * <p>Экран только читает: оформление, отмена и склад возврата остаются
 * там же, где были, — в {@code ReturnPanel} на экране продажи.
 *
 * <p>Вместо курсора — растущий предел размера страницы: список читают
 * с конца и не листают вглубь, поэтому «Показать ещё» дороже первой
 * загрузки ровно на столько строк, сколько добавилось, а не на всю
 * пройденную глубину, как было бы с {@code OFFSET}.
 */
export function ReturnsScreen({ onOpenDeal }: { onOpenDeal: (dealId: number) => void }) {
  const [query, setQuery] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [size, setSize] = useState(PAGE);
  const [page, setPage] = useState<ReturnsPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void load(size);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [size]);

  const filtered = query.trim() !== '' || fromDate !== '' || toDate !== '';

  return (
    <section className="screen screen--wide">
      <h2>Возвраты</h2>

      <form
        className="filter-row filter-row--search"
        onSubmit={(e) => {
          e.preventDefault();
          setSize(PAGE);
          void load(PAGE);
        }}
      >
        <label className="field">
          Поиск
          <input
            value={query}
            placeholder="Номер сделки, клиент или причина"
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <label className="field">
          с
          <input type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
        </label>
        <label className="field">
          по
          <input type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} />
        </label>
        <button type="submit">Найти</button>
      </form>

      {error !== null && <p className="note note--error">{error}</p>}

      {page === null ? (
        error === null && <p className="note">Загружаем…</p>
      ) : page.items.length === 0 ? (
        <p className="note">
          {filtered ? 'По этому отбору возвратов нет' : 'Возвратов пока не было'}
        </p>
      ) : (
        <>
          <div className="table-scroll">
            <table className="report">
              <thead>
                <tr>
                  <th>Номер/дата</th>
                  <th>Клиент</th>
                  <th>По сделке</th>
                  <th>Склад возврата</th>
                  <th className="num">Сумма</th>
                  <th>Статус</th>
                  <th>Причина</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <Row key={row.id} row={row} onOpenDeal={onOpenDeal} />
                ))}
              </tbody>
            </table>
          </div>

          {page.items.length < page.total && (
            <button type="button" className="button--ghost" onClick={() => setSize(size + PAGE)}>
              Показать ещё
            </button>
          )}

          <p className="note">
            Возвратов: {page.total.toLocaleString('ru-RU')} на сумму{' '}
            {Math.round(Number(page.totalAmount)).toLocaleString('ru-RU')}
          </p>
        </>
      )}
    </section>
  );

  async function load(limit: number): Promise<void> {
    try {
      const loaded = await listReturns(
        query,
        fromDate === '' ? '' : startOfDay(fromDate),
        toDate === '' ? '' : endOfDay(toDate),
        limit,
      );
      setPage(loaded);
      setError(null);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Список не загрузился');
    }
  }
}

const PAGE = 50;

function Row({
  row,
  onOpenDeal,
}: {
  row: ReturnListRow;
  onOpenDeal: (dealId: number) => void;
}) {
  return (
    <tr className={row.status === 'CANCELLED' ? 'muted' : undefined}>
      <td>
        <strong>{row.number ?? row.id}</strong>
        <div className="muted">{shortDate(row.createdAt)}</div>
      </td>
      <td>{row.customerName ?? 'Частное лицо'}</td>
      <td>
        <button type="button" className="button--ghost" onClick={() => onOpenDeal(row.dealId)}>
          {row.dealNumber ?? row.dealId}
        </button>
      </td>
      {/* Брак — деньги вернули, а на склад ничего не вставало: адреса
          у такого возврата нет, и показывать его словами честнее, чем
          прочерком или именем склада, на который деталь не ложилась. */}
      <td>{row.restocked ? row.warehouseName ?? '—' : 'Брак, на склад не ставили'}</td>
      <td className="num">{money(row.amount)}</td>
      <td className={row.status === 'DONE' ? 'status-done' : undefined}>
        {row.status === 'DONE' ? 'Выполнен' : 'Отменён'}
      </td>
      <td>{row.reason ?? ''}</td>
    </tr>
  );
}

/** Рубли с копейками: строка сделки их показывает, и здесь ровно та же сумма. */
function money(value: string): string {
  return `${Number(value).toLocaleString('ru-RU')} ₽`;
}

/**
 * Дата коротким словом месяца: «05 сен 26».
 *
 * <p>Своя таблица месяцев, а не `Intl`: короткое имя в `ru-RU` зависит
 * от сборки ICU («сент.» против «сен»), и экран показывал бы разное
 * в браузере и в тестах.
 */
const MONTHS = ['янв', 'фев', 'мар', 'апр', 'май', 'июн',
  'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

function shortDate(iso: string): string {
  const at = new Date(iso);
  const day = String(at.getDate()).padStart(2, '0');
  const year = String(at.getFullYear()).slice(-2);
  return `${day} ${MONTHS[at.getMonth()]} ${year}`;
}
