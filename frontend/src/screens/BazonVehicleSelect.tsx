import { useEffect, useState } from 'react';
import {
  NO_BAZON_VEHICLE, bodyLabel, listBazonBodies, listBazonMarks, listBazonModels,
  listBazonMotors, motorLabel,
  type BazonBody, type BazonMark, type BazonModel, type BazonMotor, type BazonSelection,
} from '../catalog/bazonVehicles';
import { useMounted } from '../ui/useMounted';

/**
 * Выбор машины из общего справочника: марка → модель → кузов → двигатель
 * (задача 0377).
 *
 * <p><b>Порядок и зависимость.</b> Списки сужаются выбранным: модели — марки,
 * кузова — модели, двигатели — марки. Смена марки сбрасывает всё ниже неё,
 * смена модели — кузов: иначе осталась бы машина, которой не бывает (сервер
 * такую всё равно отобьёт словами). Пустой выбор допустим на любом шаге —
 * «не выбрана».
 *
 * <p>Слова справочника общие для всех клиентов; свободного ввода здесь нет —
 * вписать своё можно в строках «Марка», «Модель» ниже.
 */
/** Ответ — обещание, а не гарантия: не список читается как «записей нет». */
function list<T>(found: T[] | unknown): T[] {
  return Array.isArray(found) ? found : [];
}

export function BazonVehicleSelect({ value, onChange }: {
  value: BazonSelection;
  onChange: (next: BazonSelection) => void;
}) {
  const mounted = useMounted();
  const [marks, setMarks] = useState<BazonMark[] | null>(null);
  const [models, setModels] = useState<BazonModel[]>([]);
  const [bodies, setBodies] = useState<BazonBody[]>([]);
  const [motors, setMotors] = useState<BazonMotor[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    listBazonMarks()
      .then((found) => { if (mounted.current) setMarks(list(found)); })
      .catch(() => { if (mounted.current) setError('Справочник машин не загрузился'); });
  }, [mounted]);

  useEffect(() => {
    if (value.markId == null) { setModels([]); setMotors([]); return; }
    let alive = true;
    listBazonModels(value.markId).then((found) => { if (alive && mounted.current) setModels(list(found)); })
      .catch(() => { if (alive && mounted.current) setError('Справочник машин не загрузился'); });
    listBazonMotors(value.markId).then((found) => { if (alive && mounted.current) setMotors(list(found)); })
      .catch(() => { if (alive && mounted.current) setError('Справочник машин не загрузился'); });
    return () => { alive = false; };
  }, [value.markId, mounted]);

  useEffect(() => {
    if (value.modelId == null) { setBodies([]); return; }
    let alive = true;
    listBazonBodies(value.modelId).then((found) => { if (alive && mounted.current) setBodies(list(found)); })
      .catch(() => { if (alive && mounted.current) setError('Справочник машин не загрузился'); });
    return () => { alive = false; };
  }, [value.modelId, mounted]);

  const idOf = (raw: string): number | null => (raw === '' ? null : Number(raw));

  return (
    <span className="bazon-vehicle">
      <select aria-label="Марка (справочник)" value={value.markId ?? ''}
              onChange={(e) => onChange({ ...NO_BAZON_VEHICLE, markId: idOf(e.target.value) })}>
        <option value="">— не выбрана —</option>
        {(marks ?? []).map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
      </select>
      <select aria-label="Модель (справочник)" value={value.modelId ?? ''}
              disabled={value.markId == null}
              onChange={(e) => onChange({
                ...value, modelId: idOf(e.target.value), bodyId: null })}>
        <option value="">— не выбрана —</option>
        {models.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
      </select>
      <select aria-label="Кузов (справочник)" value={value.bodyId ?? ''}
              disabled={value.modelId == null}
              onChange={(e) => onChange({ ...value, bodyId: idOf(e.target.value) })}>
        <option value="">— не выбран —</option>
        {bodies.map((b) => <option key={b.id} value={b.id}>{bodyLabel(b)}</option>)}
      </select>
      <select aria-label="Двигатель (справочник)" value={value.motorId ?? ''}
              disabled={value.markId == null}
              onChange={(e) => onChange({ ...value, motorId: idOf(e.target.value) })}>
        <option value="">— не выбран —</option>
        {motors.map((m) => <option key={m.id} value={m.id}>{motorLabel(m)}</option>)}
      </select>
      {error !== '' && <span className="note note--error"> {error}</span>}
    </span>
  );
}
