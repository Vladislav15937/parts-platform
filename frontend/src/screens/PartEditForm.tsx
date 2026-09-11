import { Fragment, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  CONDITION, loadEditable, savePart, priceOperationHint, PRICE_OPERATIONS,
  type CatalogRow, type PartEdit, type PriceOperation,
} from '../inventory/catalog';
import { generationOf } from '../inventory/partCard';
import { count, plural } from '../ui/plural';
import { useMounted } from '../ui/useMounted';

/**
 * Правка карточки товара — списком «подпись — значение», а не формой
 * из двадцати полей ввода.
 *
 * <p><b>До этого нажатие «Изменить» превращало карточку в форму целиком:</b>
 * четыре блока, больше двадцати полей, все одинаковые и до правки, и после.
 * А правят карточку точечно — цену подвинуть, комментарий дописать,
 * маркировку исправить, — по многу раз в день. Что из двадцати полей задето,
 * на экране не было написано нигде.
 *
 * <p>Цена у этого была не косметическая: <b>форма уезжает PUT'ом целиком,
 * и пустое поле означает «очистить»</b> (см. {@code blank} ниже). Случайно
 * задетое поле уходило молча вместе со всем, что в нём было написано,
 * а возврата у формы нет — только «Отмена» до сохранения. Теперь пустым
 * поле становится только руками и только у раскрытой строки.
 *
 * <p><b>Изменённым считается раскрытое,</b> а не то, что отличается
 * от прочитанного. Так у ориентира, и так честнее при PUT'е целиком:
 * раскрыв строку, человек взял её под свою ответственность — в том числе
 * когда стёр значение и оставил пусто. Счёт «изменено N параметров» внизу
 * считает ровно раскрытые строки.
 *
 * <p><b>Неправимые поля показаны с замком, а не спрятаны.</b> Спрятанное
 * поле читается как «этого у вас нет»: номер товара, донорские сведения
 * и номера производителя система собирает сама, и правка руками разошлась бы
 * с ними при первом же пересопоставлении наименований.
 *
 * <p><b>Цена двигается операцией, а не только новым числом.</b> Торг
 * на разборке идёт словами «минус десять» и «скинь пятьсот», и считать это
 * в уме — ошибка в разряде ценой в деталь. Умолчание — «Изменить»: самый
 * частый случай остаётся одним движением, арифметика лежит рядом.
 * Считает сервер: тот же расчёт нужен правке списком, и две копии
 * разошлись бы на первом округлении.
 */
