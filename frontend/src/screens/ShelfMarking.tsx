import { useState } from 'react';
import { LabelSheet } from '../labels/LabelSheet';
import { LABEL_SIZE_NOTE, shelfLabel } from '../labels/labels';
import {
  ADDRESS_SCHEME,
  markingAddresses,
  markingProblem,
  type MarkingForm,
  warehousePart,
} from '../inventory/storageAddress';
import { count, plural } from '../ui/plural';

/**
 * «Маркировка полок склада» (задача 0368).
 *
 * <p>Склад задаётся не списком ячеек, а схемой: зона, ряд, диапазон секций
 * и диапазон полок в каждой — и на стеллаж уходит лист этикеток с адресами
 * `01-01-03-01-02`. Поля, подписи и умолчания («1», «с 1 по 1») — с ориентира
 * дословно: клиенты приходят оттуда и узнают окно по ним. Печатается один ряд
 * за раз, как у ориентира: зона и ряд там одним числом.
 *
 * <p><b>Адреса заранее не заводятся</b> — решение владельца 19.10.2026
 * «Всё как у bazon» (раздел «Ответ владельца» задачи 0368): печать ничего
 * не пишет на сервер, адрес рождается этикеткой и привязкой позиции.
 *
 * <p><b>Принтера в окне нет, и это не забыто.</b> Печатает браузер
 * (`labels/labels.ts`): принтер выбирают в диалоге печати, а перечислить
 * принтеры системы странице нельзя. ZPL и локальный агент отложены задачей.
 */
export function ShelfMarking({ warehouseId, warehouseName, onClose }: {
  warehouseId: number;
  warehouseName: string;
  onClose: () => void;
}) {
  const [form, setForm] = useState<MarkingForm>({
    zone: '1', row: '1', sectionFrom: '1', sectionTo: '1', shelfFrom: '1', shelfTo: '1',
  });
  // Номер склада в адресе — две цифры; склад с номером больше 99 в схему
  // не ложится, и печатать ему этикетки, которые привязка потом отобьёт, нельзя.
  const problem = warehouseId > 99
    ? 'Номер склада в адресе — две цифры, а у этого склада номер больше 99: '
      + 'адрес по схеме ему не напечатать.'
    : markingProblem(form);
  const addresses = problem === null ? markingAddresses(warehouseId, form) : [];
  const labels = addresses.map((address) => shelfLabel(address, warehouseName));

  const field = (key: keyof MarkingForm) => ({
    value: form[key],
    inputMode: 'numeric' as const,
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value }),
  });

  return (
    <>
      <div className="card no-print">
        <h3>Маркировка полок склада</h3>
        <p className="note">
          Адрес на этикетке — {ADDRESS_SCHEME}: склад 01, зона 1, ряд 3, секция 1,
          полка 2 — это 01-01-03-01-02. У склада «{warehouseName}» в адресе
          номер {warehousePart(warehouseId)}. Кладовщик ставит деталь на полку сканом
          этой этикетки в карточке позиции.
        </p>

        <label className="field">
          Склад
          <input value={warehouseName} readOnly />
        </label>
        <div className="row">
          <label className="field">
            Зона на складе
            <input {...field('zone')} />
          </label>
          <label className="field">
            Ряд на складе
            <input {...field('row')} />
          </label>
        </div>
        <fieldset>
          <legend>Маркируемые секции</legend>
          <div className="row">
            <label className="field">
              с
              <input aria-label="Маркируемые секции: с" {...field('sectionFrom')} />
            </label>
            <label className="field">
              по
              <input aria-label="Маркируемые секции: по" {...field('sectionTo')} />
            </label>
          </div>
        </fieldset>
        <fieldset>
          <legend>Маркировка для полок в каждой секции</legend>
          <div className="row">
            <label className="field">
              с
              <input aria-label="Полки в каждой секции: с" {...field('shelfFrom')} />
            </label>
            <label className="field">
              по
              <input aria-label="Полки в каждой секции: по" {...field('shelfTo')} />
            </label>
          </div>
        </fieldset>

        {problem !== null && <p className="note note--error">{problem}</p>}
        <p className="note">
          К печати: {count(labels.length)} {plural(labels.length, 'этикетка', 'этикетки', 'этикеток')}.
          {' '}Принтер выбирается в диалоге печати. {LABEL_SIZE_NOTE}
        </p>

        <div className="row">
          <button type="button" disabled={labels.length === 0} onClick={() => window.print()}>
            Напечатать
          </button>
          <button type="button" className="button--ghost" onClick={onClose}>
            Закрыть
          </button>
        </div>
      </div>

      {/* Предпросмотр он же то, что уйдёт в печать — как на экране «Этикетки». */}
      {labels.length > 0 && <LabelSheet labels={labels} />}
    </>
  );
}
