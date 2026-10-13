import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { count, plural } from '../ui/plural';
import {
  DROM_TRANSFER,
  duplicateColumns,
  FIELDS,
  notTransferred,
  applicabilityFromDrom,
  applicabilityFromTitles,
  importBazon,
  importFile,
  importWheels,
  migratePhotos,
  photoStatus,
  retryPhotos,
  missingRequired,
  previewFile,
  quantityNote,
  splitSkipped,
} from '../import/warehouseImport';
import type {
  BazonResult,
  DromApplicabilityReport,
  FieldKey,
  ParsedApplicability,
  WheelImportResult,
  PhotoProgress,
  Preview,
  RepeatReport,
  Report,
} from '../import/warehouseImport';
import type { Reference } from '../reference/reference';
import { useMounted } from '../ui/useMounted';

/**
 * Перенос склада из таблицы.
 *
 * <p><b>Разбор показывается целиком, а не галочкой «всё распознано».</b>
 * Сопоставление колонок — догадка по заголовкам чужой таблицы, и ошибка в ней
 * тихая: перепутанные цена и количество дают склад, где всё по три рубля,
 * а замечают это на первой продаже. Поэтому владелец видит и сопоставление,
 * и первые строки файла — на них подмена видна сразу.
 */
interface Props {
  reference: Reference;
  canImport: boolean;
}

