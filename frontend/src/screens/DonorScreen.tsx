import { useEffect, useState } from 'react';
import { ApiError, request } from '../api/client';
import {
  generationForYear,
  generationsOf,
  isStale,
  isValidVin,
  loadCached,
  modelsOf,
  refresh,
  suggestBrands,
} from '../catalog/vehicles';
import type { Brand, Generation, Model, VehicleCatalog } from '../catalog/vehicles';
import type { Reference } from '../reference/reference';
import { DonorCosts } from './DonorCosts';
import {
  donorTitle,
  listDonors,
  startDismantling,
  statusTitle,
  type DonorEntry,
} from '../intake/donors';
import { ScanOverlay } from '../scan/ScanOverlay';

/**
 * Заведение машины-донора.
 *
 * <p><b>Онлайн, в отличие от приёмки деталей.</b> Донор должен существовать
 * до того, как на него начнут вешать детали: очередь вернула бы приёмщику
 * машину, которой ещё нет в списке, и он не смог бы выбрать её на следующем
 * экране. Машины при этом заводят по несколько в неделю, а детали — сотнями
 * в день, так что цена этого ограничения невелика.
 *
 * <p><b>Справочник машин при этом работает офлайн.</b> Он предзагружен целиком:
 * марку и модель приёмщик выбирает, стоя у машины, и подгружать их по клику
 * значит сделать экран неработающим ровно там, где он нужен.
 */
interface Props {
  reference: Reference;
  online: boolean;
  /** Машины изменились: справочник приёмки надо перечитать. */
  onChanged: () => void;
}

