import { Fragment, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { loadMoveJournal, loadMoveLines, type MoveDocument, type MoveLine } from '../inventory/moves';
import { loadCatalog, NO_VEHICLE, type CatalogRow, type Warehouse } from '../inventory/catalog';
import { EMPTY_WHEEL_QUERY, listWheels, rowOfWheel } from '../inventory/wheels';
import { PartCard } from './PartCard';
import { count, positions } from '../ui/plural';

/**
 * Журнал перевозок между складами.
 *
 * <p>Единственное место, где было видно движение, — вкладка «Движения»
 * в истории одной позиции: ответить «что мы увезли на второй склад
 * в августе» было нельзя. Список — по документам, а не по строкам: тот же
 * ответ на «что уехало», но восемнадцать строк вместо тысячи с лишним.
 */
export function StockMovesScreen({ role }: { role: string }) {
  const [documents, setDocuments] = useState<MoveDocument[] | null>(null);
  const [error, setError] = useState('');

  const [expanded, setExpanded] = useState<number | null>(null);
  const [lines, setLines] = useState<MoveLine[]>([]);
  const [linesError, setLinesError] = useState('');
  const [linesLoading, setLinesLoading] = useState(false);

  const [card, setCard] = useState<CatalogRow | null>(null);
  const [cardWarehouses, setCardWarehouses] = useState<Warehouse[]>([]);
  const [openError, setOpenError] = useState('');

  useEffect(() => {
    loadMoveJournal()
      .then((found) => {
        setDocuments(found);
        setError('');
      })
      .catch((cause) => setError(describe(cause, 'Журнал перевозок не загрузился')));
  }, []);

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
        setLines(found);
        setLinesLoading(false);
      })
      .catch((cause) => {
        setLinesError(describe(cause, 'Состав документа не загрузился'));
        setLinesLoading(false);
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
      const partRow = found.rows[0];
      if (partRow !== undefined) {
        setCard(partRow);
        setCardWarehouses(found.warehouses);
        return;
      }
      const wheels = await listWheels({ ...EMPTY_WHEEL_QUERY, columns: { code } });
      const wheelRow = wheels.rows[0];
      if (wheelRow !== undefined) {
        setCard(rowOfWheel(wheelRow));
        setCardWarehouses(wheels.warehouses);
        return;
      }
      setOpenError('Позиция не найдена — возможно, удалена');
    } catch (cause) {
      setOpenError(describe(cause, 'Карточка не открылась'));
    }
  }

  const totalLines = (documents ?? []).reduce((sum, d) => sum + d.lines, 0);

  return (
    <section className="screen screen--wide">
      <h2>Перевозки</h2>

      {error !== '' && <p className="note note--error">{error}</p>}
      {openError !== '' && <p className="note note--error">{openError}</p>}

      {card !== null && (
        <PartCard
          row={card}
          warehouses={cardWarehouses}
          role={role}
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
                      <td>№{doc.number}</td>
                      <td>{doc.fromWarehouse}</td>
                      <td>{doc.toWarehouse}</td>
                      <td className="num">{doc.lines}</td>
                      <td>{doc.note ?? '—'}</td>
                      <td>{doc.author ?? '—'}</td>
                    </tr>
                    {expanded === doc.id && (
                      <tr>
                        <td colSpan={7}>
                          {linesLoading ? (
                            <p className="note">Загружаем состав…</p>
                          ) : linesError !== '' ? (
                            <p className="note note--error">{linesError}</p>
                          ) : (
                            <table className="report">
                              <thead>
                                <tr>
                                  <th>Публичный код</th>
                                  <th>Наименование</th>
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
                                    <td>{line.publicCode}</td>
                                    <td>{line.title}</td>
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
            Перевозок: {count(documents.length)} на {positions(totalLines)}
          </p>
        </>
      )}
    </section>
  );
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}
