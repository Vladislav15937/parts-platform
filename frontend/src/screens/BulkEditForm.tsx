import { useState } from 'react';
import { ApiError } from '../api/client';
import {
  BULK_FIELDS, savePartsBulk, savePartsBulkByFilter, type CatalogQuery,
} from '../inventory/catalog';

/**
 * Правка нескольких позиций разом.
 *
 * <p><b>Меняется только отмеченное.</b> У каждого поля свой флажок, и без него
 * поле не уезжает вовсе. Это не украшение: у выбранных позиций заметки разные,
 * и форма, отправляющая всё подряд, стёрла бы их одним нажатием — а отменить
 * это нечем, кроме восстановления из бэкапа.
 *
 * <p><b>Зачем она нужна.</b> После переезда со старой системы владельцу надо
 * проставить секцию сотне позиций или снять «Выгружать» у битых. По одной
 * это день работы, и потому её не делают вовсе — склад так и остаётся
 * без адресов.
 */
export function BulkEditForm({ partIds, whole, count, onSaved, onCancel }: {
  partIds: number[];
  /**
   * Отбор целиком вместо отмеченных строк: правится всё, что нашёл экран,
   * а не пятьдесят видимых. Ради этого режима форма и знает про отбор —
   * без него включить «Выгружать» всему переехавшему складу нельзя.
   */
  whole?: CatalogQuery | undefined;
  /** Сколько позиций тронем. У отбора это общее число, а не длина списка. */
  count: number;
  onSaved: (changed: number) => void;
  onCancel: () => void;
}) {
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  // Правка всего отбора вторым нажатием: отменить её нечем, кроме
  // восстановления из бэкапа, а промах мышью стоит всего склада.
  // Отмеченные руками строки этого не требуют — их владелец только что
  // выбрал сам.
  const [confirming, setConfirming] = useState(false);

  function toggle(key: string): void {
    setTouched({ ...touched, [key]: touched[key] !== true });
  }

  const chosen = BULK_FIELDS.filter((f) => touched[f.key] === true);

  async function save(): Promise<void> {
    setError('');
    setSaving(true);
    try {
      const changes: Record<string, string | number | boolean | null> = {};
      for (const field of chosen) {
        // Умолчание читается тем же, каким показано. Разойдясь, они дают
        // самую тихую поломку из возможных: список стоит на «Везде»,
        // владелец его не трогает — и уезжает «Нет», то есть отмеченные
        // позиции снимаются с выгрузки вместо постановки на неё. На экране
        // при этом написано обратное, и заметить это можно только по
        // опустевшему прайсу через несколько дней.
        const raw = values[field.key] ?? (field.kind === 'flag' ? 'yes' : '');
        if (field.kind === 'flag') {
          changes[field.key] = raw === 'yes';
        } else if (field.kind === 'money') {
          // Пустое отмеченное поле — «очистить», и это осознанный выбор:
          // владелец отметил его сам.
          changes[field.key] = raw.trim() === '' ? null : Number(raw.replace(',', '.'));
        } else {
          changes[field.key] = raw.trim() === '' ? null : raw.trim();
        }
      }
      const result = whole === undefined
        ? await savePartsBulk(partIds, changes)
        : await savePartsBulkByFilter(whole, changes);
      onSaved(result.changed);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Не удалось сохранить');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card-edit">
      <h4>
        Правка списком
        <span className="muted">
          {whole === undefined
            ? ` · выбрано ${count}`
            : ` · весь отбор: ${count}`}
        </span>
      </h4>
      <p className="note">
        Изменится только отмеченное. Остальные поля у выбранных позиций
        останутся как есть.
      </p>

      <div className="card-edit__grid">
        {BULK_FIELDS.map((field) => (
          <div key={field.key} className="bulk-field">
            <label className="field field--check">
              <input
                type="checkbox"
                checked={touched[field.key] === true}
                onChange={() => toggle(field.key)}
              />
              {field.title}
            </label>
            {touched[field.key] === true && (
              field.kind === 'flag' ? (
                <select
                  value={values[field.key] ?? 'yes'}
                  onChange={(e) => setValues({ ...values, [field.key]: e.target.value })}
                >
                  <option value="yes">Везде</option>
                  <option value="no">Нет</option>
                </select>
              ) : (
                <input
                  inputMode={field.kind === 'money' ? 'decimal' : undefined}
                  value={values[field.key] ?? ''}
                  placeholder={field.kind === 'money' ? '0' : 'оставить пустым — очистить'}
                  onChange={(e) => setValues({ ...values, [field.key]: e.target.value })}
                />
              )
            )}
          </div>
        ))}
      </div>

      {error !== '' && <p className="note note--error">{error}</p>}

      <div className="filter-row">
        <button
          type="button"
          disabled={saving || chosen.length === 0}
          onClick={() => {
            if (whole !== undefined && !confirming) {
              setConfirming(true);
              return;
            }
            void save();
          }}
        >
          {saving ? 'Сохраняем…'
            : confirming ? `Точно изменить ${count}?`
            : `Изменить ${count} позиций`}
        </button>
        <button type="button" className="button--ghost" onClick={onCancel}>
          Отмена
        </button>
      </div>
    </div>
  );
}