export function DonorScreen({ reference, online, onChanged }: Props) {
  // Затраты вносятся по уже заведённой машине: до её появления вкладывать
  // не во что, а после — покупка, эвакуатор и разбор идут отдельными
  // платежами и в разные дни.
  const [costsOf, setCostsOf] = useState<number | null>(null);
  const [donors, setDonors] = useState<DonorEntry[]>([]);
  const [busy, setBusy] = useState(false);
  const [catalog, setCatalog] = useState<VehicleCatalog | null>(null);
  const [loading, setLoading] = useState(true);

  const [brand, setBrand] = useState<Brand | null>(null);
  const [brandInput, setBrandInput] = useState('');
  const [modelId, setModelId] = useState<number | null>(null);
  const [year, setYear] = useState('');
  const [generationId, setGenerationId] = useState<number | null>(null);
  const [vin, setVin] = useState('');
  const [scanning, setScanning] = useState(false);
  const [supplyId, setSupplyId] = useState<number | null>(null);
  const [note, setNote] = useState('');

  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // Список машин — с сервера, а не из офлайн-справочника: там только те,
  // что в разборе, и только что заведённой машины в нём нет.
  useEffect(() => {
    void reloadDonors();
  }, []);

  useEffect(() => {
    void (async () => {
      const cached = await loadCached();
      setCatalog(cached);
      setLoading(false);
      // Обновляем молча и только при связи: экран обязан быть рабочим
      // немедленно, а справочник меняется с релизами, не за день.
      if (navigator.onLine && isStale(cached)) {
        try {
          setCatalog(await refresh());
        } catch {
          // Не обновилось — работаем на том, что есть.
        }
      }
    })();
  }, []);

  if (loading) {
    return <section className="card">Загрузка справочника…</section>;
  }

  if (catalog === null) {
    return (
      <section className="card">
        <h2>Машина</h2>
        <p className="note note--error">
          Справочник машин не загружен. Откройте экран при связи один раз — дальше
          он работает без неё.
        </p>
        <button type="button" disabled={!online} onClick={() => void load()}>
          Загрузить справочник
        </button>
      </section>
    );
  }

  const models = modelsOf(catalog, brand?.id ?? null);
  const generations = generationsOf(catalog, modelId);
  const suggestions = brand === null ? suggestBrands(catalog.brands, brandInput) : [];
  const vinTouched = vin.trim() !== '';
  const vinBad = vinTouched && !isValidVin(vin);

  return (
    <section className="card">
      <h2>Машина</h2>

      <label>
        Марка
        <input
          value={brand === null ? brandInput : brand.name}
          onChange={(e) => {
            setBrandInput(e.target.value);
            setBrand(null);
            setModelId(null);
            setGenerationId(null);
          }}
          placeholder="тойота, toyota"
          autoCapitalize="none"
        />
      </label>

      {suggestions.length > 0 && (
        <ul className="suggestions">
          {suggestions.map((b) => (
            <li key={b.id}>
              <button type="button" className="button--ghost" onClick={() => pickBrand(b)}>
                {b.name}
                {b.nameRu !== null && <span className="muted"> · {b.nameRu}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      {brand !== null && (
        <label>
          Модель
          <select
            value={modelId ?? ''}
            onChange={(e) => pickModel(e.target.value === '' ? null : Number(e.target.value))}
          >
            <option value="">— не выбрана</option>
            {models.map((m: Model) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
          </select>
        </label>
      )}

      <div className="row">
        <label>
          Год
          <input
            type="number"
            inputMode="numeric"
            value={year}
            placeholder="2012"
            onChange={(e) => changeYear(e.target.value)}
          />
        </label>

        <label>
          Поколение
          <select
            value={generationId ?? ''}
            onChange={(e) =>
              setGenerationId(e.target.value === '' ? null : Number(e.target.value))
            }
            disabled={generations.length === 0}
          >
            <option value="">—</option>
            {generations.map((g: Generation) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      <label>
        VIN
        <input
          value={vin}
          onChange={(e) => setVin(e.target.value.toUpperCase())}
          placeholder="17 символов"
          autoCapitalize="characters"
          autoCorrect="off"
        />
      </label>
      {vinBad && (
        // Сервер VIN не сверяет, а ошибка в нём тихая: машину потом
        // не найти ни по документам, ни по запросу клиента.
        <p className="note note--error">VIN — 17 символов, без букв I, O и Q</p>
      )}

      <button type="button" className="button--ghost" onClick={() => setScanning(true)}>
        Сканировать VIN
      </button>

      <label>
        Поставка
        <select
          value={supplyId ?? ''}
          onChange={(e) => setSupplyId(e.target.value === '' ? null : Number(e.target.value))}
        >
          <option value="">не указана</option>
          {reference.supplies.map((s) => (
            <option key={s.id} value={s.id}>
              {s.number} · {s.supplierName ?? 'без поставщика'}
            </option>
          ))}
        </select>
      </label>

      <label>
        Примечание
        <input value={note} onChange={(e) => setNote(e.target.value)} />
      </label>

      {message !== null && <p className="note">{message}</p>}

      {!online && (
        <p className="note note--error">
          Нет связи. Машину нельзя завести без неё: пока её нет на сервере,
          детали вешать не на что.
        </p>
      )}

      <button
        type="button"
        disabled={!online || sending || brand === null || vinBad}
        onClick={() => void submit()}
      >
        {sending ? 'Заводим…' : 'Завести машину'}
      </button>

      {scanning && (
        <ScanOverlay
          hint="VIN в документах или на кузове"
          onScan={(text) => {
            setScanning(false);
            setVin(text.trim().toUpperCase());
          }}
          onClose={() => setScanning(false)}
        />
      )}

      <hr />

      <h3>Машины</h3>
      {donors.length === 0 ? (
        <p className="note">Машин пока нет.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Машина</th>
              <th>Состояние</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {donors.map((donor) => (
              <tr key={donor.id}>
                <td>{donorTitle(donor)}</td>
                <td>{statusTitle(donor.status)}</td>
                <td className="filter-row">
                  {donor.status === 'PURCHASED' && (
                    <button
                      type="button"
                      className="button--ghost"
                      disabled={busy || !online}
                      onClick={() => void toDismantling(donor.id)}
                    >
                      В разбор
                    </button>
                  )}
                  <button
                    type="button"
                    className="button--ghost"
                    onClick={() => setCostsOf(costsOf === donor.id ? null : donor.id)}
                  >
                    {costsOf === donor.id ? 'Свернуть' : 'Затраты'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="note">
        Деталь принимают только на машину в разборе: снятая с той, которую ещё
        везут, — ошибка выбора, а не работа. Поэтому купленную машину надо
        поставить в разбор, и до этого на приёмке её нет.
      </p>

      {costsOf !== null && donors.some((donor) => donor.id === costsOf) && (
        <DonorCosts
          donorId={costsOf}
          title={donorTitle(donors.find((donor) => donor.id === costsOf)!)}
        />
      )}

    </section>
  );

  async function load(): Promise<void> {
    setLoading(true);
    try {
      setCatalog(await refresh());
    } catch (cause) {
      setMessage(describe(cause, 'Справочник не загрузился'));
    } finally {
      setLoading(false);
    }
  }

  function pickBrand(picked: Brand): void {
    setBrand(picked);
    setBrandInput(picked.name);
    setModelId(null);
    setGenerationId(null);
  }

  function pickModel(id: number | null): void {
    setModelId(id);
    // Год уже введён — поколение подставится само, менять его руками незачем.
    setGenerationId(matchGeneration(id, year));
  }

  function changeYear(value: string): void {
    setYear(value);
    setGenerationId(matchGeneration(modelId, value));
  }

  /**
   * Подбирает поколение по году.
   *
   * <p>Год приёмщик знает из документов, а какое это поколение — вопрос,
   * на который он отвечать не должен. Ошибка тут тихая: деталь уедет
   * в объявление с чужой применимостью.
   */
  function matchGeneration(model: number | null, value: string): number | null {
    if (catalog === null || model === null || value.length !== 4) {
      return null;
    }
    const matched = generationForYear(generationsOf(catalog, model), Number(value));
    return matched?.id ?? null;
  }

  async function reloadDonors(): Promise<void> {
    try {
      setDonors(await listDonors());
    } catch {
      // Машины не догрузились — форма заведения от этого не ломается.
    }
  }

  async function toDismantling(id: number): Promise<void> {
    setBusy(true);
    try {
      await startDismantling(id);
      await reloadDonors();
      // Справочник приёмки берёт машины по состоянию: без обновления
      // поставленной в разбор машины на приёмке не появится.
      onChanged();
    } catch (cause) {
      setMessage(describe(cause, 'Машина не поставлена в разбор'));
    } finally {
      setBusy(false);
    }
  }

  async function submit(): Promise<void> {
    if (brand === null) {
      return;
    }
    setSending(true);
    setMessage(null);
    try {
      const created = await request<{ publicCode: string }>('/api/intake/donors', {
        method: 'POST',
        body: {
          brandId: brand.id,
          modelId,
          generationId,
          year: year === '' ? null : Number(year),
          vin: vin.trim() === '' ? null : vin.trim(),
          supplyId,
          note: note.trim() === '' ? null : note.trim(),
        },
      });

      setMessage(`Машина заведена: ${created.publicCode}`);
      onChanged();
      void reloadDonors();
      // Марку оставляем: партию однотипных машин заводят подряд.
      setModelId(null);
      setGenerationId(null);
      setYear('');
      setVin('');
      setNote('');
    } catch (cause) {
      setMessage(describe(cause, 'Машина не заведена'));
    } finally {
      setSending(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    return cause.status === 0 ? 'Нет связи с сервером' : cause.message;
  }
  return fallback;
}
