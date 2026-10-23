import { Fragment, useCallback, useEffect, useState } from 'react';
import type { Rights } from '../access/rights';
import { ApiError } from '../api/client';
import { loadMoveJournal, loadMoveLines, type MoveDocument, type MoveLine } from '../inventory/moves';
import { deleteStockDocument, deletionNotice } from '../inventory/stockDocuments';
import {
  conditionTitle, loadCatalog, NO_VEHICLE, type CatalogRow, type Warehouse,
} from '../inventory/catalog';
import { OemWord } from '../inventory/OemWord';
import { EMPTY_WHEEL_QUERY, listWheels, rowOfWheel } from '../inventory/wheels';
import { PartCard } from './PartCard';
import { count, positions } from '../ui/plural';
import { useMounted } from '../ui/useMounted';

/**
 * Журнал перевозок между складами.
 *
 * <p>Единственное место, где было видно движение, — вкладка «Движения»
 * в истории одной позиции: ответить «что мы увезли на второй склад
 * в августе» было нельзя. Список — по документам, а не по строкам: тот же
 * ответ на «что уехало», но восемнадцать строк вместо тысячи с лишним.
 */
export function StockMovesScreen({ rights }: { rights: Rights }) {
  const [documents, setDocuments] = useState<MoveDocument[] | null>(null);
  const [error, setError] = useState('');

  const [expanded, setExpanded] = useState<number | null>(null);
  const [lines, setLines] = useState<MoveLine[]>([]);
  const [linesError, setLinesError] = useState('');
  const [linesLoading, setLinesLoading] = useState(false);

  const [card, setCard] = useState<CatalogRow | null>(null);
  const [cardWarehouses, setCardWarehouses] = useState<Warehouse[]>([]);
  const [openError, setOpenError] = useState('');
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  // Удаление перевозки (задача 0367): документ, который спросили, и итог.
  const [asked, setAsked] = useState<number | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [notice, setNotice] = useState<{ text: string; error: boolean } | null>(null);

  const reload = useCallback(() => {
    loadMoveJournal()
      .then((found) => {
        if (mounted.current) {
          setDocuments(found);
          setError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Журнал перевозок не загрузился'));
      });
  }, [mounted]);

  useEffect(() => {
    reload();
  }, [reload]);

  /**
   * Удаляет перевозку встречным движением: со склада-получателя снимается
   * только свободное, и что осталось там, называется словами над журналом.
   */
  async function remove(id: number): Promise<void> {
    setAsked(null);
    setDeleting(true);
    setNotice(null);
    try {
      const outcome = await deleteStockDocument(id);
      if (!mounted.current) return;
      setNotice({ text: deletionNotice(outcome), error: false });
      reload();
    } catch (cause) {
      if (mounted.current) setNotice({ text: describe(cause, 'Перевозка не удалена'), error: true });
    } finally {
      if (mounted.current) setDeleting(false);
    }
  }

  function toggle(id: number): void {
    if (expanded === id) {
      setExpanded(null);
      return;
    }
    setExpanded(id);
    setLinesError('');
    setLinesLoading(true);
    loadMoveLines(id)
      .then((found) => {
        if (mounted.current) {
          setLines(found);
          setLinesLoading(false);
        }
      })
      .catch((cause) => {
        if (mounted.current) {
          setLinesError(describe(cause, 'Состав документа не загрузился'));
          setLinesLoading(false);
        }
      });
  }

  /**
   * Карточка позиции — по публичному коду, а не по идентификатору: журнал
   * знает только его, а второй запрос за карточкой всё равно нужен.
   *
   * <p>Позиция могла быть и запчастью, и колесом — витрина склада и вкладка
   * колёс отбирают разные линии товара (`product_line`), поэтому ищем
   * сначала среди запчастей, а не найдя — среди колёс.
   */
  async function openPart(code: string): Promise<void> {
    setOpenError('');
    try {
      const found = await loadCatalog({
        q: '', vehicle: NO_VEHICLE, reserved: true, missing: true,
        warehouses: [], columns: { code }, words: {}, sort: 'code', desc: true,
        page: 0, size: 1,
      });
      if (!mounted.current) return;
      const partRow = found.rows[0];
      if (partRow !== undefined) {
        setCard(partRow);
        setCardWarehouses(found.warehouses);
        return;
      }
      const wheels = await listWheels({ ...EMPTY_WHEEL_QUERY, columns: { code } });
      if (!mounted.current) return;
      const wheelRow = wheels.rows[0];
      if (wheelRow !== undefined) {
        setCard(rowOfWheel(wheelRow));
        setCardWarehouses(wheels.warehouses);
        return;
      }
      setOpenError('Позиция не найдена — возможно, удалена');
    } catch (cause) {
      if (mounted.current) setOpenError(describe(cause, 'Карточка не открылась'));
    }
  }

  // Удалённые в счёт не идут: «Перевозок: 5» обязано значить пять увёзших.
  const live = (documents ?? []).filter((d) => d.deletedAt == null);
  const totalLines = live.reduce((sum, d) => sum + d.lines, 0);
  const removed = (documents ?? []).length - live.length;
  const anyDeletable = (documents ?? []).some((d) => d.deletable === true);

  return (
    <section className="screen screen--wide">
      <h2>Перевозки</h2>

      {error !== '' && <p className="note note--error">{error}</p>}
      {openError !== '' && <p className="note note--error">{openError}</p>}
      {notice !== null && (
        <p className={notice.error ? 'note note--error' : 'note'} role="status">{notice.text}</p>
      )}

      {card !== null && (
        <PartCard
          row={card}
          warehouses={cardWarehouses}
          rights={rights}
          onClose={() => setCard(null)}
          onChanged={() => setCard(null)}
        />
      )}

      {documents === null ? (
        error === '' ? <p className="note">Загружаем…</p> : null
      ) : documents.length === 0 ? (
        <p className="note">Перевозок пока не было.</p>
      ) : (
        <>
          <div className="table-scroll">
            <table className="report">
              <thead>
                <tr>
                  <th>Дата</th>
                  <th>Номер</th>
                  <th>Откуда</th>
                  <th>Куда</th>
                  <th className="num">Позиций</th>
                  <th>Примечание</th>
                  <th>Кто</th>
                  {anyDeletable && <th aria-label="Действия" />}
                </tr>
              </thead>
              <tbody>
                {documents.map((doc) => (
                  <Fragment key={doc.id}>
                    <tr
                      className="row--clickable"
                      onClick={() => toggle(doc.id)}
                    >
                      <td>{new Date(doc.createdAt).toLocaleString('ru-RU')}</td>
                      <td>
                        №{doc.number}
                        {/* Удалённая перевозка остаётся в журнале: её движения
                            в журнале склада никуда не делись (задача 0367). */}
                        {doc.deletedAt != null && (
                          <div className="moves__deleted">
                            Удалён{doc.deletedBy != null ? ` · ${doc.deletedBy}` : ''}
                          </div>
                        )}
                      </td>
                      <td>{doc.fromWarehouse}</td>
                      <td>{doc.toWarehouse}</td>
                      <td className="num">{doc.lines}</td>
                      <td>{doc.note ?? '—'}</td>
                      <td>{doc.author ?? '—'}</td>
                      {anyDeletable && (
                        <td onClick={(e) => e.stopPropagation()}>
                          {doc.deletable === true && (
                            asked === doc.id ? (
                              <span className="history__confirm">
                                <span className="muted">
                                  Удалить перемещение №{doc.number} целиком? Обратно уедет только
                                  свободное на складе «{doc.toWarehouse}».
                                </span>
                                <button type="button" disabled={deleting}
                                  onClick={() => void remove(doc.id)}>
                                  Удалить документ
                                </button>
                                <button type="button" className="button--ghost"
                                  onClick={() => setAsked(null)}>
                                  Отмена
                                </button>
                              </span>
                            ) : (
                              <button type="button" className="button--ghost" disabled={deleting}
                                aria-label={`Удалить: перемещение №${doc.number}`}
                                onClick={() => setAsked(doc.id)}>
                                Удалить
                              </button>
                            )
                          )}
                        </td>
                      )}
                    </tr>
                    {expanded === doc.id && (
                      <tr>
                        <td colSpan={anyDeletable ? 8 : 7}>
                          {linesLoading ? (
                            <p className="note">Загружаем состав…</p>
                          ) : linesError !== '' ? (
                            <p className="note note--error">{linesError}</p>
                          ) : (
                            <table className="report">
                              <thead>
                                <tr>
                                  {/* Номер позиции (задача 0168): состав
                                      документа открывают, чтобы назвать
                                      увезённое вслух. «Номер товара» —
                                      то же слово, каким публичный код зовут
                                      на витрине и в карточке. */}
                                  <th className="num">№ позиции</th>
                                  <th>Номер товара</th>
                                  <th>Наименование</th>
                                  {/* Состояние столбцом (задача 0318):
                                      принимающий сверяет, та ли деталь
                                      приехала. */}
                                  <th>Состояние</th>
                                  <th className="num">Количество</th>
                                </tr>
                              </thead>
                              <tbody>
                                {lines.map((line) => (
                                  <tr
                                    key={line.partId}
                                    className="row--clickable"
                                    onClick={() => void openPart(line.publicCode)}
                                  >
                                    <td className="num">{line.number}</td>
                                    <td>{line.publicCode}</td>
                                    {/* Номер производителя (задача 0329):
                                        принимающий сверяет по нему деталь. */}
                                    <td>{line.title}<OemWord number={line.primaryOem} /></td>
                                    <td>{conditionTitle(line.condition) ?? ''}</td>
                                    <td className="num">{line.qty}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
          <p className="note">
            Перевозок: {count(live.length)} на {positions(totalLines)}
            {removed > 0 && ` · удалённых: ${count(removed)}`}
          </p>
        </>
      )}
    </section>
  );
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}