export function PartEditForm({ partId, row, onSaved, onCancel }: {
  partId: number;
  /**
   * Строка витрины — за неправимыми полями. Второго запроса за ними
   * не делается: в строке все двадцать с лишним колонок, включая скрытые
   * в таблице, и карточка её уже держит.
   */
  row: CatalogRow;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<Draft | null>(null);
  /**
   * Карточка, какой её прочитали. Нужна, чтобы «Отменить» у строки вернуло
   * прежнее значение этой строки — а не всей формы, как «Отмена» внизу.
   */
  const [loaded, setLoaded] = useState<Draft | null>(null);
  /** Раскрытые строки: они же изменённые, они же уезжают с ответственностью. */
  const [open, setOpen] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [priceOp, setPriceOp] = useState<PriceOperation>('SET');
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    let alive = true;
    void loadEditable(partId)
      .then((card) => {
        if (!alive) return;
        const draft = draftOf(card);
        setForm(draft);
        setLoaded(draft);
      })
      .catch(() => { if (alive) setError('Не удалось прочитать карточку'); });
    return () => { alive = false; };
  }, [partId]);

  function set<K extends keyof Draft>(key: K, value: Draft[K]): void {
    setForm((f) => (f === null ? f : { ...f, [key]: value }));
  }

  function edit(field: Field): void {
    if (field.kind === 'locked' || open.includes(field.key)) return;
    setOpen((keys) => [...keys, field.key]);
  }

  /**
   * Отмена одной строки: значение возвращается к прочитанному, строка
   * снова становится текстом, остальные раскрытые остаются как были.
   */
  function revert(field: Field): void {
    if (field.kind === 'locked') return;
    setOpen((keys) => keys.filter((k) => k !== field.key));
    if (loaded !== null) set(field.key, loaded[field.key]);
    // Цена возвращается вместе с операцией: «Уменьшить на %» при закрытой
    // строке означал бы арифметику, которую никто не заказывал.
    if (field.kind === 'price') setPriceOp('SET');
  }

  /**
   * Смена операции чистит поле, а возврат к «Изменить» возвращает цену.
   *
   * <p>Оставленные в поле 27 000 при выбранном «Уменьшить на %» — это
   * двадцать семь тысяч процентов, то есть отказ на ровном месте; а пустое
   * поле при «Изменить» значит «цену не трогаем», и потерять её нельзя.
   */
  function changeOp(next: PriceOperation): void {
    setPriceOp(next);
    set('price', next === 'SET' ? (loaded?.price ?? '') : '');
  }

  async function save(): Promise<void> {
    if (form === null || loaded === null || open.length === 0) return;
    setError('');
    setSaving(true);
    try {
      // Цену трогаем, только если её строку раскрыли: иначе уезжает
      // прочитанное значение простой заменой, и арифметика к нему
      // не применяется.
      const touched = open.includes('price');
      const body = toEdit(touched ? form : { ...form, price: loaded.price });
      await savePart(partId, body, touched ? priceOp : 'SET');
      if (mounted.current) onSaved();
    } catch (e) {
      if (mounted.current) {
        setError(e instanceof ApiError ? e.message : 'Не удалось сохранить');
      }
    } finally {
      if (mounted.current) setSaving(false);
    }
  }

  if (form === null) {
    return (
      <div className="card-edit">
        {error === '' ? <p className="muted">Читаем карточку…</p>
                      : <p className="note note--error">{error}</p>}
        <button type="button" className="button--ghost" onClick={onCancel}>Отмена</button>
      </div>
    );
  }

  return (
    <div className="card-edit">
      {SECTIONS.map((section) => (
        <Fragment key={section.title}>
          <h4>{section.title}</h4>
          {section.fields.map((field) => {
            const opened = open.includes(field.key);
            return (
              <div
                key={field.key}
                className={opened ? 'card-edit__row card-edit__row--changed'
                                  : 'card-edit__row'}
              >
                <span className="card-edit__label">
                  {field.label}
                  {field.kind === 'locked'
                    && <span title="Это поле система ведёт сама"> 🔒</span>}
                </span>
                <span className="card-edit__value">
                  {opened
                    ? input(field, form, set, priceOp, changeOp)
                    : shownValue(field, form, row)}
                </span>
                {field.kind !== 'locked' && (
                  opened
                    ? (
                      <button type="button" className="button--ghost"
                              aria-label={`Отменить: ${field.label}`}
                              onClick={() => revert(field)}>
                        Отменить
                      </button>
                    )
                    : (
                      <button type="button" className="button--ghost"
                              aria-label={`Изменить: ${field.label}`}
                              onClick={() => edit(field)}>
                        Изменить
                      </button>
                    )
                )}
              </div>
            );
          })}
        </Fragment>
      ))}

      {error !== '' && <p className="note note--error">{error}</p>}

      {/* Счётчик изменённого — внизу, там же, где решают сохранять.
          Погашенная кнопка обязана называть причину: серая «Сохранить»
          без объяснения читается как поломка. */}
      <p className={open.length === 0 ? 'muted' : 'card-edit__counter'}>
        {open.length === 0
          ? 'Менять нечего — нажмите «Изменить» у поля, которое надо поправить'
          : changedNote(open.length)}
      </p>

      <div className="filter-row">
        <button type="button" disabled={saving || open.length === 0}
                onClick={() => void save()}>
          {saving ? 'Сохраняем…' : 'Сохранить'}
        </button>
        <button type="button" className="button--ghost" onClick={onCancel}>
          Отмена
        </button>
      </div>
    </div>
  );
}

/**
 * «Изменен 1 параметр», «Изменено 2 параметра», «Изменено 5 параметров».
 *
 * <p>Склоняется и существительное, и глагол: «Изменено 1 параметр» владелец
 * видел бы каждый раз, когда правит одно поле, — то есть почти всегда.
 * Второй помощник склонения разошёлся бы с первым, поэтому берётся общий
 * (`ui/plural.ts`).
 */
