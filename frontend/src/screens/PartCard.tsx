import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  addApplicability,
  loadApplicability,
  loadPhotos,
  removeApplicability,
  writeOffPart,
  type Applicability,
  type CatalogRow,
  type PartPhoto,
  type Warehouse,
} from '../inventory/catalog';
import { loadCached, modelsOf, type VehicleCatalog } from '../catalog/vehicles';

/**
 * Карточка позиции — как в кабинете, на который клиент смотрит каждый день:
 * узкий столбец сведений слева, крупный снимок справа.
 *
 * <p>Порядок полей взят оттуда же, и он не случайный: сначала то, что нужно
 * продавцу по телефону — где лежит, сколько стоит, какой номер, — и только
 * потом машина и номера производителя. Алфавитный порядок или порядок колонок
 * таблицы заставил бы искать цену глазами.
 *
 * <p>Данные берутся из уже загруженной строки: в ней все двадцать с лишним
 * колонок, включая скрытые в таблице. Второй запрос делается только
 * за снимками — их ссылки подписанные и короткоживущие.
 */
export function PartCard({ row, warehouses, role, onClose, onWrittenOff }: {
  row: CatalogRow;
  warehouses: Warehouse[];
  role: string;
  onClose: () => void;
  onWrittenOff: () => void;
}) {
  // Списывает тот, кто отвечает за деньги: списанная деталь — это убыток,
  // а не запись в журнале. Кладовщик находит недостачу, решение не его.
  const [writingOff, setWritingOff] = useState(false);
  const [reason, setReason] = useState('');
  const [writeOffAt, setWriteOffAt] = useState<number | null>(null);
  const [writeOffQty, setWriteOffQty] = useState('1');
  const [writeOffError, setWriteOffError] = useState('');
  const [photos, setPhotos] = useState<PartPhoto[]>([]);
  const [shown, setShown] = useState(0);
  const [tab, setTab] = useState<'about' | 'fits'>('about');
  const [fits, setFits] = useState<Applicability[] | null>(null);
  // Справочник машин для добавления: он предзагружен и лежит в IndexedDB,
  // тянуть четыре с половиной тысячи моделей ради одной правки незачем.
  const [vehicles, setVehicles] = useState<VehicleCatalog | null>(null);
  const [brandId, setBrandId] = useState<number | null>(null);
  const [modelId, setModelId] = useState<number | null>(null);

  useEffect(() => {
    void loadPhotos(row.id).then(setPhotos).catch(() => setPhotos([]));
    // Применимость нужна и на вкладке «Описание»: там стоит отметка,
    // задана она или нет, и отметка «сейчас узнаю» была бы бесполезной.
    void loadApplicability(row.id).then(setFits).catch(() => setFits([]));
    void loadCached().then(setVehicles).catch(() => setVehicles(null));
  }, [row.id]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
      if (e.key === 'ArrowRight') {
        setShown((i) => (i + 1) % Math.max(photos.length, 1));
      }
      if (e.key === 'ArrowLeft') {
        setShown((i) => (i - 1 + photos.length) % Math.max(photos.length, 1));
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, photos.length]);

  // Только там, где что-то лежит: строка «Кузов —» не сообщает ничего,
  // а таких строк набирается больше, чем заполненных.
  const rows: Array<[string, string]> = [];
  const add = (title: string, value: string | number | null) => {
    if (value !== null && value !== undefined && String(value) !== '') {
      rows.push([title, String(value)]);
    }
  };

  add('Номер товара', row.code);
  add('Поставка', row.supply);
  add('Оценка состояния', row.qualityGrade);
  add('Марка', row.brand);
  add('Модель', row.model);
  add('Модель кузова', row.body);
  add('Модель двигателя', row.engine);
  add('Год выпуска', row.year);
  add('Комплектация', row.equipment);
  add('Поколение', row.generation);
  add('Номер донора', row.donorCode);
  add('Передний / Задний', row.sideFr === null ? null : SIDE_FR[row.sideFr] ?? row.sideFr);
  add('Левый / Правый', row.sideLr === null ? null : SIDE_LR[row.sideLr] ?? row.sideLr);
  add('Цвет', row.color);
  add('Секция', row.section);
  add('Производитель', row.manufacturer);
  add('Номер производителя', row.oem);
  add('Кросс-номера', row.crosses);
  add('Маркировка', row.marking);
  add('Заметка', row.note);

  const main = photos[shown];

  async function writeOff(): Promise<void> {
    if (writeOffAt === null) {
      return;
    }
    setWriteOffError('');
    try {
      await writeOffPart(writeOffAt, row.id, Number(writeOffQty), reason.trim());
      onWrittenOff();
    } catch (cause) {
      // 409 — «обещано покупателю» или «столько не лежит», и текст оттуда
      // называет числа: без них человек идёт искать поломку сервера.
      setWriteOffError(cause instanceof ApiError && cause.message !== ''
        ? cause.message
        : 'Списать не вышло');
    }
  }

  async function addVehicle(): Promise<void> {
    if (brandId === null) {
      return;
    }
    setFits(await addApplicability(row.id, brandId, modelId));
    setModelId(null);
  }

  async function drop(id: number): Promise<void> {
    setFits(await removeApplicability(row.id, id));
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="card-view" onClick={(event) => event.stopPropagation()}>
        <header className="card-view__head">
          <h2>{row.title}</h2>
          <button type="button" className="button--ghost" onClick={onClose}>
            Закрыть
          </button>
        </header>

        <div className="card-view__body">
          <div className="card-view__side">
            <div className="card-tabs">
              <button
                type="button"
                className={tab === 'about' ? 'card-tab card-tab--active' : 'card-tab'}
                onClick={() => setTab('about')}
              >
                Описание
              </button>
              <button
                type="button"
                className={tab === 'fits' ? 'card-tab card-tab--active' : 'card-tab'}
                onClick={() => setTab('fits')}
              >
                Применимость
              </button>
            </div>

            {tab === 'fits' ? (
              <div className="card-view__fits">
                {fits === null ? (
                  <p className="muted">Загружаем…</p>
                ) : fits.length > 0 ? (
                  <ul>
                    {fits.map((fit) => (
                      <li key={fit.id}>
                        {[fit.brand, fit.model, fit.generation].filter((v) => v).join(' ')}
                        {fit.yearFrom !== null && ` · ${fit.yearFrom}—${fit.yearTo ?? ''}`}
                        {!fit.verified && <span className="muted"> · из наименования</span>}
                        <button
                          type="button"
                          className="mark__link"
                          onClick={() => void drop(fit.id)}
                        >
                          убрать
                        </button>
                      </li>
                    ))}
                  </ul>
                ) : row.brand !== null ? (
                  <p className="note">
                    Применимость не задана. Деталь находится подбором по машине
                    донора — {[row.brand, row.model].filter((v) => v).join(' ')}, — но
                    к другим машинам, к которым она тоже подходит, её не найдут.
                  </p>
                ) : (
                  <p className="note note--error">
                    Ни применимости, ни донора. По машине эту деталь не найти
                    вовсе — только поиском по названию или номеру.
                  </p>
                )}

                {/* Добавление руками: разбор из наименования берёт только
                    то, что там написано, а деталь подходит и к другим
                    машинам — знает об этом человек. */}
                {vehicles !== null && (
                  <div className="card-view__add">
                    <label className="field">
                      Марка
                      <select
                        value={brandId ?? ''}
                        onChange={(e) => {
                          setBrandId(e.target.value === '' ? null : Number(e.target.value));
                          setModelId(null);
                        }}
                      >
                        <option value="">—</option>
                        {vehicles.brands.map((brand) => (
                          <option key={brand.id} value={brand.id}>{brand.name}</option>
                        ))}
                      </select>
                    </label>
                    <label className="field">
                      Модель
                      <select
                        value={modelId ?? ''}
                        onChange={(e) =>
                          setModelId(e.target.value === '' ? null : Number(e.target.value))}
                        disabled={brandId === null}
                      >
                        <option value="">любая</option>
                        {modelsOf(vehicles, brandId).map((model) => (
                          <option key={model.id} value={model.id}>{model.name}</option>
                        ))}
                      </select>
                    </label>
                    <button type="button" disabled={brandId === null} onClick={() => void addVehicle()}>
                      Добавить машину
                    </button>
                  </div>
                )}
              </div>
            ) : (
            <>
            {/* Отметки состояния — как в кабинете: видно сразу, привязана ли
                деталь к машине. Отрицательное состояние важнее положительного:
                деталь без донора и без применимости не находится подбором,
                и узнать об этом надо здесь, а не когда покупатель не позвонил. */}
            <div className="card-marks">
              <span className={row.donorCode === null ? 'mark mark--off' : 'mark'}>
                {row.donorCode === null ? 'Донор не задан' : 'Донор задан'}
              </span>
              <span className={fits !== null && fits.length > 0 ? 'mark' : 'mark mark--off'}>
                {fits !== null && fits.length > 0
                  ? 'Применимость задана'
                  : 'Применимость не задана'}
                <button type="button" className="mark__link" onClick={() => setTab('fits')}>
                  Посмотреть
                </button>
              </span>
            </div>

            {/* Остаток по складам первым: продавец по телефону отвечает
                «есть, лежит на Ткацкой», а не «сейчас посмотрю». */}
            <div className="card-view__stock">
              {warehouses.map((warehouse) => (
                <div key={warehouse.id}>
                  <span>{warehouse.name}</span>
                  <b>{row.stock[String(warehouse.id)] ?? '—'}</b>
                </div>
              ))}
            </div>

            <div className="card-view__price">
              {row.price === null ? '—' : row.price.toLocaleString('ru-RU')}
              <span> ₽</span>
            </div>

            {/* Списание здесь, а не на отдельном экране: решение принимают,
                глядя на карточку — что это за деталь, сколько её и где она
                лежит. Недостача из пересчёта обнуляет остаток, но карточку
                оставляет «в наличии»: закрыть её можно только отсюда. */}
            {(role === 'OWNER' || role === 'MANAGER') && (
              writingOff ? (
                <div className="card-view__writeoff">
                  <label className="field">
                    Со склада
                    <select
                      value={writeOffAt ?? ''}
                      onChange={(e) => setWriteOffAt(
                        e.target.value === '' ? null : Number(e.target.value))}
                    >
                      <option value="">—</option>
                      {warehouses
                        .filter((w) => Number(row.stock[String(w.id)] ?? 0) > 0)
                        .map((w) => (
                          <option key={w.id} value={w.id}>
                            {w.name} · {row.stock[String(w.id)]}
                          </option>
                        ))}
                    </select>
                  </label>
                  <label className="field">
                    Сколько
                    <input
                      inputMode="decimal"
                      value={writeOffQty}
                      onChange={(e) => setWriteOffQty(e.target.value.replace(',', '.'))}
                    />
                  </label>
                  <label className="field">
                    Причина
                    <input
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      placeholder="разбита при разборе"
                    />
                  </label>
                  {writeOffError !== '' && (
                    <p className="note note--error">{writeOffError}</p>
                  )}
                  <div className="filter-row">
                    <button
                      type="button"
                      disabled={writeOffAt === null || reason.trim() === ''}
                      onClick={() => void writeOff()}
                    >
                      Списать
                    </button>
                    <button
                      type="button"
                      className="button--ghost"
                      onClick={() => setWritingOff(false)}
                    >
                      Отмена
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  type="button"
                  className="button--ghost"
                  onClick={() => setWritingOff(true)}
                >
                  Списать
                </button>
              )
            )}

            {row.description !== null && row.description !== '' && (
              <p className="card-view__note">{row.description}</p>
            )}

            <dl className="card-view__fields">
              {rows.map(([title, value]) => (
                <div key={title}>
                  <dt>{title}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            </>
            )}
          </div>

          <div className="card-view__photos">
            {main === undefined ? (
              <p className="muted">Снимков нет</p>
            ) : (
              <>
                <div className="card-view__frame">
                  {photos.length > 1 && (
                    <button
                      type="button"
                      className="card-view__arrow card-view__arrow--prev"
                      onClick={() => setShown((i) => (i - 1 + photos.length) % photos.length)}
                    >
                      ‹
                    </button>
                  )}
                  <img className="card-view__main" src={main.url} alt="" />
                  {photos.length > 1 && (
                    <button
                      type="button"
                      className="card-view__arrow card-view__arrow--next"
                      onClick={() => setShown((i) => (i + 1) % photos.length)}
                    >
                      ›
                    </button>
                  )}
                </div>
                {photos.length > 1 && (
                  <div className="card-view__strip">
                    {photos.map((photo, i) => (
                      <button
                        key={photo.photoId}
                        type="button"
                        className={i === shown ? 'thumb-button is-shown' : 'thumb-button'}
                        onClick={() => setShown(i)}
                      >
                        <img className="thumb" src={photo.url} alt="" />
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

const SIDE_LR: Record<string, string> = { LEFT: 'лев.', RIGHT: 'прав.' };
const SIDE_FR: Record<string, string> = { FRONT: 'перед.', REAR: 'задн.' };
