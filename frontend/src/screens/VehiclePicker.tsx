import { useEffect, useMemo, useState } from 'react';
import { useLockedScroll } from '../ui/useLockedScroll';
import { loadVehicleOptions, type VehicleOption } from '../inventory/catalog';
import { NO_VEHICLE, type VehicleFilter } from '../inventory/catalog';

/**
 * Подбор запчасти по машине: марка → модель → кузов → двигатель.
 *
 * <p><b>Шагами, а не пятью полями сразу.</b> Модель без марки не значит ничего,
 * а кузов без модели — тем более: «ACV40» есть только у Camry. Каждый шаг
 * сужает следующий список, и на любом можно остановиться — деталь ищут
 * и «на любую Camry», и «на Camry с двигателем 2AZ».
 *
 * <p><b>Уровень пропускается, если выбирать не из чего.</b> У марки одна
 * модель, у модели один кузов — показывать список из одной строки значит
 * просить нажать кнопку, у которой нет альтернативы.
 */
export function VehiclePicker({ chosen, onPick, onClose }: {
  chosen: VehicleFilter;
  onPick: (vehicle: VehicleFilter) => void;
  onClose: () => void;
}) {
  const [options, setOptions] = useState<VehicleOption[] | null>(null);
  const [error, setError] = useState('');
  const [draft, setDraft] = useState<VehicleFilter>(chosen);
  const [search, setSearch] = useState('');

  useEffect(() => {
    loadVehicleOptions()
      .then(setOptions)
      .catch(() => setError('Список машин не загрузился'));
  }, []);

  // Что осталось после уже сделанных шагов — из этого и строится очередной
  // список. Считать заново на каждом шаге дешевле, чем хранить дерево:
  // строк тут по числу разобранных машин.
  const left = useMemo(() => (options ?? []).filter((row) => (
    (draft.brandId === null || row.brandId === draft.brandId)
    && (draft.modelId === null || row.modelId === draft.modelId)
    && (draft.body === '' || row.body === draft.body)
  )), [options, draft]);

  const level = levelOf(draft, left);
  const items = itemsOf(level, left, search);

  function step(item: Item) {
    setSearch('');
    if (level === 'brand') {
      setDraft({ ...NO_VEHICLE, brandId: item.id as number, brandName: item.label });
    } else if (level === 'model') {
      setDraft({ ...draft, modelId: item.id as number, modelName: item.label, body: '', engine: '' });
    } else if (level === 'body') {
      setDraft({ ...draft, body: item.label, engine: '' });
    } else {
      setDraft({ ...draft, engine: item.label });
    }
  }

  /** Шаг назад по цепочке: сбрасывает всё, что было выбрано после него. */
  function back(to: 'brand' | 'model' | 'body') {
    setSearch('');
    if (to === 'brand') {
      setDraft(NO_VEHICLE);
    } else if (to === 'model') {
      setDraft({ ...draft, modelId: null, modelName: '', body: '', engine: '' });
    } else {
      setDraft({ ...draft, body: '', engine: '' });
    }
  }

  useLockedScroll();

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className={level === null ? 'modal modal--short' : 'modal'}
        onClick={(event) => event.stopPropagation()}
      >
        <h2>Подбор по машине</h2>

        <div className="crumbs">
          {draft.brandId !== null && (
            <button type="button" className="crumb" onClick={() => back('brand')}>
              {draft.brandName} ✕
            </button>
          )}
          {draft.modelId !== null && (
            <button type="button" className="crumb" onClick={() => back('model')}>
              {draft.modelName} ✕
            </button>
          )}
          {draft.body !== '' && (
            <button type="button" className="crumb" onClick={() => back('body')}>
              {draft.body} ✕
            </button>
          )}
          {draft.engine !== '' && (
            <button
              type="button"
              className="crumb"
              onClick={() => setDraft({ ...draft, engine: '' })}
            >
              {draft.engine} ✕
            </button>
          )}
          {level !== null && <span className="crumb crumb--next">{PROMPTS[level]}</span>}
        </div>

        {error !== '' && <p className="error">{error}</p>}
        {options === null && error === '' && <p className="hint">Загружаю…</p>}

        {level === null && options !== null && (
          <p className="hint">Сужать больше нечем — нажмите «Показать запчасти».</p>
        )}

        {level !== null && (
          <>
            <label className="field">
              <input
                /* Ключ по уровню: без него поле остаётся тем же элементом,
                   autoFocus второй раз не срабатывает, и набранное после
                   выбора марки уходит мимо окна. */
                key={level}
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder={PROMPTS[level]}
                autoFocus
              />
            </label>
            <ul className="picker-list">
              {items.map((item) => (
                <li key={String(item.id)}>
                  <button type="button" onClick={() => step(item)}>
                    <span>{item.label}</span>
                    <span className="picker-count">{item.parts}</span>
                  </button>
                </li>
              ))}
              {items.length === 0 && options !== null && (
                <li className="hint">Ничего не нашлось</li>
              )}
            </ul>
          </>
        )}

        <div className="filter-row">
          <button type="button" className="button--ghost" onClick={onClose}>
            Отменить
          </button>
          <button
            type="button"
            onClick={() => onPick(draft)}
            disabled={draft.brandId === null}
          >
            Показать запчасти
          </button>
        </div>
      </div>
    </div>
  );
}

type Level = 'brand' | 'model' | 'body' | 'engine';

const PROMPTS: Record<Level, string> = {
  brand: 'Выберите марку',
  model: 'Выберите модель',
  body: 'Выберите кузов',
  engine: 'Выберите двигатель',
};

interface Item {
  id: string | number;
  label: string;
  parts: number;
}

/**
 * На каком шаге стоим. Пустой уровень пропускается: выбирать из одного
 * значения нечего, а из ни одного — тем более (у переехавшего клиента
 * кузов и двигатель могут быть не заполнены вовсе).
 */
export function levelOf(draft: VehicleFilter, left: VehicleOption[]): Level | null {
  if (draft.brandId === null) {
    return 'brand';
  }
  if (draft.modelId === null && distinct(left, (row) => row.modelId).length > 1) {
    return 'model';
  }
  if (draft.body === '' && distinct(left, (row) => row.body).length > 1) {
    return 'body';
  }
  if (draft.engine === '' && distinct(left, (row) => row.engine).length > 1) {
    return 'engine';
  }
  return null;
}

export function itemsOf(level: Level | null, left: VehicleOption[], search: string): Item[] {
  if (level === null) {
    return [];
  }
  const sums = new Map<string, Item>();
  for (const row of left) {
    const id = level === 'brand' ? row.brandId
      : level === 'model' ? row.modelId
        : level === 'body' ? row.body : row.engine;
    const label = level === 'brand' ? row.brand
      : level === 'model' ? row.model
        : level === 'body' ? row.body : row.engine;
    if (id === null || label === null || label === '') {
      continue;
    }
    const key = String(id);
    const seen = sums.get(key);
    sums.set(key, { id, label, parts: (seen?.parts ?? 0) + row.parts });
  }
  const needle = search.trim().toLowerCase();
  return [...sums.values()]
    .filter((item) => needle === '' || item.label.toLowerCase().includes(needle))
    .sort((a, b) => a.label.localeCompare(b.label, 'ru'));
}

function distinct<T>(rows: VehicleOption[], of: (row: VehicleOption) => T): T[] {
  return [...new Set(rows.map(of))].filter((value) => value !== null && value !== '');
}
