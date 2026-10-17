import { Fragment, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  CONDITION, CONDITIONS, QUALITY, QUALITY_GRADES, loadEditable, savePart,
  priceOperationHint, PRICE_OPERATIONS,
  type CatalogRow, type PartEdit, type PriceOperation,
} from '../inventory/catalog';
import { generationOf, shownTitle } from '../inventory/partCard';
import { LongNumberNote } from '../inventory/LongNumberNote';
import { notSavedNote, notSavedSummary, type NotSavedField } from '../inventory/notSaved';
import { count, plural } from '../ui/plural';
import { useMounted } from '../ui/useMounted';
import { ApplicabilityView } from './ApplicabilityView';
import { CharacteristicsView } from './CharacteristicsView';
import { VehicleNameInput, useVehicleCatalog } from './VehicleNameInput';

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
 * <p>Цена у этого была не косметическая: пустое поле означает «очистить»
 * (см. {@code blank} ниже). Случайно задетое поле уходило молча вместе
 * со всем, что в нём было написано, а возврата у формы нет — только «Отмена»
 * до сохранения. Теперь пустым поле становится только руками и только
 * у раскрытой строки.
 *
 * <p><b>Счёт «изменено N параметров» считает раскрытые строки,</b> как
 * у ориентира: раскрыв строку, человек взял её под свою ответственность.
 * А <b>уезжает только то, что отличается от увиденного</b> (задача 0307,
 * решение владельца продукта 16.10.2026: «1-б») — вместе с увиденным,
 * чтобы сервер узнал чужую правку того же поля. До неё форма уезжала
 * PUT'ом целиком, и вторая вкладка, сохраняя заметку, откатывала цену,
 * которую успела поменять первая. Раскрытая и нетронутая строка не уезжает:
 * её значение и есть то, что в базе.
 *
 * <p><b>Замков нет ни у одного поля</b> (задача 0302, решение владельца
 * продукта 15.10.2026: «Клиент может править у себя абсолютно всё… Никаких
 * замков и запретов»). До неё номер товара, наименование, стороны, номера
 * производителя, машина и идентификаторы стояли с замком и подсказкой «Это
 * поле система ведёт сама» — неверной: номер производителя система не ведёт
 * вовсе, и клиент, заведя позицию, не мог вписать его ничем.
 *
 * <p><b>Поля, которых не знала прежняя форма, уезжают, только если их строку
 * раскрыли</b>; нераскрытые не отправляются вовсе, и сервер их не трогает.
 * Иначе каждое сохранение цены переписывало бы и номер производителя, и машину
 * позиции тем, что форма прочитала, — а машина, не вписанная у позиции руками,
 * показывается донорская, и записалась бы своей.
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
  /**
   * Карточка, как её отдал сервер, — без приведения к строкам. Её значения
   * уезжают «увиденными» (`seen`): сервер сравнивает их с базой в том же
   * виде, в каком отдал (задача 0307).
   */
  const [card, setCard] = useState<PartEdit | null>(null);
  /** Раскрытые строки: они же изменённые, они же уезжают с ответственностью. */
  const [open, setOpen] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [priceOp, setPriceOp] = useState<PriceOperation>('SET');
  /**
   * Что из последнего сохранения не легло: то же поле успел изменить другой
   * (задача 0313). Остальное сохранено, и форма об этом говорит, а не
   * закрывается молча.
   */
  const [notSaved, setNotSaved] = useState<NotSavedField[]>([]);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();
  // Подсказки марки и модели (задача 0308): справочник — по первому раскрытию.
  const vehicles = useVehicleCatalog(open.includes('brand') || open.includes('model'));

  useEffect(() => {
    let alive = true;
    void loadEditable(partId)
      .then((card) => {
        if (!alive) return;
        const draft = draftOf(card);
        setForm(draft);
        setLoaded(draft);
        setCard(card);
      })
      .catch(() => { if (alive) setError('Не удалось прочитать карточку'); });
    return () => { alive = false; };
  }, [partId]);

  function set<K extends keyof Draft>(key: K, value: Draft[K]): void {
    setForm((f) => (f === null ? f : { ...f, [key]: value }));
  }

  function edit(field: Field): void {
    if (open.includes(field.key)) return;
    // Своей машины у позиции нет — в поле подставляется то, что видно
    // (донор или применимость): править начинают от него, а не с пустого.
    if (field.kind === 'vehicle' && form !== null && form[field.key].trim() === '') {
      set(field.key, field.fallback(row));
    }
    setOpen((keys) => [...keys, field.key]);
  }

  /**
   * Отмена одной строки: значение возвращается к прочитанному, строка
   * снова становится текстом, остальные раскрытые остаются как были.
   */
  function revert(field: Field): void {
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
    if (form === null || loaded === null || card === null || open.length === 0) return;
    if (open.includes('number') && !/^\s*[1-9][0-9]*\s*$/.test(form.number)) {
      setError('№ позиции — целое число больше нуля');
      return;
    }
    setError('');
    setSaving(true);
    try {
      // Цену трогаем, только если её строку раскрыли: иначе уезжает
      // прочитанное значение простой заменой, и арифметика к нему
      // не применяется.
      const touched = open.includes('price');
      const op = touched ? priceOp : 'SET';
      const { edit, seen } = changedEdit(
        toEdit(touched ? form : { ...form, price: loaded.price }, open),
        toEdit(shownDraft(loaded, row), open), card, op);
      // Раскрыли и ничего не поменяли — писать нечего: в базе то, что видно.
      const saved = Object.keys(edit).length > 0
        ? await savePart(partId, edit, op, seen) : null;
      if (!mounted.current) return;
      const lost = saved?.notSaved ?? [];
      if (lost.length === 0) {
        onSaved();
        return;
      }
      keepNotSaved(lost, saved?.form ?? null);
    } catch (e) {
      if (mounted.current) {
        setError(e instanceof ApiError ? e.message : 'Не удалось сохранить');
      }
    } finally {
      if (mounted.current) setSaving(false);
    }
  }

  /**
   * Сохранено не всё (задача 0313): сохранённое перестаёт быть изменённым,
   * несохранённое остаётся раскрытым с набранным.
   *
   * <p>Снимок формы (`card`, `loaded`) берётся из ответа сервера — тем, что
   * легло, — и следующее «Сохранить» не шлёт сохранённое заново. У несохранённых
   * полей снимок остаётся прежним, а не чужим: подставь туда значение другого
   * человека увиденным — и повторное «Сохранить» молча затёрло бы его правку,
   * которой этот человек так и не видел. Поэтому повтор без перезагрузки снова
   * получит отказ, и ровно об этом просит строка у поля.
   */
  function keepNotSaved(lost: NotSavedField[], after: PartEdit | null): void {
    if (form === null || card === null) return;
    const keys = new Set(lost.map((f) => f.key));
    const nextCard = { ...(after ?? card) } as unknown as Record<string, unknown>;
    const wasCard = card as unknown as Record<string, unknown>;
    for (const key of keys) nextCard[key] = wasCard[key];
    const nextLoaded = draftOf(nextCard as unknown as PartEdit);
    const nextForm = { ...nextLoaded } as unknown as Record<string, unknown>;
    const typed = form as unknown as Record<string, unknown>;
    for (const key of keys) {
      if (key in nextForm) nextForm[key] = typed[key];
    }
    setCard(nextCard as unknown as PartEdit);
    setLoaded(nextLoaded);
    setForm(nextForm as unknown as Draft);
    setOpen((was) => was.filter((k) => keys.has(k)));
    if (!keys.has('price')) setPriceOp('SET');
    setNotSaved(lost);
  }

  /**
   * После частичного сохранения «Отмена» уже ничего не отменяет: часть легла.
   * Закрытие перечитывает карточку (`onSaved` — «закрыть и перечитать»), иначе
   * за формой осталась бы прежняя цена или заметка.
   */
  const close = notSaved.length > 0 ? onSaved : onCancel;

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
      {notSaved.length > 0 && (
        <p className="note note--error" role="status">{notSavedSummary(notSaved)}</p>
      )}
      {SECTIONS.map((section) => (
        <Fragment key={section.title}>
          <h4>{section.title}</h4>
          {section.fields.map((field) => {
            const opened = open.includes(field.key);
            const lost = notSavedOf(notSaved, field.key);
            return (
              <div
                key={field.key}
                className={opened ? 'card-edit__row card-edit__row--changed'
                                  : 'card-edit__row'}
              >
                <span className="card-edit__label">{field.label}</span>
                <span className="card-edit__value">
                  {opened && (field.key === 'brand' || field.key === 'model')
                    ? (
                      <VehicleNameInput kind={field.key} label={field.label}
                                        value={form[field.key]} catalog={vehicles}
                                        brand={form.brand.trim() !== '' ? form.brand : plainText(row.brand)}
                                        onChange={(v) => set(field.key as 'brand' | 'model', v)} />
                    )
                    : opened
                    ? input(field, form, set, priceOp, changeOp)
                    : shownValue(field, form, row)}
                  {opened && 'note' in field && field.note !== undefined
                    && <span className="muted"> {field.note}</span>}
                  {/* И у раскрытой строки — пока набирают, — и у закрытой:
                      длинное значение, сохранённое раньше, видно при открытии. */}
                  {(field.key === 'oem' || field.key === 'crosses')
                    && <LongNumberNote field={field.key} value={form[field.key]} />}
                  {opened && lost !== null
                    && <span className="card-edit__not-saved">{notSavedNote(lost)}</span>}
                </span>
                {opened
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
        <button type="button" className="button--ghost" onClick={close}>
          {notSaved.length > 0 ? 'Закрыть' : 'Отмена'}
        </button>
      </div>
    </div>
  );
}

function notSavedOf(list: readonly NotSavedField[], key: string): NotSavedField | null {
  return list.find((f) => f.key === key) ?? null;
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

/**
 * «2026-09-28» → «28.09.2026»; всё, что не похоже на дату, показывается как есть:
 * хранится текст клиента, и подправлять его на показе нельзя.
 */
export function dromDateShown(text: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text.trim());
  return m ? `${m[3]}.${m[2]}.${m[1]}` : text;
}

/** Значение строки текстом — то, что видно, пока строку не раскрыли. */
function shownValue(field: Field, form: Draft, row: CatalogRow): JSX.Element | string {
  const value = draftText(field, form, row);
  if (field.kind === 'fits' && value.trim() !== '') {
    return <ApplicabilityView text={value} />;
  }
  if (field.kind === 'specs' && value.trim() !== '') {
    return <CharacteristicsView text={value} />;
  }
  // «Не заполнено», а не прочерк: прочерк читается как «не знаем»,
  // а тут мы знаем — поле просто пустое, и заполнить его можно отсюда же.
  return value === '' ? <span className="muted">не заполнено</span> : value;
}

function draftText(field: Field, form: Draft, row: CatalogRow): string {
  switch (field.kind) {
    case 'vehicle':
      // Своё пусто — видно то, что показывает витрина: донор или применимость.
      return form[field.key].trim() !== '' ? form[field.key] : field.fallback(row);
    case 'side':
      return form[field.key] === '' ? '' : field.options[form[field.key]] ?? form[field.key];
    case 'check':
      return form.published ? 'Да' : 'Нет';
    case 'condition':
      // Словом, а не кодом: `USED` на экране — утечка внутреннего
      // представления, и словарь для этого один на фронтенд.
      return form.condition === '' ? '' : CONDITION[form.condition] ?? form.condition;
    case 'grade':
      return form.qualityGrade === '' ? '' : QUALITY[form.qualityGrade] ?? form.qualityGrade;
    case 'price':
    case 'num': {
      const raw = form[field.key];
      if (raw.trim() === '') return '';
      const parsed = Number(raw);
      const shown = Number.isFinite(parsed) ? count(parsed) : raw;
      return field.unit === undefined ? shown : `${shown} ${field.unit}`;
    }
    case 'date':
      return dromDateShown(form.adDate);
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
    case 'side':
      return (
        <select aria-label={field.label} autoFocus value={form[field.key]}
                onChange={(e) => set(field.key, e.target.value)}>
          <option value="">—</option>
          {Object.entries(field.options).map(([key, title]) => (
            <option key={key} value={key}>{title}</option>
          ))}
        </select>
      );
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
    case 'fits':
    case 'specs':
      return (
        <textarea aria-label={field.label} rows={10} autoFocus value={form[field.key]}
                  onChange={(e) => set(field.key, e.target.value)} />
      );
    case 'area':
      return (
        <textarea aria-label={field.label} rows={BLOCK_KEYS.includes(field.key) ? 6 : 2}
                  autoFocus value={form[field.key]}
                  onChange={(e) => set(field.key, e.target.value)} />
      );
    case 'condition':
      // Пустого пункта тут нет, в отличие от оценки: у детали всегда
      // какое-то состояние, и «—» означало бы «очистить» — то, чего
      // сервер не делает (колонка NOT NULL с умолчанием).
      return (
        <select aria-label={field.label} autoFocus value={form.condition}
                onChange={(e) => set('condition', e.target.value)}>
          {CONDITIONS.map((condition) => (
            <option key={condition.key} value={condition.key}>{condition.title}</option>
          ))}
        </select>
      );
    case 'grade':
      return (
        <select aria-label={field.label} autoFocus value={form.qualityGrade}
                onChange={(e) => set('qualityGrade', e.target.value)}>
          <option value="">—</option>
          {QUALITY_GRADES.map((grade) => (
            <option key={grade.key} value={grade.key}>{grade.title}</option>
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

/** Блоки карточки Дрома: длинные тексты в несколько строк, и поле под них выше обычного. */
const BLOCK_KEYS: string[] = ['warranty', 'pickup', 'deliveryPayment'];

/** Ключи черновика, в которых лежит строка: всё, кроме флажка и ячейки. */
type StringKey = { [K in keyof Draft]: Draft[K] extends string ? K : never }[keyof Draft];

/** Машина позиции: своё значение, а пусто — донор или применимость из строки витрины. */
type VehicleKey = 'donorCode' | 'brand' | 'model' | 'generation' | 'body' | 'engine' | 'year'
  | 'equipment';

type Field =
  | { kind: 'vehicle'; key: VehicleKey; label: string; fallback: (row: CatalogRow) => string }
  | { kind: 'side'; key: 'sideLr' | 'sideFr'; label: string; options: Record<string, string> }
  | { kind: 'price'; key: 'price'; label: string; unit?: string }
  | { kind: 'condition'; key: 'condition'; label: string }
  | { kind: 'grade'; key: 'qualityGrade'; label: string }
  | { kind: 'check'; key: 'published'; label: string }
  | { kind: 'text' | 'area' | 'fits' | 'specs' | 'date'; key: StringKey; label: string;
      /** Последствие правки — словами у раскрытого поля, а не замком. */
      note?: string }
  | { kind: 'num'; key: StringKey; label: string; unit?: string };

const SIDE_LR: Record<string, string> = { LEFT: 'лев.', RIGHT: 'прав.', BOTH: 'лев., прав.' };
const SIDE_FR: Record<string, string> = { FRONT: 'перед.', REAR: 'задн.', BOTH: 'перед., задн.' };

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
      // Номер, которым деталь называют в разговоре. Правится (задача 0302);
      // новые позиции система нумерует дальше вписанного.
      { kind: 'text', key: 'number', label: '№ позиции',
        note: 'Номер называют вслух — поменяв его, скажите об этом тем, кто знал прежний' },
      { kind: 'text', key: 'code', label: 'Номер товара',
        note: 'Он напечатан на этикетке и уходит в прайс Дрома: после смены перепечатайте'
          + ' этикетку, а объявление на площадке может завестись заново' },
      // Состояние стоит второй строкой, как у ориентира: сразу под номером товара.
      { kind: 'condition', key: 'condition', label: 'Состояние' },
      { kind: 'grade', key: 'qualityGrade', label: 'Оценка состояния' },
      { kind: 'price', key: 'price', label: 'Цена', unit: '₽' },
      { kind: 'num', key: 'minPrice', label: 'Минимальная цена', unit: '₽' },
      { kind: 'num', key: 'costPrice', label: 'Себестоимость', unit: '₽' },
      { kind: 'num', key: 'installationPrice', label: 'Цена установки', unit: '₽' },
      { kind: 'area', key: 'description', label: 'Комментарий' },
      // Блоки карточки Дрома (задача 0285): слова Дрома, по ним клиент узнаёт
      // своё объявление, — а не имена колонок файла переноса.
      { kind: 'area', key: 'warranty', label: 'Гарантия и условия возврата' },
      { kind: 'area', key: 'pickup', label: 'Самовывоз' },
      { kind: 'area', key: 'deliveryPayment', label: 'Доставка и оплата' },
      // Строки Дрома дословно (задача 0286). «Раздел» — не «Секция» (место на
      // складе, блок «Основные параметры» ниже): поля стоят в разных блоках формы, рядом
      // с остальными словами объявления, а не с адресом полки.
      { kind: 'text', key: 'dromKind', label: 'Вид детали' },
      { kind: 'text', key: 'dromSection', label: 'Раздел' },
      // Слово Дрома; текст как у клиента, показ — блоками, правка — как есть (задача 0287).
      { kind: 'fits', key: 'applicability', label: 'Подходит' },
      // Второй блок применимости Дрома — его догадка (задача 0293): слово Дрома, сразу
      // под «Подходит», показ тот же — пунктами и свёрнуто; справочной применимости не трогает.
      { kind: 'fits', key: 'mayFit', label: 'Может подойти' },
      // «Прочие поля» объявления Дрома (задача 0292): показ — «Подпись: значение»,
      // правка — хранимый текст как есть.
      { kind: 'specs', key: 'characteristics', label: 'Характеристики' },
      // «Дата» объявления Дрома (задача 0298): показ — ДД.ММ.ГГГГ, правка — хранимый текст.
      { kind: 'date', key: 'adDate', label: 'Дата (Дром)' },
      { kind: 'text', key: 'note', label: 'Заметка' },
      { kind: 'text', key: 'barcode', label: 'Ст. баркод' },
    ],
  },
  {
    title: 'Основные параметры',
    fields: [
      // Наименование — то, что стоит в колонке «Наименование» витрины и уходит
      // в объявление. Правленое руками справочник больше не пересобирает.
      { kind: 'text', key: 'title', label: 'Наименование' },
      { kind: 'side', key: 'sideFr', label: 'Передний / Задний', options: SIDE_FR },
      { kind: 'side', key: 'sideLr', label: 'Левый / Правый', options: SIDE_LR },
      { kind: 'text', key: 'manufacturer', label: 'Производитель' },
      // Слово клиента с карточки Дрома (задача 0277).
      { kind: 'text', key: 'authenticity', label: 'Оригинальность' },
      { kind: 'text', key: 'oem', label: 'Номер производителя' },
      { kind: 'text', key: 'crosses', label: 'Кросс-номера', note: 'Через запятую' },
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
    // Правка здесь меняет эту позицию, а не машину-донора и не соседние
    // детали с неё (задача 0302): у позиции свои колонки, и пустое своё
    // значит «как у донора или применимости».
    title: 'Автомобиль-донор',
    fields: [
      { kind: 'vehicle', key: 'donorCode', label: 'Номер донора',
        fallback: (r) => plainText(r.donorCode) },
      { kind: 'vehicle', key: 'brand', label: 'Марка', fallback: (r) => plainText(r.brand) },
      { kind: 'vehicle', key: 'model', label: 'Модель', fallback: (r) => plainText(r.model) },
      { kind: 'vehicle', key: 'body', label: 'Модель кузова',
        fallback: (r) => plainText(r.body) },
      { kind: 'vehicle', key: 'engine', label: 'Модель двигателя',
        fallback: (r) => plainText(r.engine) },
      { kind: 'vehicle', key: 'year', label: 'Год выпуска',
        fallback: (r) => plainText(r.year) },
      { kind: 'vehicle', key: 'equipment', label: 'Комплектация',
        fallback: (r) => plainText(r.equipment) },
      { kind: 'vehicle', key: 'generation', label: 'Поколение',
        fallback: (r) => plainText(generationOf(r)) },
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
      { kind: 'text', key: 'legacyCode', label: 'Старые данные' },
      { kind: 'text', key: 'dromAdNumber', label: 'Номер объявления Дрома',
        note: 'Связывает позицию с объявлением на Дроме: с другим номером она'
          + ' будет числиться за другим объявлением' },
    ],
  },
];


/**
 * Поля задачи 0302: уезжают, только если их строку раскрыли (см. описание
 * формы). Нераскрытое — `undefined`, то есть ключа в запросе нет, и сервер
 * поле не трогает.
 */
const SENT_WHEN_OPEN = ['code', 'title', 'sideLr', 'sideFr', 'authenticity', 'oem',
  'crosses', 'legacyCode', 'dromAdNumber', 'donorCode', 'brand', 'model', 'generation',
  'body', 'engine', 'year', 'equipment'] as const;

/**
 * Черновик таким, каким его видел человек: у машины позиции без своего
 * значения на экране стоит донор или применимость, и раскрытая строка
 * открывается с ним же. Нетронутым считается это значение, а не пустота:
 * иначе раскрытая и нетронутая «Марка» уехала бы донорской маркой своей.
 */
function shownDraft(loaded: Draft, row: CatalogRow): Draft {
  const shown = { ...loaded };
  for (const section of SECTIONS) {
    for (const field of section.fields) {
      if (field.kind === 'vehicle' && loaded[field.key].trim() === '') {
        shown[field.key] = field.fallback(row);
      }
    }
  }
  return shown;
}

/**
 * Изменённое и увиденное — то, что уезжает на сервер (задача 0307).
 *
 * <p>`now` и `was` — одна и та же форма, собранная `toEdit` из правленого
 * и из увиденного черновика: поле изменено, если они разошлись. Цена
 * с арифметикой изменена всегда — «−10 %» от любого числа это правка.
 * Увиденное берётся из ответа сервера как есть (`card`), а не из черновика:
 * сервер сравнивает его с базой, и приведённая к строке или обрезанная копия
 * дала бы конфликт на ровном месте.
 */
export function changedEdit(
  now: PartEdit,
  was: PartEdit,
  card: PartEdit,
  priceOp: PriceOperation,
): { edit: Partial<PartEdit>; seen: Partial<PartEdit> } {
  const edit: Record<string, unknown> = {};
  const seen: Record<string, unknown> = {};
  const nowValues = now as unknown as Record<string, unknown>;
  const wasValues = was as unknown as Record<string, unknown>;
  const cardValues = card as unknown as Record<string, unknown>;
  for (const key of Object.keys(nowValues)) {
    const value = nowValues[key];
    if (value === undefined) continue;
    const arithmetic = key === 'price' && priceOp !== 'SET';
    if (!arithmetic && JSON.stringify(value) === JSON.stringify(wasValues[key] ?? null)) continue;
    edit[key] = value;
    seen[key] = cardValues[key] ?? null;
  }
  return { edit: edit as Partial<PartEdit>, seen: seen as Partial<PartEdit> };
}

function plainText(value: string | number | null): string {
  return value === null || value === undefined ? '' : String(value);
}

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
  condition: string;
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
  warranty: string;
  pickup: string;
  deliveryPayment: string;
  dromKind: string;
  dromSection: string;
  applicability: string;
  characteristics: string;
  mayFit: string;
  adDate: string;
  number: string;
  code: string;
  title: string;
  sideLr: string;
  sideFr: string;
  authenticity: string;
  oem: string;
  crosses: string;
  legacyCode: string;
  dromAdNumber: string;
  donorCode: string;
  brand: string;
  model: string;
  generation: string;
  body: string;
  engine: string;
  year: string;
  equipment: string;
}

export function draftOf(card: PartEdit): Draft {
  return {
    price: text(card.price),
    minPrice: text(card.minPrice),
    costPrice: text(card.costPrice),
    installationPrice: text(card.installationPrice),
    condition: card.condition ?? '',
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
    // Старый ответ сервера блоков не несёт: `undefined` читается пустым.
    warranty: card.warranty ?? '',
    pickup: card.pickup ?? '',
    deliveryPayment: card.deliveryPayment ?? '',
    dromKind: card.dromKind ?? '',
    dromSection: card.dromSection ?? '',
    applicability: card.applicability ?? '',
    characteristics: card.characteristics ?? '',
    mayFit: card.mayFit ?? '',
    adDate: card.adDate ?? '',
    number: card.number === null || card.number === undefined ? '' : String(card.number),
    code: card.code ?? '',
    // Без пометки состояния, как в шапке и в просмотре (задача 0311). Увиденным
    // уезжает ответ сервера как есть (`card`), поэтому раскрытая и нетронутая
    // строка не шлёт ничего, а правленая — то, что набрал человек.
    title: shownTitle(card.title ?? null) ?? '',
    sideLr: card.sideLr ?? '',
    sideFr: card.sideFr ?? '',
    authenticity: card.authenticity ?? '',
    oem: card.oem ?? '',
    crosses: card.crosses ?? '',
    legacyCode: card.legacyCode ?? '',
    dromAdNumber: card.dromAdNumber ?? '',
    donorCode: card.donorCode ?? '',
    brand: card.brand ?? '',
    model: card.model ?? '',
    generation: card.generation ?? '',
    body: card.body ?? '',
    engine: card.engine ?? '',
    year: card.year ?? '',
    equipment: card.equipment ?? '',
  };
}

export function toEdit(form: Draft, open: readonly string[] = []): PartEdit {
  const sent = (key: typeof SENT_WHEN_OPEN[number]): string | undefined =>
    (open.includes(key) ? form[key] : undefined);
  return {
    // Поля задачи 0302 — только раскрытые; очистка — пустая строка.
    // Строкой цифр: Number() округлил бы номер больше 2^53 − 1, и сервер
    // принял бы не тот (задача 0303). Границу называет сервер словами.
    number: open.includes('number') ? form.number.trim() : undefined,
    code: sent('code'),
    title: sent('title'),
    sideLr: sent('sideLr'),
    sideFr: sent('sideFr'),
    authenticity: sent('authenticity'),
    oem: sent('oem'),
    crosses: sent('crosses'),
    legacyCode: sent('legacyCode'),
    dromAdNumber: sent('dromAdNumber'),
    donorCode: sent('donorCode'),
    brand: sent('brand'),
    model: sent('model'),
    generation: sent('generation'),
    body: sent('body'),
    engine: sent('engine'),
    year: sent('year'),
    equipment: sent('equipment'),
    price: number(form.price),
    minPrice: number(form.minPrice),
    costPrice: number(form.costPrice),
    installationPrice: number(form.installationPrice),
    // Пустое состояние — «не трогать», а не «очистить»: у детали нет
    // состояния «не заполнено». Это единственное поле формы с такой
    // семантикой, и сервер её повторяет.
    condition: blank(form.condition),
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
    // Дословно, без обрезки краёв и без «пусто — null»: клиент не должен терять
    // слова, а очистка блока — пустая строка (сервер приводит её к NULL).
    // `null` сервер читает как «ключа не было» и блок не трогает.
    warranty: form.warranty,
    pickup: form.pickup,
    deliveryPayment: form.deliveryPayment,
    dromKind: form.dromKind,
    dromSection: form.dromSection,
    applicability: form.applicability,
    characteristics: form.characteristics,
    mayFit: form.mayFit,
    adDate: form.adDate,
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
