import { useState } from 'react';
import { ApiError } from '../api/client';
import {
  duplicateColumns,
  FIELDS,
  importFile,
  missingRequired,
  previewFile,
} from '../import/warehouseImport';
import type { FieldKey, Preview, Report } from '../import/warehouseImport';
import type { Reference } from '../reference/reference';

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
  const [warehouseId, setWarehouseId] = useState<number | null>(
    reference.warehouses[0]?.id ?? null,
  );
  const [report, setReport] = useState<Report | null>(null);
  // Ключ идемпотентности живёт от выбора файла до успеха: повтор после
  // ошибки обязан отдать прежний итог, а не завести второй склад.
  const [requestId, setRequestId] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
          <p className="note">
            Проверьте до запуска. Перепутанные цена и количество дадут склад,
            где всё стоит по три рубля, и заметно это станет на первой продаже.
          </p>

          <ul className="mapping">
            {FIELDS.map((field) => (
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

          <h3>Первые строки файла</h3>
          <div className="table-scroll">
            <table className="preview">
              <thead>
                <tr>
                  {preview.header.map((title, index) => (
                    <th key={index} className={fieldAt(index) !== null ? 'mapped' : undefined}>
                      {title === '' ? `колонка ${index + 1}` : title}
                      {fieldAt(index) !== null && (
                        <div className="muted">→ {fieldAt(index)}</div>
                      )}
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
              onChange={(e) => setWarehouseId(Number(e.target.value))}
            >
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
          <h3>Загружено позиций: {report.imported}</h3>
          {report.skipped.length === 0 ? (
            <p className="note">Все строки файла приняты.</p>
          ) : (
            <>
              <p className="note">
                Пропущено строк: {report.skipped.length}. Номера — как в Excel,
                искать по ним.
              </p>
              <ul className="suggestions">
                {report.skipped.slice(0, 20).map((skip) => (
                  <li key={skip.row}>
                    строка {skip.row} — {skip.reason}
                  </li>
                ))}
              </ul>
            </>
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
      setPreview(parsed);
      // Догадку сервера берём как отправную точку, но показываем целиком:
      // подтверждает её человек, а не молчание.
      setColumns(parsed.detected);
    } catch (cause) {
      setError(describe(cause, 'Файл не разобрался'));
    } finally {
      setBusy(false);
    }
  }

  async function run(): Promise<void> {
    if (file === null || warehouseId === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setReport(await importFile(file, warehouseId, columns, requestId));
    } catch (cause) {
      setError(describe(cause, 'Загрузка не прошла'));
    } finally {
      setBusy(false);
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
