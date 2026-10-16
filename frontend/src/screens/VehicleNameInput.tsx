import { useEffect, useRef, useState } from 'react';
import {
  brandNamed, isStale, loadCached, modelsOf, refresh, suggestByName,
  type VehicleCatalog,
} from '../catalog/vehicles';
import { useMounted } from '../ui/useMounted';

/**
 * Справочник машин для подсказок: `null` — ещё не просили, дальше три
 * различимых состояния — грузим, не смогли, есть.
 */
export type VehicleCatalogState = VehicleCatalog | 'loading' | 'unavailable' | null;

/**
 * Справочник машин — только когда он понадобился (задача 0308).
 *
 * <p><b>По первому раскрытию «Марки» или «Модели», а не при открытии формы.</b>
 * Карточку правят ради цены и заметки по многу раз в день, а справочник —
 * восемьсот килобайт: качать его на каждое «Изменить» незачем. Берётся
 * из IndexedDB, куда его кладёт экран машин; нет там или устарел — один
 * запрос за всем справочником, как у приёмки. Владелец за компьютером
 * экран машин мог не открывать никогда, и без запроса подсказок у него
 * не было бы вовсе.
 *
 * <p>Ответ проверяется на форму: справочник, не похожий на справочник, —
 * это «подсказок нет», а не упавшая форма. Правка карточки работает и без
 * подсказок: поле свободное.
 */
export function useVehicleCatalog(wanted: boolean): VehicleCatalogState {
  const [state, setState] = useState<VehicleCatalogState>(null);
  const started = useRef(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    if (!wanted || started.current) return;
    started.current = true;
    setState('loading');
    void (async () => {
      let found: VehicleCatalog | null = null;
      try {
        found = await loadCached();
      } catch {
        found = null;
      }
      if ((!usable(found) || isStale(found)) && navigator.onLine) {
        try {
          found = await refresh();
        } catch {
          // Не обновилось — работаем на том, что лежит, если оно годное.
        }
      }
      if (mounted.current) setState(usable(found) ? found : 'unavailable');
    })();
  }, [wanted, mounted]);

  return state;
}

function usable(found: VehicleCatalog | null): found is VehicleCatalog {
  return found !== null && Array.isArray(found.brands) && Array.isArray(found.models);
}

/**
 * Поле «Марка» или «Модель» машины позиции с подсказками справочника.
 *
 * <p><b>Подсказка ничего не запрещает и ничего не подменяет.</b> Решение
 * владельца продукта 16.10.2026: «a, но также должна быть возможность
 * свободного текста». Выбор нажатием ставит написание справочника; набранное
 * и не выбранное уезжает как набрано — «Лексус» остаётся «Лексус», «тойота»
 * не становится «Toyota» ни здесь, ни на сервере.
 *
 * <p><b>Запроса на каждый знак нет вовсе:</b> справочник уже на устройстве,
 * подсказки считаются из него на месте (308 марок, у марки до трёхсот
 * моделей), поэтому и задержки перед подсказкой нет. Список — не длиннее
 * восьми строк (`suggestByName`).
 *
 * <p>Список закрывается, когда в поле стоит написание справочника, — то же
 * правило, что у наименования на приёмке: оставшийся после выбора список
 * не говорит, что выбор засчитан.
 */
export function VehicleNameInput({ kind, label, value, brand, catalog, onChange }: {
  kind: 'brand' | 'model';
  label: string;
  value: string;
  /** Марка, к которой подсказываются модели: та, что стоит в строке «Марка». */
  brand: string;
  catalog: VehicleCatalogState;
  onChange: (value: string) => void;
}) {
  const input = (
    <input aria-label={label} autoFocus value={value} autoCapitalize="none"
           onChange={(e) => onChange(e.target.value)} />
  );
  if (catalog === null || catalog === 'loading') {
    return <>{input}<span className="muted"> Загружаем подсказки…</span></>;
  }
  if (catalog === 'unavailable') {
    return (
      <>
        {input}
        <span className="muted"> Подсказок нет: справочник машин не загрузился</span>
      </>
    );
  }

  let names: string[];
  if (kind === 'brand') {
    names = catalog.brands.map((b) => b.name);
  } else {
    const known = brandNamed(catalog.brands, brand);
    if (known === null) {
      // Марки нет в справочнике — подсказывать нечего, поле свободное.
      return brand.trim() === '' ? input : (
        <>
          {input}
          <span className="muted"> Марки «{brand.trim()}» нет в справочнике — подсказок нет</span>
        </>
      );
    }
    names = modelsOf(catalog, known.id).map((m) => m.name);
  }

  const term = value.trim();
  const exact = names.includes(term);
  const shown = exact ? [] : suggestByName(names.map((name) => ({ name })), term);

  return (
    <>
      {input}
      {shown.length > 0 && (
        <ul className="suggestions" aria-label={`Подсказки: ${label}`}>
          {shown.map(({ name }) => (
            <li key={name}>
              <button type="button" className="button--ghost" onClick={() => onChange(name)}>
                {name}
              </button>
            </li>
          ))}
        </ul>
      )}
      {term !== '' && !exact && shown.length === 0 && (
        <span className="muted"> Нет в справочнике — сохранится как вписано</span>
      )}
    </>
  );
}