export function ImportScreen({ reference, canImport }: Props) {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [columns, setColumns] = useState<Partial<Record<FieldKey, number>>>({});
  // Склад не подставляется: это самая разрушительная операция в системе —
  // тысячи позиций, отменяемые только восстановлением из бэкапа, — и первый
  // склад списка у клиента с тремя складами оказывался пустым «54 YARD».
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  // Счётчик переносов: по его смене оживает очередь снимков ниже.
  const [imported, setImported] = useState(0);
  // Ключ идемпотентности живёт от выбора файла до успеха: повтор после
  // ошибки обязан отдать прежний итог, а не завести второй склад.
  const [requestId, setRequestId] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  if (!canImport) {
    return (
      <section className="card">
        <h2>Загрузка склада</h2>
        <p className="note">
          Загружать склад может только владелец: импорт заводит тысячи позиций,
          и отменить его можно лишь восстановлением из бэкапа.
        </p>
      </section>
    );
  }

  const missing = missingRequired(columns);
  const duplicates = duplicateColumns(columns);
  const skipped = preview === null ? [] : notTransferred(preview.header, columns);
  const isDrom = (preview?.format ?? null) === DROM_TRANSFER;
  // «Наличие» — поле только файла с Дрома: у обычной таблицы его не предлагаем.
  const shownFields = FIELDS.filter(
    (f) => !f.dromOnly || isDrom || columns[f.key] !== undefined);
  const quantity = preview === null ? null : quantityNote(columns, preview.rows);

  return (
    <section className="card">
      <h2>Загрузка склада</h2>

      {report === null && (
        <>
          <p className="note">
            Файл .xlsx с вашим складом. Старый .xls пересохраните в Excel —
            это другой формат, мы его не читаем.
          </p>

          <input
            type="file"
            accept=".xlsx"
            onChange={(e) => {
              const picked = e.target.files?.[0] ?? null;
              setFile(picked);
              setRequestId(crypto.randomUUID());
              setPreview(null);
              setColumns({});
              setError(null);
              if (picked !== null) {
                void showPreview(picked);
              }
            }}
          />
        </>
      )}

      {error !== null && <p className="note note--error">{error}</p>}

      {preview !== null && report === null && (
        <>
          <hr />
          <h3>Что распознано</h3>
          {isDrom ? (
            <p className="note">
              Это файл переноса с Дрома: колонки сопоставлены сами, выбирать
              ничего не нужно.
            </p>
          ) : (
            <p className="note">
              Проверьте до запуска. Перепутанные цена и количество дадут склад,
              где всё стоит по три рубля, и заметно это станет на первой продаже.
            </p>
          )}

          <ul className="mapping">
            {shownFields.map((field) => (
              <li key={field.key} className="mapping-row">
                <span>
                  {field.label}
                  {field.required && <span className="muted"> · обязательно</span>}
                </span>
                <select
                  value={columns[field.key] ?? ''}
                  onChange={(e) =>
                    setColumns({
                      ...columns,
                      [field.key]: e.target.value === '' ? undefined : Number(e.target.value),
                    })
                  }
                >
                  <option value="">— нет в файле</option>
                  {preview.header.map((title, index) => (
                    <option key={index} value={index}>
                      {title === '' ? `колонка ${index + 1}` : title}
                    </option>
                  ))}
                </select>
              </li>
            ))}
          </ul>

          {duplicates.length > 0 && (
            <p className="note note--error">
              Одна колонка назначена двум полям — так склад заведётся неверно.
            </p>
          )}
          {missing.length > 0 && (
            <p className="note note--error">Не сопоставлено: {missing.join(', ')}</p>
          )}
          {/* Одна штука — догадка, и она говорится до загрузки, а не
              обнаруживается на складе (задача 0279). */}
          {quantity !== null && <p className="note">{quantity}</p>}
          {columns.AVAILABILITY !== undefined && (
            <p className="note">
              Строки, где «Наличие» не «В наличии», загрузка пропустит и назовёт.
            </p>
          )}
          {/* Колонка без поля не уедет никуда, и молчание о ней читается как
              «перенесётся». У файла переноса с Дрома это снимки, описание
              и номер объявления — то, ради чего перенос и затевают. */}
          {skipped.length > 0 && (
            <p className="note">
              Не переносятся — загрузка их пропустит: {skipped.join(', ')}.
            </p>
          )}

          <h3>Первые строки файла</h3>
          <div className="table-scroll">
            <table className="preview">
              <thead>
                <tr>
                  {preview.header.map((title, index) => (
                    <th key={index} className={fieldAt(index) !== null ? 'mapped' : undefined}>
                      {title === '' ? `колонка ${index + 1}` : title}
                      <div className="muted">
                        {fieldAt(index) !== null ? `→ ${fieldAt(index)}` : 'не переносится'}
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {preview.rows.map((row, at) => (
                  <tr key={at}>
                    {preview.header.map((_, index) => (
                      <td key={index} className={fieldAt(index) !== null ? 'mapped' : undefined}>
                        {row[index] ?? ''}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <label>
            Склад
            <select
              value={warehouseId ?? ''}
              onChange={(e) => setWarehouseId(
                e.target.value === '' ? null : Number(e.target.value))}
            >
              <option value="">— выберите склад —</option>
              {reference.warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name}
                </option>
              ))}
            </select>
          </label>

          <button
            type="button"
            disabled={
              busy || missing.length > 0 || duplicates.length > 0 || warehouseId === null
            }
            onClick={() => void run()}
          >
            {busy ? 'Загружаем…' : 'Загрузить склад'}
          </button>
        </>
      )}

      {report !== null && (
        <>
          <hr />
          <h3>Заведено новых: {count(report.imported)}</h3>
          {(report.quantityDefaulted ?? 0) > 0 && (
            <p className="note">
              По 1 шт. заведено позиций: {count(report.quantityDefaulted ?? 0)} —
              количества в файле для них не было. Где штук больше, поправьте
              остаток.
            </p>
          )}
          <RepeatSummary repeat={report.repeat} />
          {report.applicability != null && (
            <>
              <h4>Применимость из блока «Подходит»</h4>
              <DromFitsReport report={report.applicability} />
            </>
          )}
          <SkippedReport skipped={report.skipped} />
          {columns.PHOTOS !== undefined
            && (report.imported > 0 || refilledLinks(report.repeat) > 0) && (
            <p className="note">
              Ссылки на снимки поставлены в очередь. Сами файлы переносятся
              ниже, в «Фотографиях», кнопкой «Перенести все».
            </p>
          )}
          <p className="note">
            Наименования попали в общий справочник. Те, что не сопоставились
            с эталоном, ждут на экране нераспознанных — до этого они не уедут
            на площадку.
          </p>
          <button type="button" onClick={reset}>
            Загрузить ещё файл
          </button>
        </>
      )}

      {/* Перенос и то, что делается после него, — разные компоненты, но
          счётчик очереди снимков обязан ожить сразу после переноса: иначе
          он показывает «ждёт 0» при полной очереди, и владелец решает,
          что переносить нечего. Ровно так у живого клиента и осталось
          восемь снимков вместо ста девяноста тысяч. */}
      <BazonImport onImported={() => setImported((n) => n + 1)} />

      <WheelImport reference={reference} />
      <AfterImport reload={imported} />
    </section>
  );

  /** Какое поле показывает эта колонка — для подсветки в таблице. */
  function fieldAt(index: number): string | null {
    const found = FIELDS.find((f) => columns[f.key] === index);
    return found?.label ?? null;
  }

  async function showPreview(picked: File): Promise<void> {
    setBusy(true);
    try {
      const parsed = await previewFile(picked);
      if (mounted.current) {
        setPreview(parsed);
        // Догадку сервера берём как отправную точку, но показываем целиком:
        // подтверждает её человек, а не молчание.
        setColumns(parsed.detected);
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Файл не разобрался'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function run(): Promise<void> {
    if (file === null || warehouseId === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const done = await importFile(file, warehouseId, columns, requestId);
      if (mounted.current) {
        setReport(done);
        // Снимки из колонки «Фото» встали в очередь — счётчик ниже обязан
        // это показать сразу, иначе он стоит на «ждёт 0» при полной очереди
        // и владелец решает, что переносить нечего (та же беда, что была
        // у переноса из предыдущей системы).
        setImported((n) => n + 1);
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Загрузка не прошла'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  function reset(): void {
    setFile(null);
    setRequestId('');
    setPreview(null);
    setColumns({});
    setReport(null);
    setError(null);
  }
}

/**
 * Перенос из предыдущей системы.
 *
 * <p>Отдельно от загрузки таблицы: выгрузка приходит двумя файлами и несёт
 * то, чего в таблице нет вовсе — машины, поставки, резервы по складам.
 * Сопоставлять колонки тут не надо: формат чужой системы известен.
 */
/**
 * Что доделывается после переноса склада.
 *
 * <p>Оба шага отделены от самого переноса намеренно. Фотографии — это сотня
 * тысяч файлов с чужого CDN, часы работы: внутри запроса импорта соединение
 * оборвётся. Применимость — проход по всем заголовкам, и запускать её надо
 * после того, как справочник наименований разобран, иначе часть машин ещё
 * не будет узнана.
 */
function AfterImport({ reload }: { reload: number }) {
  const [photos, setPhotos] = useState<PhotoProgress | null>(null);
  const [fits, setFits] = useState<ParsedApplicability | null>(null);
  const [dromFits, setDromFits] = useState<DromApplicabilityReport | null>(null);
  const [busy, setBusy] = useState<'photos' | 'fits' | 'drom' | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();
  // Останов читается из ref, а не из состояния: цикл живёт внутри одного
  // вызова и обновлённого состояния не увидит.
  const stop = useRef(false);

  useEffect(() => {
    void photoStatus()
      .then((found) => { if (mounted.current) setPhotos(found); })
      .catch(() => { if (mounted.current) setPhotos(null); });
  }, [reload, mounted]);

  // Уход с раздела останавливает проход. Иначе цикл живёт дальше — промис
  // размонтированием не отменяется, — а кнопки «Остановить» на экране уже
  // нет; вернувшись, владелец увидит «Перенести все» и запустит второй
  // проход рядом с первым.
  useEffect(() => () => { stop.current = true; }, []);

  return (
    <>
      <h2>После переноса</h2>

      {error !== null && <p className="note note--error">{error}</p>}

      <div className="card">
        <h3>Фотографии</h3>
        {photos === null ? (
          <p className="muted">Состояние неизвестно</p>
        ) : (
          <p className="note">
            Перенесено {count(photos.total)}, ждёт {count(photos.pending)}
            {photos.broken > 0 && `, не вышло ${count(photos.broken)}`}.
            {busy === 'photos' && ' Идёт перенос…'}
          </p>
        )}
        <div className="filter-row">
          {busy === 'photos' ? (
            <button type="button" className="button--ghost" onClick={() => { stop.current = true; }}>
              Остановить
            </button>
          ) : (
            <button type="button" disabled={busy !== null} onClick={() => void pullPhotos()}>
              Перенести все
            </button>
          )}
          {busy !== 'photos' && photos !== null && photos.broken > 0 && (
            <button
              type="button"
              className="button--ghost"
              disabled={busy !== null}
              onClick={() => void retry()}
            >
              Вернуть неудачные в очередь
            </button>
          )}
        </div>
        <p className="note">
          Пачками, потому что сотня тысяч файлов с чужого CDN — это часы,
          а запрос столько не живёт. Пачки идут одна за другой сами: у клиента
          их бывает под двести тысяч, и нажимать на каждую значило бы простоять
          у экрана смену. Вкладку до конца не закрывать и с раздела
          не уходить — уход останавливает проход. Терять при этом нечего:
          перенесённое остаётся перенесённым, продолжить можно позже.
        </p>
      </div>

      <div className="card">
        <h3>Применимость из наименований</h3>
        {fits !== null && (
          <p className="note">
            Разобрано позиций: {fits.parts}, добавлено строк: {fits.added}.
          </p>
        )}
        <button type="button" disabled={busy !== null} onClick={() => void parseTitles()}>
          {busy === 'fits' ? 'Разбираем…' : 'Проставить применимость'}
        </button>
        <p className="note">
          Деталь, подходящая к нескольким машинам, донора не имеет — машины
          у неё названы прямо в наименовании. Без этого прохода подбор
          по машине её не находит вовсе. Разбираются только марки и модели
          из справочника; повтор ничего не дублирует.
        </p>
      </div>

      <div className="card">
        <h3>Применимость из карточек Дрома</h3>
        {dromFits !== null && <DromFitsReport report={dromFits} />}
        <button type="button" disabled={busy !== null} onClick={() => void parseDrom()}>
          {busy === 'drom' ? 'Раскладываем…' : 'Разложить «Подходит»'}
        </button>
        <p className="note">
          У позиций, перенесённых с Дрома, блок «Подходит» лежит текстом:
          «Toyota 4Runner — 5 поколение (2009–2013)». Кнопка раскладывает его
          по справочнику машин — марка, модель, поколение с годами, — и по ним
          работает подбор по машине. Текст объявления не меняется, машины,
          добавленные в карточке руками, не трогаются, повтор ничего
          не дублирует. При загрузке файла это делается само.
        </p>
      </div>
    </>
  );

  /**
   * Гонит пачки, пока очередь не опустеет.
   *
   * <p>Пачка — это запрос, который живёт секунды; очередь — сотни тысяч
   * снимков. Пока за каждую пачку отвечало нажатие, у живого клиента
   * получалось 961 нажатие подряд, и экран сам это и предлагал. Перенос
   * при этом остаётся прерываемым: остановились — перенесённое осталось.
   */
  async function pullPhotos(): Promise<void> {
    setBusy('photos');
    setError(null);
    stop.current = false;
    try {
      // Исходная длина берётся из показанного состояния: иначе первая
      // пачка не с чем сравнить, и по неподвижной очереди проход сделал бы
      // лишний заход к чужому CDN.
      let left = photos?.pending ?? Number.POSITIVE_INFINITY;
      while (!stop.current) {
        // Двести, а не пятьсот: минимизировать надо не число запросов —
        // цикл идёт сам, и лишние round-trip'ы ничего не стоят, — а время
        // одного. Пятьсот снимков это пятьдесят секунд, то есть запрос
        // на грани таймаута терминатора и почти минута, в которую счётчик
        // на экране стоит. Замерено живьём: десять снимков в секунду.
        const next = await migratePhotos(200);
        if (!mounted.current) return;
        setPhotos(next);
        // Очередь не сдвинулась — дальше ходить незачем: так выглядит
        // пачка, целиком легшая в неудачные, и без этой проверки цикл
        // молотил бы вечно.
        if (next.pending === 0 || next.pending >= left) {
          break;
        }
        left = next.pending;
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Перенос фотографий не прошёл'));
    } finally {
      stop.current = false;
      if (mounted.current) setBusy(null);
    }
  }

  async function retry(): Promise<void> {
    setBusy('photos');
    try {
      const found = await retryPhotos();
      if (mounted.current) setPhotos(found);
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Не удалось вернуть в очередь'));
    } finally {
      if (mounted.current) setBusy(null);
    }
  }

  async function parseDrom(): Promise<void> {
    setBusy('drom');
    setError(null);
    try {
      const report = await applicabilityFromDrom();
      if (mounted.current) setDromFits(report);
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Разбор «Подходит» не прошёл'));
    } finally {
      if (mounted.current) setBusy(null);
    }
  }

  async function parseTitles(): Promise<void> {
    setBusy('fits');
    setError(null);
    try {
      const parsed = await applicabilityFromTitles();
      if (mounted.current) setFits(parsed);
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Разбор применимости не прошёл'));
    } finally {
      if (mounted.current) setBusy(null);
    }
  }
}

/**
 * Итог раскладки «Подходит» словами (задача 0289).
 *
 * <p>Повтор, не сделавший ничего, говорит это прямо: «заведено 0» среди
 * трёх нулей читается как сбой, а это обычный второй проход. Не легшее
 * называется по причинам и уникальными названиями из блока клиента —
 * по ним видно, чего нет в справочнике машин.
 */
function DromFitsReport({ report }: { report: DromApplicabilityReport }) {
  const changed = report.added + report.refined + report.removed;
  const notLaid = report.misses.reduce((sum, miss) => sum + miss.items, 0);
  return (
    <>
      {changed === 0 ? (
        <p className="note">
          Ничего нового: проверено {count(report.parts)}{' '}
          {plural(report.parts, 'позиция', 'позиции', 'позиций')}, применимость уже разложена.
        </p>
      ) : (
        <p className="note">
          Позиций обработано: {count(report.parts)}. Строк применимости заведено:{' '}
          {count(report.added)}, дополнено годами: {count(report.refined)}, снято менее
          точных: {count(report.removed)}.
        </p>
      )}
      {notLaid > 0 && (
        <>
          <p className="note">Пунктов, не легших в справочник целиком: {count(notLaid)}.</p>
          <ul className="note">
            {report.misses.map((miss) => (
              <li key={miss.reason}>
                {miss.reason}
                {miss.reason === 'поколение не найдено' && ' (марка, модель и годы легли)'}:{' '}
                {miss.names.join(', ')}
                {miss.unique > miss.names.length &&
                  ` и ещё ${count(miss.unique - miss.names.length)}`}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

/**
 * Перенос шин и дисков — отдельным файлом.
 *
 * <p>Колёса лежат у Bazon на своей вкладке и в выгрузку товаров не попадают:
 * в её сорока восьми колонках нет ни ширины, ни профиля, ни сезона. Пока
 * этого блока не было, переехавший клиент терял весь колёсный склад —
 * 65 позиций, 221 карточку с учётом комплектов, — и узнать об этом мог
 * только по пустой вкладке «Шины и диски».
 */
function WheelImport({ reference }: { reference: Reference }) {
  const [file, setFile] = useState<File | null>(null);
  const [warehouseId, setWarehouseId] = useState<number | null>(null);
  const [result, setResult] = useState<WheelImportResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  return (
    <>
      <hr />
      <h3>Шины и диски</h3>
      <p className="note">
        Третий файл из кабинета — выгрузка колёс. В выгрузке товаров их нет
        вовсе: ни ширины, ни профиля, ни сезона там не бывает. Комплект
        из четырёх станет четырьмя карточками, как и в кабинете.
      </p>

      <label>
        Выгрузка шин и дисков
        <input
          type="file"
          accept=".csv"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </label>

      {/* Склад спрашивается, а не подставляется: какой правильный, знает
          только владелец, а уехавший не туда товар ищут глазами. */}
      <label>
        Склад
        <select
          value={warehouseId ?? ''}
          onChange={(e) => setWarehouseId(
            e.target.value === '' ? null : Number(e.target.value))}
        >
          <option value="">— выберите склад —</option>
          {reference.warehouses.map((w) => (
            <option key={w.id} value={w.id}>{w.name}</option>
          ))}
        </select>
      </label>

      {error !== null && <p className="note note--error">{error}</p>}

      <button
        type="button"
        disabled={busy || file === null || warehouseId === null}
        onClick={() => void run()}
      >
        {busy ? 'Переносим…' : 'Перенести колёса'}
      </button>

      {result !== null && (
        <>
          <p className="note">
            Заведено карточек: {result.created} из {result.sets}{' '}
            {plural(result.sets, 'строки', 'строк', 'строк')} файла
            {result.skipped > 0 && `, пропущено уже перенесённых: ${result.skipped}`}
            {result.photos > 0 && `, снимков в очередь: ${result.photos}`}.
          </p>
          {result.problems.length > 0 && (
            <ul className="suggestions">
              {result.problems.slice(0, 20).map((problem) => (
                <li key={problem}>{problem}</li>
              ))}
            </ul>
          )}
        </>
      )}
    </>
  );

  async function run(): Promise<void> {
    if (file === null || warehouseId === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const done = await importWheels(file, warehouseId);
      if (mounted.current) setResult(done);
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Перенос колёс не прошёл'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function BazonImport({ onImported }: { onImported: () => void }) {
  const [donors, setDonors] = useState<File | null>(null);
  const [catalog, setCatalog] = useState<File | null>(null);
  const [result, setResult] = useState<BazonResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  return (
    <>
      <hr />
      <h3>Перенос из предыдущей системы</h3>
      <p className="note">
        Два файла из кабинета: выгрузка машин и выгрузка товаров. Вместе с ними
        переедут поставки, доноры и резервы по складам — в обычную таблицу
        это не помещается.
      </p>

      <label>
        Выгрузка машин
        <input
          type="file"
          accept=".csv"
          onChange={(e) => setDonors(e.target.files?.[0] ?? null)}
        />
      </label>

      <label>
        Выгрузка товаров
        <input
          type="file"
          accept=".csv"
          onChange={(e) => setCatalog(e.target.files?.[0] ?? null)}
        />
      </label>

      {error !== null && <p className="note note--error">{error}</p>}

      <button
        type="button"
        disabled={busy || donors === null || catalog === null}
        onClick={() => void run()}
      >
        {busy ? 'Переносим…' : 'Перенести склад'}
      </button>

      {result !== null && (
        <>
          <h4>Перенесено</h4>
          <ul className="suggestions">
            {Object.entries(result.loaded).map(([what, count]) => (
              <li key={what}>
                {what}: {count}
              </li>
            ))}
          </ul>

          {result.problemCount > 0 ? (
            <>
              <p className="note note--error">
                Не перенеслось строк: {result.problemCount}. Номера — как
                в исходном файле, искать по ним.
              </p>
              <ul className="suggestions">
                {result.problems.slice(0, 20).map((problem) => (
                  <li key={problem.line}>
                    строка {problem.line} — {problem.message}
                  </li>
                ))}
              </ul>
            </>
          ) : (
            <p className="note">Все строки приняты.</p>
          )}

          <p className="note">
            Повторить перенос безопасно: уже загруженное узнаётся по номерам
            из самой выгрузки и второй раз не заводится.
          </p>
        </>
      )}
    </>
  );

  async function run(): Promise<void> {
    if (donors === null || catalog === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const done = await importBazon(donors, catalog);
      if (mounted.current) {
        setResult(done);
        onImported();
      }
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Перенос не прошёл'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. Файл не загружен — повторите.';
    }
    if (cause.status === 403) {
      return 'Загружать склад может только владелец';
    }
    return cause.message;
  }
  return fallback;
}

/**
 * Что из файла не легло: строки первого листа — списком под пределом
 * в двадцать, повреждения остальных листов — отдельно и все (задача 0281).
 *
 * <p>Повреждения листа «Продолжение» — это значение, склеенное не целиком
 * или не склеенное вовсе, и сервер дописывает их после строк первого листа.
 * В общем списке под пределом у файла с Дрома, где «не в наличии» десятки,
 * они уходили бы за двадцатый пункт, и человек не узнал бы, что объявление
 * перенесено не на сто процентов. Номер строки у них — строка их листа,
 * поэтому лист назван рядом с номером.
 */
/** Сколько ссылок на снимки поставил в очередь повтор файла. */
function refilledLinks(repeat: RepeatReport | undefined): number {
  return (repeat?.fields ?? []).reduce((sum, f) => sum + f.links, 0);
}

/**
 * Что повторная загрузка сделала с уже перенесёнными позициями (задача 0282).
 *
 * <p>Говорит о каждом исходе отдельной строкой, а не одним «готово»: владелец
 * грузит файл второй раз ради новых колонок, и «заведено новых: 0» без
 * «дозаполнено» читалось бы как «ничего не произошло». Расхождения — то, что
 * повтор намеренно оставил как есть, — перечислены, и обрезка списка названа.
 */
function RepeatSummary({ repeat }: { repeat: RepeatReport | undefined }) {
  if (repeat === undefined) {
    return null;
  }
  const shown = repeat.discrepancies.length;
  return (
    <>
      {repeat.refilled > 0 && (
        <p className="note">
          Дозаполнено: {count(repeat.refilled)}{' '}
          {plural(repeat.refilled, 'позиция', 'позиции', 'позиций')}. По полям:{' '}
          {repeat.fields
            .map((f) => `${f.field} — ${count(f.positions)}`
              + (f.links > 0
                ? ` (${count(f.links)} ${plural(f.links, 'ссылка', 'ссылки', 'ссылок')})`
                : ''))
            .join(', ')}
          .
        </p>
      )}
      {repeat.unchanged > 0 && (
        <p className="note">Без изменений: {count(repeat.unchanged)}</p>
      )}
      {repeat.withoutAdNumber > 0 && (
        <p className="note">
          Без номера объявления: {count(repeat.withoutAdNumber)} — заведены как новые.
          Повторная загрузка их не узнает и заведёт ещё раз.
        </p>
      )}
      {repeat.discrepancyCount > 0 && (
        <>
          <p className="note">
            Расхождений: {count(repeat.discrepancyCount)}
            {shown < repeat.discrepancyCount
              && ` — показано ${count(shown)} из ${count(repeat.discrepancyCount)}`}
            . Заполненное у позиции повтор не меняет: правьте в карточке, если файл прав.
          </p>
          <ul className="suggestions">
            {/* Ключ — место в списке: у позиции бывает несколько расхождений. */}
            {repeat.discrepancies.map((d, index) => (
              <li key={index}>{d.text}</li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

function SkippedReport({ skipped }: { skipped: Report['skipped'] }) {
  const { rows, sheets } = splitSkipped(skipped);
  return (
    <>
      {rows.length === 0 ? (
        <p className="note">Все строки файла приняты.</p>
      ) : (
        <>
          <p className="note">
            Пропущено строк: {rows.length}. Номера — как в Excel,
            искать по ним.
          </p>
          <ul className="suggestions">
            {/* Ключ — место в списке: номер строки не обязан быть единственным. */}
            {rows.slice(0, 20).map((skip, index) => (
              <li key={index}>
                строка {skip.row} — {skip.reason}
              </li>
            ))}
          </ul>
        </>
      )}
      {sheets.length > 0 && (
        <>
          <p className="note">
            Лист «Продолжение» склеен не целиком: {sheets.length}. Значения
            объявлений ниже перенесены обрезанными или без части.
          </p>
          <ul className="suggestions">
            {sheets.map((skip, index) => (
              <li key={index}>
                строка {skip.row} листа «{skip.sheet}» — {skip.reason}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}
