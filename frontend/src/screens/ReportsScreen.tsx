import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  donorProfitability,
  managerSales,
  money,
  salesBySource,
  unknownShare,
  monthName,
  monthOf,
  shiftMonth,
} from '../reports/reports';
import type { DonorReport, ManagerReport, SourceReport } from '../reports/reports';

/**
 * Отчёты владельца.
 *
 * <p>Два вопроса, ради которых их открывают: сколько платить менеджерам
 * и стоит ли брать такие машины. Оба — то, на что прямо жалуются пользователи
 * системы, с которой к нам переходят.
 *
 * <p>Продажи — за месяц, а не за всё время: премию считают за период, и цифра
 * с начала работы для этого бесполезна.
 *
 * <p>У доноров убыточные сверху, но «убыток» у только что купленной машины
 * ничего не значит — с неё ещё не сняли. Поэтому в строке видно и сколько ещё
 * лежит на складе, и сколько позиций из общего продано: по ним отличают
 * плохую машину от свежей.
 */
interface Props {
  canRead: boolean;
}

export function ReportsScreen({ canRead }: Props) {
  const [month, setMonth] = useState(monthOf(new Date()));
  const [managers, setManagers] = useState<ManagerReport | null>(null);
  const [donors, setDonors] = useState<DonorReport | null>(null);
  const [sources, setSources] = useState<SourceReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void managerSales(month)
      .then(setManagers)
      .catch((cause) => setError(describe(cause, 'Отчёт по продажам не загрузился')));
  }, [month]);

  useEffect(() => {
    // Тот же месяц, что и у отчёта по менеджерам: владелец смотрит их рядом,
    // и разные периоды на соседних таблицах сравнивать нельзя.
    void salesBySource(month)
      .then(setSources)
      .catch((cause) => setError(describe(cause, 'Отчёт по каналам не загрузился')));
  }, [month]);

  useEffect(() => {
    void donorProfitability()
      .then(setDonors)
      .catch((cause) => setError(describe(cause, 'Отчёт по машинам не загрузился')));
  }, []);

  if (!canRead) {
    return (
      <section className="card">
        <h2>Отчёты</h2>
        <p className="note">
          Отчёты видит владелец или менеджер: в продажах по менеджерам лежит
          зарплатная база всей смены, а в окупаемости машин — себестоимость.
        </p>
      </section>
    );
  }

  return (
    <section className="card">
      <h2>Отчёты</h2>
      {error !== null && <p className="note note--error">{error}</p>}

      <h3>Продажи по менеджерам</h3>
      <div className="row row--between">
        <button type="button" className="button--ghost" onClick={() => setMonth(shiftMonth(month, -1))}>
          ←
        </button>
        <strong>{monthName(month)}</strong>
        <button type="button" className="button--ghost" onClick={() => setMonth(shiftMonth(month, 1))}>
          →
        </button>
      </div>

      {managers !== null && managers.rows.length === 0 && (
        <p className="note">В этом месяце продаж не было.</p>
      )}

      {managers !== null && managers.rows.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Менеджер</th>
                <th className="num">Сделок</th>
                <th className="num">Выручка</th>
                <th className="num">Наценка</th>
              </tr>
            </thead>
            <tbody>
              {managers.rows.map((row) => (
                <tr key={row.managerId ?? 'none'}>
                  {/* Сделки без менеджера — из времён до учёта продавцов.
                      Прятать их нельзя: их выручка тоже настоящая. */}
                  <td>{row.displayName ?? 'без менеджера'}</td>
                  <td className="num">{row.dealsCount}</td>
                  <td className="num">{money(row.revenue)}</td>
                  {/* Прочерк, а не ноль: «себестоимость не заведена»
                      и «продали в ноль» — разные вещи, и вторая говорит
                      владельцу, что вся выручка ушла в закупку. */}
                  <td className="num">{row.margin === null ? '—' : money(row.margin)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="note">
        Возвращённое сюда не попадает: премию платят за проданное, а не
        за привезённое обратно. Наценка — по себестоимости на момент продажи.
      </p>

      {managers !== null && withoutCost(managers) > 0 && (
        <p className="note">
          Позиций без закупочной цены: {withoutCost(managers)}. В наценку они
          не вошли — склад, загруженный из таблицы, приходит без закупок,
          и посчитанная по нему прибыль была бы завышена на всю их стоимость.
        </p>
      )}

      <hr />
      <h3>Откуда пришли продажи</h3>

      {sources !== null && sources.rows.length === 0 && (
        <p className="note">За этот месяц продаж нет.</p>
      )}

      {sources !== null && sources.rows.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Канал</th>
                <th className="num">Сделок</th>
                <th className="num">Выручка</th>
                <th className="num">Наценка</th>
              </tr>
            </thead>
            <tbody>
              {sources.rows.map((row) => (
                <tr key={row.sourceId ?? 'none'}>
                  {/* Не «прочее»: это не канал, а незаполненное поле. */}
                  <td>{row.sourceName ?? 'источник не указан'}</td>
                  <td className="num">{row.dealsCount}</td>
                  <td className="num">{money(row.revenue)}</td>
                  <td className="num">{row.margin === null ? '—' : money(row.margin)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {sources !== null && unknownShare(sources) > 0.2 && (
        <p className="note note--error">
          Без указанного источника прошло{' '}
          {Math.round(unknownShare(sources) * 100)}% выручки. Пока это так,
          сравнивать каналы между собой нельзя: «Дром принёс мало» и «продавцы
          не отмечают Дром» отсюда выглядят одинаково.
        </p>
      )}

      <hr />
      <h3>Окупаемость машин</h3>

      {donors !== null && (
        <p className="note">
          Машин: {donors.totals.donors} · вложено {money(donors.totals.totalCost)} ·
          выручено {money(donors.totals.revenue)} · ещё на складе{' '}
          {money(donors.totals.stockValue)}
        </p>
      )}

      {donors !== null && donors.rows.length === 0 && (
        <p className="note">Машин пока нет. Донора заводят на вкладке «Машина».</p>
      )}

      {donors !== null && donors.rows.length > 0 && (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                <th>Машина</th>
                <th className="num">Вложено</th>
                <th className="num">Выручено</th>
                <th className="num">Итог</th>
                <th className="num">На складе</th>
              </tr>
            </thead>
            <tbody>
              {donors.rows.map((row) => (
                <tr key={row.donorId}>
                  <td>
                    {/* Сначала то, чем машину зовёт владелец: марка с моделью
                        или номер из предыдущей системы. Наш внутренний код
                        ему ничего не говорит — прогон на чистой ячейке
                        показал таблицу из одних шестнадцатеричных кодов. */}
                    <strong>{row.note ?? row.legacyCode ?? row.publicCode ?? row.donorId}</strong>
                    {row.year !== null && <span className="muted"> · {row.year}</span>}
                    {row.legacyCode !== null && row.note !== null && (
                      <span className="muted"> · {row.legacyCode}</span>
                    )}
                    {row.vin !== null && <div className="muted">{row.vin}</div>}
                    {/* Продано — не колонка: пять числовых столбцов не влезают
                        в ширину экрана, а без этой доли строка не читается
                        вовсе (минус у свежей машины — это ещё не убыток).

                        «Полностью» здесь не для красоты: считаются карточки
                        с обнулённым остатком, и позиция, у которой из двух
                        штук продана одна, сюда не попадает. Без уточнения
                        «продано 0 из 6» рядом с выручкой читается как ошибка. */}
                    <div className="muted">
                      полностью продано {row.partsSold} из {row.partsTotal}
                    </div>
                  </td>
                  <td className="num">{money(row.totalCost)}</td>
                  <td className="num">{money(row.revenue)}</td>
                  <td className={Number(row.profit) < 0 ? 'num negative' : 'num'}>
                    {money(row.profit)}
                  </td>
                  <td className="num">{money(row.stockValue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="note">
        Убыточные сверху. Минус у свежей машины — это ещё не убыток: смотрите,
        сколько с неё продано и на сколько осталось на складе.
      </p>
    </section>
  );
}

/** Сколько позиций месяца остались без себестоимости — по всем менеджерам. */
function withoutCost(report: ManagerReport): number {
  return report.rows.reduce((sum, row) => sum + row.itemsWithoutCost, 0);
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. Отчёты считаются на сервере — повторите.';
    }
    if (cause.status === 403) {
      return 'Отчёты видит владелец или менеджер';
    }
    return cause.message;
  }
  return fallback;
}
