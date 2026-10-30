import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  donorCard,
  updateDonor,
  donorSavedMessage,
  type DonorDetails,
  type DonorFields,
  type DonorOption,
  type DonorVocabulary,
} from '../intake/donors';
import { plural } from '../ui/plural';
import { useMounted } from '../ui/useMounted';
import { donorRows } from './PartDonorView';

/** Словарь руля, КПП и привода: пока грузится, не загрузился, есть. */
export type VocabularyState = DonorVocabulary | 'loading' | 'failed';

/**
 * Поля машины, которых до задачи 0364 на форме не было вовсе, хотя сервер
 * их принимал: руль, КПП, привод, цвет, комплектация, пробег. Одни на
 * заведение и на правку — две формы одной машины разошлись бы на первом
 * новом поле.
 */
export interface DonorSpec {
  steering: string;
  transmissionType: string;
  transmissionModel: string;
  driveType: string;
  color: string;
  equipmentCode: string;
  /** Строкой, как в поле: пустое — «не заполнено». */
  mileage: string;
}

export const EMPTY_SPEC: DonorSpec = {
  steering: '',
  transmissionType: '',
  transmissionModel: '',
  driveType: '',
  color: '',
  equipmentCode: '',
  mileage: '',
};

/** Пробег — целое неотрицательное; иначе форма скажет об этом до отправки. */
export function mileageBad(value: string): boolean {
  return value.trim() !== '' && !/^\d+$/.test(value.trim());
}

/** Поля формы — в значения запроса: пустое уезжает `null`, то есть «очищено». */
export function specToFields(spec: DonorSpec): Pick<DonorFields,
    'steering' | 'transmissionType' | 'transmissionModel' | 'driveType'
    | 'color' | 'equipmentCode' | 'mileageKm'> {
  return {
    steering: orNull(spec.steering),
    transmissionType: orNull(spec.transmissionType),
    transmissionModel: orNull(spec.transmissionModel),
    driveType: orNull(spec.driveType),
    color: orNull(spec.color),
    equipmentCode: orNull(spec.equipmentCode),
    mileageKm: spec.mileage.trim() === '' ? null : Number(spec.mileage.trim()),
  };
}

function specOf(form: DonorFields): DonorSpec {
  return {
    steering: form.steering ?? '',
    transmissionType: form.transmissionType ?? '',
    transmissionModel: form.transmissionModel ?? '',
    driveType: form.driveType ?? '',
    color: form.color ?? '',
    equipmentCode: form.equipmentCode ?? '',
    mileage: form.mileageKm === null ? '' : String(form.mileageKm),
  };
}

function orNull(value: string): string | null {
  return value.trim() === '' ? null : value.trim();
}

export function DonorSpecFields({ spec, onChange, vocabulary }: {
  spec: DonorSpec;
  onChange: (next: DonorSpec) => void;
  vocabulary: VocabularyState;
}) {
  const set = (patch: Partial<DonorSpec>) => onChange({ ...spec, ...patch });
  return (
    <>
      {vocabulary === 'failed' && (
        // «Пусто» и «не смогли узнать» — разные вещи: пустой выбор руля
        // читался бы как «у машины руля нет».
        <p className="note note--error">
          Руль, КПП и привод не загрузились — обновите страницу.
        </p>
      )}
      <div className="row">
        <Choice label="Расположение руля" value={spec.steering}
                options={vocabulary} pick={(v) => v.steering}
                onChange={(steering) => set({ steering })} />
        <Choice label="Тип КПП" value={spec.transmissionType}
                options={vocabulary} pick={(v) => v.transmission}
                onChange={(transmissionType) => set({ transmissionType })} />
      </div>
      <div className="row">
        <label>
          Модель КПП
          <input value={spec.transmissionModel} placeholder="U340E"
                 autoCapitalize="characters" autoCorrect="off"
                 onChange={(e) => set({ transmissionModel: e.target.value.toUpperCase() })} />
        </label>
        <Choice label="Привод" value={spec.driveType}
                options={vocabulary} pick={(v) => v.drive}
                onChange={(driveType) => set({ driveType })} />
      </div>
      <div className="row">
        <label>
          Цвет
          <input value={spec.color} placeholder="Черный"
                 onChange={(e) => set({ color: e.target.value })} />
        </label>
        <label>
          Комплектация
          <input value={spec.equipmentCode} placeholder="500000"
                 onChange={(e) => set({ equipmentCode: e.target.value })} />
        </label>
        <label>
          Пробег, км
          <input inputMode="numeric" value={spec.mileage} placeholder="158000"
                 onChange={(e) => set({ mileage: e.target.value })} />
        </label>
      </div>
      {mileageBad(spec.mileage) && (
        <p className="note note--error">Пробег — целое число километров, без букв и знаков</p>
      )}
    </>
  );
}

function Choice({ label, value, options, pick, onChange }: {
  label: string;
  value: string;
  options: VocabularyState;
  pick: (vocabulary: DonorVocabulary) => DonorOption[];
  onChange: (code: string) => void;
}) {
  const list = typeof options === 'string' ? [] : pick(options);
  return (
    <label>
      {label}
      <select value={value} disabled={typeof options === 'string'}
              onChange={(e) => onChange(e.target.value)}>
        <option value="">
          {options === 'loading' ? 'Загружаем…' : '— не задано —'}
        </option>
        {list.map((option) => (
          <option key={option.code} value={option.code}>{option.title}</option>
        ))}
      </select>
    </label>
  );
}

