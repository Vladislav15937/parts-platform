import { useState } from 'react';
import { ApiError } from '../api/client';
import {
  BULK_FIELDS, PRICE_OPERATIONS, priceOperationHint, savePartsBulk, savePartsBulkByFilter,
  type BulkResult, type CatalogQuery, type PriceOperation,
} from '../inventory/catalog';
import { count as formatCount, positions } from '../ui/plural';

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
  /**
   * Итог целиком, а не одно число: кроме изменённых, экран обязан сказать
   * про пропущенные (поле пустое) и про непрошедшие (операция дала бы минус
   * или ноль) — без них «изменено 40» читается как «сделано всем».
   */
  onSaved: (result: BulkResult) => void;
  onCancel: () => void;
}) {
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [values, setValues] = useState<Record<string, string>>({});
  /**
   * Что сделать с денежным полем: заменить (умолчание), процент, сумма,
   * округление. Считается от прежнего значения каждой позиции, а не сводит
   * отбор к общему числу: «минус десять процентов» у трёх позиций — три
   * разные цены.
   */
  const [ops, setOps] = useState<Record<string, PriceOperation>>({});
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

  /**
   * Отмеченное поле с арифметикой, но без значения.
   *
   * <p>Кнопка, которая ничего не сделает, обязана быть погашена и назвать
   * причину: без этого владелец выбирает «Уменьшить на %», не вводит числа,
   * жмёт «Изменить 3 позиции» и получает «изменено 3» при неизменившихся
   * ценах — то есть экран сообщает о работе, которой не было.
   */
  const emptyOperand = chosen.find((f) => f.kind === 'money'
    && (ops[f.key] ?? 'SET') !== 'SET' && (values[f.key] ?? '').trim() === '');

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
      // Операции уезжают только у денег и только не «Изменить»:
      // непереданное означает прежнее поведение — замену значения.
      const operations: Record<string, PriceOperation> = {};
      for (const field of chosen) {
        const op = ops[field.key] ?? 'SET';
        if (field.kind === 'money' && op !== 'SET') {
          operations[field.key] = op;
        }
      }
      const result = whole === undefined
        ? await savePartsBulk(partIds, changes, operations)
        : await savePartsBulkByFilter(whole, changes, operations);
      onSaved(result);
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
            ? ` · выбрано ${formatCount(count)}`
            : ` · весь отбор: ${formatCount(count)}`}
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
            {touched[field.key] === true && field.kind === 'money' && (
              <select
                aria-label={`Операция: ${field.title}`}
                value={ops[field.key] ?? 'SET'}
                onChange={(e) => setOps({
                  ...ops, [field.key]: e.target.value as PriceOperation,
                })}
              >
                {PRICE_OPERATIONS.map((op) => (
                  <option key={op.key} value={op.key}>{op.title}</option>
                ))}
              </select>
            )}
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
                  placeholder={field.kind !== 'money' ? 'оставить пустым — очистить'
                    : (ops[field.key] ?? 'SET') === 'SET' ? '0'
                    : priceOperationHint(ops[field.key] ?? 'SET')}
                  onChange={(e) => setValues({ ...values, [field.key]: e.target.value })}
                />
              )
            )}
          </div>
        ))}
      </div>

      {emptyOperand !== undefined && (
        <p className="note">
          {`«${emptyOperand.title}»: не задано значение операции — вводить нечего.`}
        </p>
      )}

      {error !== '' && <p className="note note--error">{error}</p>}

      <div className="filter-row">
        <button
          type="button"
          disabled={saving || chosen.length === 0 || emptyOperand !== undefined}
          onClick={() => {
            if (whole !== undefined && !confirming) {
              setConfirming(true);
              return;
            }
            void save();
          }}
        >
          {saving ? 'Сохраняем…'
            : confirming ? `Точно изменить ${positions(count)}?`
            : `Изменить ${positions(count)}`}
        </button>
        <button type="button" className="button--ghost" onClick={onCancel}>
          Отмена
        </button>
      </div>
    </div>
  );
}
