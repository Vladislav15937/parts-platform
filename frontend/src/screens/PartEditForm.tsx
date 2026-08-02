import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { loadEditable, savePart, type PartEdit } from '../inventory/catalog';

/**
 * Правка карточки товара.
 *
 * <p><b>До этого править карточку было нечем.</b> Цена, заведённая приёмщиком
 * с опечаткой, оставалась в складе навсегда: менять её умел только
 * {@code PartService.changePrice}, которого не звал ни один эндпоинт.
 * А на разборке цену двигают постоянно — «повисло полгода, снижаем».
 *
 * <p><b>Заголовка, стороны и состояния в форме нет.</b> Заголовок собирается
 * из справочника наименований, машины и стороны, и правка руками разошлась бы
 * с ним при первом же пересопоставлении. Ошибку в стороне лечит разбор
 * наименований, а не поле здесь.
 *
 * <p>Пустое поле означает «очищено», а не «оставить как было»: форма уезжает
 * целиком. Иначе стереть заметку было бы невозможно.
 */
export function PartEditForm({ partId, onSaved, onCancel }: {
  partId: number;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<Draft | null>(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    void loadEditable(partId)
      .then((card) => { if (alive) setForm(draftOf(card)); })
      .catch(() => { if (alive) setError('Не удалось прочитать карточку'); });
    return () => { alive = false; };
  }, [partId]);

  function set<K extends keyof Draft>(key: K, value: Draft[K]): void {
    setForm((f) => (f === null ? f : { ...f, [key]: value }));
  }

  async function save(): Promise<void> {
    if (form === null) return;
    setError('');
    setSaving(true);
    try {
      await savePart(partId, toEdit(form));
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Не удалось сохранить');
    } finally {
      setSaving(false);
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
      <h4>Деньги</h4>
      <div className="card-edit__grid">
        <Num label="Цена" value={form.price} onChange={(v) => set('price', v)} />
        <Num label="Минимальная цена" value={form.minPrice}
             onChange={(v) => set('minPrice', v)} />
        <Num label="Себестоимость" value={form.costPrice}
             onChange={(v) => set('costPrice', v)} />
        <Num label="Цена установки" value={form.installationPrice}
             onChange={(v) => set('installationPrice', v)} />
      </div>

      <h4>Описание</h4>
      <label className="field">
        Комментарий
        <textarea rows={2} value={form.description}
                  onChange={(e) => set('description', e.target.value)} />
      </label>
      <label className="field">
        Заметка
        <input value={form.note} onChange={(e) => set('note', e.target.value)} />
      </label>
      <label className="field">
        {/* Уезжает покупателю, в отличие от заметки и комментария. */}
        Текстовый блок
        <textarea rows={2} value={form.textBlock}
                  onChange={(e) => set('textBlock', e.target.value)} />
      </label>
      <label className="field">
        Видео
        <input value={form.videoUrl} onChange={(e) => set('videoUrl', e.target.value)}
               placeholder="ссылка на ролик" />
      </label>

      <h4>Свойства</h4>
      <div className="card-edit__grid">
        <label className="field">
          Оценка состояния
          <select value={form.qualityGrade}
                  onChange={(e) => set('qualityGrade', e.target.value)}>
            <option value="">—</option>
            {Object.entries(GRADES).map(([value, title]) => (
              <option key={value} value={value}>{title}</option>
            ))}
          </select>
        </label>
        <Text label="Производитель" value={form.manufacturer}
              onChange={(v) => set('manufacturer', v)} />
        <Text label="Маркировка" value={form.marking} onChange={(v) => set('marking', v)} />
        <Text label="Цвет" value={form.color} onChange={(v) => set('color', v)} />
        <Text label="Секция" value={form.section} onChange={(v) => set('section', v)} />
        <Text label="Ст. баркод" value={form.barcode} onChange={(v) => set('barcode', v)} />
      </div>

      <h4>Габариты и вес</h4>
      <div className="card-edit__grid">
        <Num label="Вес, кг" value={form.weightKg} onChange={(v) => set('weightKg', v)} />
        <Num label="Длина, мм" value={form.lengthMm} onChange={(v) => set('lengthMm', v)} />
        <Num label="Ширина, мм" value={form.widthMm} onChange={(v) => set('widthMm', v)} />
        <Num label="Высота, мм" value={form.heightMm} onChange={(v) => set('heightMm', v)} />
        <Num label="Вес в упаковке, кг" value={form.packageWeightKg}
             onChange={(v) => set('packageWeightKg', v)} />
        <Num label="Длина упаковки, мм" value={form.packageLengthMm}
             onChange={(v) => set('packageLengthMm', v)} />
        <Num label="Ширина упаковки, мм" value={form.packageWidthMm}
             onChange={(v) => set('packageWidthMm', v)} />
        <Num label="Высота упаковки, мм" value={form.packageHeightMm}
             onChange={(v) => set('packageHeightMm', v)} />
      </div>

      <label className="field field--check">
        <input type="checkbox" checked={form.published}
               onChange={(e) => set('published', e.target.checked)} />
        Выгружать на площадки
      </label>

      {error !== '' && <p className="note note--error">{error}</p>}

      <div className="filter-row">
        <button type="button" disabled={saving} onClick={() => void save()}>
          {saving ? 'Сохраняем…' : 'Сохранить'}
        </button>
        <button type="button" className="button--ghost" onClick={onCancel}>
          Отмена
        </button>
      </div>
    </div>
  );
}

function Num({ label, value, onChange }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="field">
      {label}
      <input inputMode="decimal" value={value}
             onChange={(e) => onChange(e.target.value.replace(',', '.'))} />
    </label>
  );
}

function Text({ label, value, onChange }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="field">
      {label}
      <input value={value} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
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