export function changedNote(changed: number): string {
  const verb = plural(changed, 'Изменен', 'Изменено', 'Изменено');
  const noun = plural(changed, 'параметр', 'параметра', 'параметров');
  return `${verb} ${count(changed)} ${noun}`;
}

/** Значение строки текстом — то, что видно, пока строку не раскрыли. */
function shownValue(field: Field, form: Draft, row: CatalogRow): JSX.Element | string {
  const value = field.kind === 'locked' ? field.value(row) : draftText(field, form);
  // «Не заполнено», а не прочерк: прочерк читается как «не знаем»,
  // а тут мы знаем — поле просто пустое, и заполнить его можно отсюда же.
  return value === '' ? <span className="muted">не заполнено</span> : value;
}

function draftText(field: Field, form: Draft): string {
  switch (field.kind) {
    case 'check':
      return form.published ? 'Да' : 'Нет';
    case 'grade':
      return form.qualityGrade === '' ? '' : GRADES[form.qualityGrade] ?? form.qualityGrade;
    case 'price':
    case 'num': {
      const raw = form[field.key];
      if (raw.trim() === '') return '';
      const parsed = Number(raw);
      const shown = Number.isFinite(parsed) ? count(parsed) : raw;
      return field.unit === undefined ? shown : `${shown} ${field.unit}`;
    }
    case 'locked':
      return '';
    default:
      return form[field.key];
  }
}

function input(
  field: Field,
  form: Draft,
  set: <K extends keyof Draft>(key: K, value: Draft[K]) => void,
  priceOp: PriceOperation,
  changeOp: (op: PriceOperation) => void,
): JSX.Element | null {
  switch (field.kind) {
    case 'locked':
      return null;
    case 'price':
      return (
        <span className="row">
          <select aria-label="Операция с ценой" value={priceOp}
                  onChange={(e) => changeOp(e.target.value as PriceOperation)}>
            {PRICE_OPERATIONS.map((op) => (
              <option key={op.key} value={op.key}>{op.title}</option>
            ))}
          </select>
          <input aria-label="Значение операции с ценой" inputMode="decimal" autoFocus
                 value={form.price} placeholder={priceOperationHint(priceOp)}
                 onChange={(e) => set('price', e.target.value.replace(',', '.'))} />
        </span>
      );
    case 'num':
      return (
        <input aria-label={field.label} inputMode="decimal" autoFocus
               value={form[field.key]}
               onChange={(e) => set(field.key, e.target.value.replace(',', '.'))} />
      );
    case 'area':
      return (
        <textarea aria-label={field.label} rows={2} autoFocus value={form[field.key]}
                  onChange={(e) => set(field.key, e.target.value)} />
      );
    case 'grade':
      return (
        <select aria-label={field.label} autoFocus value={form.qualityGrade}
                onChange={(e) => set('qualityGrade', e.target.value)}>
          <option value="">—</option>
          {Object.entries(GRADES).map(([value, title]) => (
            <option key={value} value={value}>{title}</option>
          ))}
        </select>
      );
    case 'check':
      return (
        <input type="checkbox" aria-label={field.label} autoFocus checked={form.published}
               onChange={(e) => set('published', e.target.checked)} />
      );
    default:
      return (
        <input aria-label={field.label} autoFocus value={form[field.key]}
               onChange={(e) => set(field.key, e.target.value)} />
      );
  }
}

/** Ключи черновика, в которых лежит строка: всё, кроме флажка и ячейки. */
type StringKey = { [K in keyof Draft]: Draft[K] extends string ? K : never }[keyof Draft];

type Field =
  | { kind: 'locked'; key: string; label: string; value: (row: CatalogRow) => string }
  | { kind: 'price'; key: 'price'; label: string; unit?: string }
  | { kind: 'grade'; key: 'qualityGrade'; label: string }
  | { kind: 'check'; key: 'published'; label: string }
  | { kind: 'text' | 'area'; key: StringKey; label: string }
  | { kind: 'num'; key: StringKey; label: string; unit?: string };

/**
 * Разделы и порядок строк — от ориентира, из которого приходят клиенты:
 * поле ищут глазами там, где к нему привыкли. Чего у нас нет («Фотографии»
 * правятся в самой карточке, «Архивного товара» нет вовсе), того тут и нет;
 * чего система не даёт править — стоит с замком.
 */