/**
 * Карточка машины под её строкой (задача 0364): плитка «N запчастей —
 * Посмотреть», строки машины словами и «карандаш» — правка той же формой,
 * что заведение.
 *
 * <p>Марка, модель, поколение и поставка здесь не правятся — уезжают тем,
 * что лежало. Ошибаются при заведении годом, пробегом и комплектацией,
 * а марку выбирают подсказкой; правка марки потянула бы на эту форму
 * справочник машин целиком.
 */
export function DonorCard({ donorId, vocabulary, online, onParts, onSaved }: {
  donorId: number;
  vocabulary: VocabularyState;
  online: boolean;
  /** Открыть склад с отбором по этой машине. */
  onParts: ((donorCode: string) => void) | undefined;
  /** Машина сохранена: список машин перечитать. */
  onSaved: () => void;
}) {
  const [details, setDetails] = useState<DonorDetails | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [year, setYear] = useState('');
  const [bodyCode, setBodyCode] = useState('');
  const [engineCode, setEngineCode] = useState('');
  const [vin, setVin] = useState('');
  const [note, setNote] = useState('');
  const [spec, setSpec] = useState<DonorSpec>(EMPTY_SPEC);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const mounted = useMounted();

  useEffect(() => {
    void donorCard(donorId)
      .then((found) => { if (mounted.current) setDetails(found); })
      .catch((cause: unknown) => {
        if (mounted.current) setFailure(describe(cause, 'Машину прочитать не удалось'));
      });
  }, [donorId, mounted]);

  if (details === null) {
    return failure !== null
      ? <p className="note note--error">{failure}</p>
      : <p className="muted">Загружаем…</p>;
  }

  const { card, form } = details;
  const yearBad = year.trim() !== '' && !/^\d{4}$/.test(year.trim());

  return (
    <div className="donor-view">
      <div className="donor-view__parts">
        <b>{card.partsCount}</b>
        <span> {plural(card.partsCount, 'запчасть', 'запчасти', 'запчастей')} </span>
        {onParts !== undefined && card.partsCount > 0 && (
          <button type="button" className="mark__link" onClick={() => onParts(card.code)}>
            Посмотреть
          </button>
        )}
      </div>

      {!editing ? (
        <>
          <button type="button" className="button--ghost" onClick={startEditing}>
            ✎ Изменить
          </button>
          {message !== null && <p className="note">{message}</p>}
          <dl className="card-view__fields">
            {donorRows(card).map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        </>
      ) : (
        <>
          <div className="row">
            <label>
              Год
              <input type="number" inputMode="numeric" value={year} placeholder="2012"
                     onChange={(e) => setYear(e.target.value)} />
            </label>
            <label>
              Кузов
              <input value={bodyCode} placeholder="ACV40" autoCapitalize="characters"
                     autoCorrect="off" onChange={(e) => setBodyCode(e.target.value.toUpperCase())} />
            </label>
            <label>
              Двигатель
              <input value={engineCode} placeholder="2AZ-FE" autoCapitalize="characters"
                     autoCorrect="off" onChange={(e) => setEngineCode(e.target.value.toUpperCase())} />
            </label>
          </div>
          {yearBad && <p className="note note--error">Год — четыре цифры</p>}
          <DonorSpecFields spec={spec} onChange={setSpec} vocabulary={vocabulary} />
          <label>
            VIN
            <input value={vin} placeholder="17 символов" autoCapitalize="characters"
                   autoCorrect="off" onChange={(e) => setVin(e.target.value.toUpperCase())} />
          </label>
          <label>
            Примечание
            <input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          {message !== null && <p className="note note--error">{message}</p>}
          {!online && <p className="note note--error">Нет связи — сохранить нельзя.</p>}
          <div className="filter-row">
            <button type="button"
                    disabled={saving || !online || yearBad || mileageBad(spec.mileage)}
                    onClick={() => void save(form)}>
              {saving ? 'Сохраняем…' : 'Сохранить'}
            </button>
            {/* Отмена ничего не отправляет: правка живёт в форме до «Сохранить». */}
            <button type="button" className="button--ghost" disabled={saving}
                    onClick={() => { setEditing(false); setMessage(null); }}>
              Отменить
            </button>
          </div>
        </>
      )}
    </div>
  );

  function startEditing(): void {
    setYear(form.year === null ? '' : String(form.year));
    setBodyCode(form.bodyCode ?? '');
    setEngineCode(form.engineCode ?? '');
    setVin(form.vin ?? '');
    setNote(form.note ?? '');
    setSpec(specOf(form));
    setMessage(null);
    setEditing(true);
  }

  async function save(was: DonorFields): Promise<void> {
    setSaving(true);
    setMessage(null);
    try {
      const saved = await updateDonor(donorId, {
        ...was,
        year: year.trim() === '' ? null : Number(year.trim()),
        bodyCode: orNull(bodyCode),
        engineCode: orNull(engineCode),
        vin: orNull(vin),
        note: orNull(note),
        ...specToFields(spec),
      });
      if (!mounted.current) return;
      setDetails(saved);
      setEditing(false);
      setMessage(donorSavedMessage(saved));
      onSaved();
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Машину сохранить не удалось'));
    } finally {
      if (mounted.current) setSaving(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    return cause.status === 0 ? 'Нет связи с сервером' : cause.message;
  }
  return fallback;
}