const SECTIONS: Array<{ title: string; fields: Field[] }> = [
  {
    title: 'Общая информация',
    fields: [
      { kind: 'locked', key: 'code', label: 'Номер товара', value: (r) => plainText(r.code) },
      { kind: 'locked', key: 'condition', label: 'Состояние',
        value: (r) => (r.condition === null ? '' : CONDITION[r.condition] ?? r.condition) },
      { kind: 'grade', key: 'qualityGrade', label: 'Оценка состояния' },
      { kind: 'price', key: 'price', label: 'Цена', unit: '₽' },
      { kind: 'num', key: 'minPrice', label: 'Минимальная цена', unit: '₽' },
      { kind: 'num', key: 'costPrice', label: 'Себестоимость', unit: '₽' },
      { kind: 'num', key: 'installationPrice', label: 'Цена установки', unit: '₽' },
      { kind: 'area', key: 'description', label: 'Комментарий' },
      { kind: 'text', key: 'note', label: 'Заметка' },
      { kind: 'text', key: 'barcode', label: 'Ст. баркод' },
    ],
  },
  {
    title: 'Основные параметры',
    fields: [
      { kind: 'locked', key: 'partName', label: 'Наименование',
        value: (r) => plainText(r.partName) },
      { kind: 'locked', key: 'sideFr', label: 'Передний / Задний',
        value: (r) => (r.sideFr === null ? '' : SIDE_FR[r.sideFr] ?? r.sideFr) },
      { kind: 'locked', key: 'sideLr', label: 'Левый / Правый',
        value: (r) => (r.sideLr === null ? '' : SIDE_LR[r.sideLr] ?? r.sideLr) },
      { kind: 'text', key: 'manufacturer', label: 'Производитель' },
      { kind: 'locked', key: 'oem', label: 'Номер производителя',
        value: (r) => plainText(r.oem) },
      { kind: 'locked', key: 'crosses', label: 'Кросс-номера',
        value: (r) => plainText(r.crosses) },
      { kind: 'text', key: 'marking', label: 'Маркировка' },
      { kind: 'text', key: 'color', label: 'Цвет' },
      { kind: 'text', key: 'section', label: 'Секция' },
      { kind: 'num', key: 'weightKg', label: 'Вес, кг' },
      { kind: 'num', key: 'lengthMm', label: 'Длина, мм' },
      { kind: 'num', key: 'widthMm', label: 'Ширина, мм' },
      { kind: 'num', key: 'heightMm', label: 'Высота, мм' },
      { kind: 'num', key: 'packageWeightKg', label: 'Вес в упаковке, кг' },
      { kind: 'num', key: 'packageLengthMm', label: 'Длина упаковки, мм' },
      { kind: 'num', key: 'packageWidthMm', label: 'Ширина упаковки, мм' },
      { kind: 'num', key: 'packageHeightMm', label: 'Высота упаковки, мм' },
    ],
  },
  {
    // Донора правят на машине, а не на детали: сведения приезжают с неё
    // и на каждой её детали одинаковы. Поэтому весь раздел — с замком.
    title: 'Автомобиль-донор',
    fields: [
      { kind: 'locked', key: 'donorCode', label: 'Номер донора',
        value: (r) => plainText(r.donorCode) },
      { kind: 'locked', key: 'brand', label: 'Марка', value: (r) => plainText(r.brand) },
      { kind: 'locked', key: 'model', label: 'Модель', value: (r) => plainText(r.model) },
      { kind: 'locked', key: 'body', label: 'Модель кузова', value: (r) => plainText(r.body) },
      { kind: 'locked', key: 'engine', label: 'Модель двигателя',
        value: (r) => plainText(r.engine) },
      { kind: 'locked', key: 'year', label: 'Год выпуска', value: (r) => plainText(r.year) },
      { kind: 'locked', key: 'equipment', label: 'Комплектация',
        value: (r) => plainText(r.equipment) },
      { kind: 'locked', key: 'generation', label: 'Поколение',
        value: (r) => plainText(generationOf(r)) },
    ],
  },
  {
    title: 'Размещение на площадках',
    fields: [
      { kind: 'area', key: 'textBlock', label: 'Текстовый блок' },
      { kind: 'text', key: 'videoUrl', label: 'Видео' },
      { kind: 'check', key: 'published', label: 'Выгружать' },
    ],
  },
  {
    title: 'Другие настройки',
    fields: [
      { kind: 'locked', key: 'legacyCode', label: 'Старые данные',
        value: (r) => plainText(r.legacyCode) },
    ],
  },
];

const SIDE_LR: Record<string, string> = { LEFT: 'лев.', RIGHT: 'прав.' };
const SIDE_FR: Record<string, string> = { FRONT: 'перед.', REAR: 'задн.' };

function plainText(value: string | number | null): string {
  return value === null || value === undefined ? '' : String(value);
}

const GRADES: Record<string, string> = {
  EXCELLENT: 'отличное',
  GOOD: 'хорошее',
  FAIR: 'удовлетворительное',
  POOR: 'плохое',
};

/**
 * Черновик формы — строки, а не числа.
 *
 * <p>Поле ввода отдаёт строку, и держать в состоянии число значит терять
 * набранное на каждом промежуточном «3,» или «1». Приведение к числу — один
 * раз, при отправке.
 */
export interface Draft {
  price: string;
  minPrice: string;
  costPrice: string;
  installationPrice: string;
  qualityGrade: string;
  description: string;
  note: string;
  textBlock: string;
  videoUrl: string;
  marking: string;
  manufacturer: string;
  color: string;
  section: string;
  barcode: string;
  weightKg: string;
  lengthMm: string;
  widthMm: string;
  heightMm: string;
  packageLengthMm: string;
  packageWidthMm: string;
  packageHeightMm: string;
  packageWeightKg: string;
  storageCellId: number | null;
  published: boolean;
}

export function draftOf(card: PartEdit): Draft {
  return {
    price: text(card.price),
    minPrice: text(card.minPrice),
    costPrice: text(card.costPrice),
    installationPrice: text(card.installationPrice),
    qualityGrade: card.qualityGrade ?? '',
    description: card.description ?? '',
    note: card.note ?? '',
    textBlock: card.textBlock ?? '',
    videoUrl: card.videoUrl ?? '',
    marking: card.marking ?? '',
    manufacturer: card.manufacturer ?? '',
    color: card.color ?? '',
    section: card.section ?? '',
    barcode: card.barcode ?? '',
    weightKg: text(card.weightKg),
    lengthMm: text(card.lengthMm),
    widthMm: text(card.widthMm),
    heightMm: text(card.heightMm),
    packageLengthMm: text(card.packageLengthMm),
    packageWidthMm: text(card.packageWidthMm),
    packageHeightMm: text(card.packageHeightMm),
    packageWeightKg: text(card.packageWeightKg),
    // Ячейку правят перемещением, а не формой: в форме её нет, но и терять
    // её сохранение не должно.
    storageCellId: card.storageCellId,
    published: card.published,
  };
}

export function toEdit(form: Draft): PartEdit {
  return {
    price: number(form.price),
    minPrice: number(form.minPrice),
    costPrice: number(form.costPrice),
    installationPrice: number(form.installationPrice),
    qualityGrade: blank(form.qualityGrade),
    description: blank(form.description),
    note: blank(form.note),
    textBlock: blank(form.textBlock),
    videoUrl: blank(form.videoUrl),
    marking: blank(form.marking),
    manufacturer: blank(form.manufacturer),
    color: blank(form.color),
    section: blank(form.section),
    barcode: blank(form.barcode),
    weightKg: number(form.weightKg),
    lengthMm: number(form.lengthMm),
    widthMm: number(form.widthMm),
    heightMm: number(form.heightMm),
    packageLengthMm: number(form.packageLengthMm),
    packageWidthMm: number(form.packageWidthMm),
    packageHeightMm: number(form.packageHeightMm),
    packageWeightKg: number(form.packageWeightKg),
    storageCellId: form.storageCellId,
    published: form.published,
  };
}

function text(value: number | null): string {
  return value === null || value === undefined ? '' : String(value);
}

function blank(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

/**
 * Пустое поле — это null, а не ноль: «цена установки 0 ₽» в карточке
 * означает бесплатную установку, то есть обещание, которого никто не давал.
 */
function number(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed === '') return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}
