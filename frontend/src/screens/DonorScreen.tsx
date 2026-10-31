import { Fragment, useEffect, useRef, useState } from 'react';
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
  DonorCard,
  DonorInfoFields,
  DonorRestylingField,
  DonorSpecFields,
  DonorVideoField,
  EMPTY_EXTRA,
  EMPTY_SPEC,
  extraBad,
  extraToFields,
  mileageBad,
  specToFields,
  type DonorExtra,
  type DonorSpec,
  type VocabularyState,
} from './DonorCard';
import { DonorPhotos } from './DonorPhotos';
import {
  donorTitle,
  donorVocabulary,
  moveDonor,
  listDonors,
  nextDonorNumber,
  registerSupply,
  startDismantling,
  statusTitle,
  type DonorEntry,
} from '../intake/donors';
import { ScanOverlay } from '../scan/ScanOverlay';
import { shown } from '../ui/plural';
import { SupplyList } from './SupplyList';
import { useMounted } from '../ui/useMounted';

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
  /** Заводить ожидаемый товар по поставке может владелец (задача 0170). */
  canExpect?: boolean;
  /** «N запчастей — Посмотреть»: открыть склад с отбором по машине (задача 0364). */
  onDonorParts?: (donorCode: string) => void;
  /**
   * Право «Записывать затраты по машине»: «Стоимость» ложится закупкой
   * в журнал затрат (ответ владельца 31.10.2026), и без права сервер её
   * отобьёт — поле такому сотруднику не показывается вовсе.
   */
  canRecordCost?: boolean;
}

export function DonorScreen({
  reference, online, onChanged, canExpect = false, onDonorParts, canRecordCost = false,
}: Props) {
  // Затраты вносятся по уже заведённой машине: до её появления вкладывать
  // не во что, а после — покупка, эвакуатор и разбор идут отдельными
  // платежами и в разные дни.
  const [costsOf, setCostsOf] = useState<number | null>(null);
  // Снимки машины — там же, где затраты, и по той же причине: машину
  // фотографируют один раз, а в объявления её снимки уходят у тех
  // наименований, которые владелец отметил у выгрузки.
  const [photosOf, setPhotosOf] = useState<number | null>(null);
  // Карточка машины — под своей строкой, как затраты и снимки (задача 0364).
  const [cardOf, setCardOf] = useState<number | null>(null);
  const [vocabulary, setVocabulary] = useState<VocabularyState>('loading');
  // Где стоит машина — правится прямо в строке: значение и поле ввода
  // в одной клетке, иначе владелец ищет, куда делась строка после нажатия.
  const [movingId, setMovingId] = useState<number | null>(null);
  const [place, setPlace] = useState('');
  const [donors, setDonors] = useState<DonorEntry[]>([]);
  const [query, setQuery] = useState('');
  const [supplyNumber, setSupplyNumber] = useState('');
  const [supplyKind, setSupplyKind] = useState('CONTAINER');
  const [supplier, setSupplier] = useState('');
  const [busy, setBusy] = useState(false);
  const [catalog, setCatalog] = useState<VehicleCatalog | null>(null);
  const [loading, setLoading] = useState(true);

  const [brand, setBrand] = useState<Brand | null>(null);
  const [brandInput, setBrandInput] = useState('');
  const [modelId, setModelId] = useState<number | null>(null);
  const [year, setYear] = useState('');
  const [generationId, setGenerationId] = useState<number | null>(null);
  const [bodyCode, setBodyCode] = useState('');
  const [engineCode, setEngineCode] = useState('');
  const [vin, setVin] = useState('');
  const [scanning, setScanning] = useState(false);
  const [supplyId, setSupplyId] = useState<number | null>(null);
  const [spec, setSpec] = useState<DonorSpec>(EMPTY_SPEC);
  const [extra, setExtra] = useState<DonorExtra>(EMPTY_EXTRA);
  // «Стоимость» только при заведении: дальше покупка — запись журнала затрат,
  // и правят её там, а не в форме машины.
  const [cost, setCost] = useState('');
  // Предложенный номер донора: наибольший плюс один (задача 0364). Не
  // приехал — поле пустое, и номер выдаст сервер при заведении.
  const [suggested, setSuggested] = useState<number | null>(null);
  const suggestedRef = useRef<number | null>(null);

  const [sending, setSending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  // Список машин — с сервера, а не из офлайн-справочника: тот отдаёт
  // только те, с которых можно снимать (в разборе и разобранные),
  // и только что купленной машины в нём нет.
  useEffect(() => {
    void reloadDonors();
  }, []);

  useEffect(() => {
    void suggestNumber();
  }, []);

  useEffect(() => {
    void donorVocabulary()
      .then((words) => { if (mounted.current) setVocabulary(words); })
      .catch(() => { if (mounted.current) setVocabulary('failed'); });
  }, [mounted]);

  useEffect(() => {
    void (async () => {
      const cached = await loadCached();
      if (!mounted.current) return;
      setCatalog(cached);
      setLoading(false);
      // Обновляем молча и только при связи: экран обязан быть рабочим
      // немедленно, а справочник меняется с релизами, не за день.
      if (navigator.onLine && isStale(cached)) {
        try {
          const fresh = await refresh();
          if (mounted.current) setCatalog(fresh);
        } catch {
          // Не обновилось — работаем на том, что есть.
        }
      }
    })();
  }, [mounted]);

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

  // Ищем по тому же, что видно в строке, плюс VIN: владелец помнит машину
  // по своему номеру («261»), по марке с моделью или по заметке
  // («Синий маркер!!!») — по нашему внутреннему коду не помнит никто.
  const needle = query.trim().toLowerCase();
  const found = needle === ''
    ? donors
    : donors.filter((donor) =>
        // Ищется по тому же, что видно в строке, плюс VIN. Место попало
        // сюда вместе с колонкой: «покажи всё, что стоит во втором ряду» —
        // вопрос, который задают, стоя на площадке.
        `${donorTitle(donor)} ${donor.location ?? ''} ${donor.vin ?? ''}`
          .toLowerCase().includes(needle));

  const models = modelsOf(catalog, brand?.id ?? null);
  const generations = generationsOf(catalog, modelId);
  const suggestions = brand === null ? suggestBrands(catalog.brands, brandInput) : [];
  const vinTouched = vin.trim() !== '';
  const vinBad = vinTouched && !isValidVin(vin);

  return (
    <section className="card">
      <h2>Машина</h2>

      <DonorInfoFields extra={extra} onChange={setExtra} vocabulary={vocabulary}
                       suggested={suggested} />

      {canRecordCost && (
        <>
          <label>
            Стоимость
            <input
              inputMode="decimal"
              value={cost}
              placeholder="необязательно"
              onChange={(e) => setCost(e.target.value.replace(',', '.'))}
            />
          </label>
          {costBad(cost) ? (
            <p className="note note--error">Стоимость — число не меньше нуля</p>
          ) : (
            <p className="note">Запишется в затраты машины покупкой</p>
          )}
        </>
      )}

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
              chooseGeneration(e.target.value === '' ? null : Number(e.target.value))
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

      {/* Рестайлинг — свободным текстом: справочник его не знает
          (`is_restyling` пуст у всех поколений). Сбрасывается сменой
          поколения, как у ориентира. */}
      <DonorRestylingField extra={extra} onChange={setExtra} />

      {/* Кузов и двигатель приёмщик списывает с документов машины. По ним
          подбирают деталь по телефону — «подойдёт ли на JZX110» — и они же
          уходят в прайс отдельными полями. Свободный ввод, а не выбор:
          у модели, которой в справочнике нет, выбирать не из чего. */}
      <div className="row">
        <label>
          Кузов
          <input
            value={bodyCode}
            onChange={(e) => setBodyCode(e.target.value.toUpperCase())}
            placeholder="ACV40"
            autoCapitalize="characters"
            autoCorrect="off"
          />
        </label>

        <label>
          Двигатель
          <input
            value={engineCode}
            onChange={(e) => setEngineCode(e.target.value.toUpperCase())}
            placeholder="2AZ-FE"
            autoCapitalize="characters"
            autoCorrect="off"
          />
        </label>
      </div>

      {/* Руль, КПП, привод, цвет, комплектация и пробег сервер принимал
          с самого начала, а на форме их не было — и «Комплектация» каждой
          снятой детали оставалась пустой (задача 0364). */}
      <DonorSpecFields spec={spec} onChange={setSpec} vocabulary={vocabulary} />

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

      <DonorVideoField extra={extra} onChange={setExtra} />

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

      {/*
        * Поставка заводится здесь же, где выбирается.
        *
        * `POST /api/intake/supplies` был написан с самого начала, и звать
        * его было некому: список поставок приезжал справочником, а новую
        * завести было нельзя ниоткуда. У переехавшего клиента их
        * восемнадцать — все из переноса, — и следующий пришедший контейнер
        * записать было бы не на что: приёмщик выбрал бы «не указана»,
        * и связь детали с партией потерялась бы навсегда.
        *
        * Рядом с выбором, а не отдельным разделом: контейнер и машины
        * приходят вместе, и заводит их один человек за один заход.
        */}
      <details>
        <summary>Завести поставку</summary>
        <div className="row">
          <label className="field">
            Номер
            <input
              value={supplyNumber}
              placeholder="18"
              onChange={(e) => setSupplyNumber(e.target.value)}
            />
          </label>
          <label className="field">
            Вид
            <select value={supplyKind} onChange={(e) => setSupplyKind(e.target.value)}>
              <option value="CONTAINER">Контейнер</option>
              <option value="PURCHASE">Закупка</option>
              <option value="OTHER">Поставка</option>
            </select>
          </label>
          <label className="field">
            Поставщик
            <input
              value={supplier}
              placeholder="необязательно"
              onChange={(e) => setSupplier(e.target.value)}
            />
          </label>
        </div>
        <button
          type="button"
          disabled={sending || !online || supplyNumber.trim() === ''}
          onClick={() => void createSupply()}
        >
          Завести поставку
        </button>
      </details>

      {message !== null && <p className="note">{message}</p>}

      {!online && (
        <p className="note note--error">
          Нет связи. Машину нельзя завести без неё: пока её нет на сервере,
          детали вешать не на что.
        </p>
      )}

      <button
        type="button"
        disabled={!online || sending || brand === null || vinBad || mileageBad(spec.mileage)
                  || extraBad(extra) || (canRecordCost && costBad(cost))}
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
        <>
        {/*
          * Поиск по списку, а не прокрутка.
          *
          * У переехавшего клиента 441 машина, и список идёт простынёй
          * в 27 801 пиксель — тридцать четыре экрана подряд. Владелец
          * приходит сюда за одной машиной: положить на неё эвакуатор
          * или перевести в разбор, — а найти её мог только глазами.
          * Ровно та же болезнь, что с правкой списком в семьсот страниц
          * и переносом снимков в девятьсот нажатий: возможность есть,
          * воспользоваться нельзя.
          *
          * Отбор на клиенте: все машины уже загружены одним запросом,
          * и лишний поход на сервер тут ничего не уточнит.
          */}
        <input
          type="search"
          value={query}
          placeholder="Найти машину — номер, марка, модель, заметка, место, VIN"
          onChange={(e) => setQuery(e.target.value)}
        />
        {query.trim() !== '' && (
          // Обрезанный список говорит, что он обрезан: иначе владелец
          // читает выдачу как весь свой автопарк.
          <p className="note">
            {found.length === 0
              ? `Ничего не найдено среди ${donors.length} — очистите поиск`
              : `Показано ${shown(found.length, donors.length)}`}
          </p>
        )}
        {/* Прокручивается таблица внутри своей обёртки, а не страница: у машины
            длинное имя («Toyota Land Cruiser Prado 2008 · №261») и две кнопки
            в строке, и без обёртки вбок уезжал весь экран. */}
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Машина</th>
                <th>Состояние</th>
                <th>Где стоит</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {found.map((donor) => (
                <Fragment key={donor.id}>
                <tr>
                  <td>{donorTitle(donor)}</td>
                  <td>{statusTitle(donor.status)}</td>
                  {/* Где стоит машина. Поле заполнялось только запросом к API
                      и не показывалось нигде — на площадке в полсотни машин
                      это единственный способ её найти, и держалось оно
                      в голове того, кто её ставил. */}
                  <td>
                    {movingId === donor.id ? (
                      <span className="filter-row">
                        <input
                          autoFocus
                          aria-label={`Где стоит ${donorTitle(donor)}`}
                          value={place}
                          placeholder="ряд 2, место 14"
                          onChange={(e) => setPlace(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') void moveTo(donor.id);
                            if (e.key === 'Escape') setMovingId(null);
                          }}
                        />
                        <button
                          type="button"
                          disabled={busy || !online}
                          onClick={() => void moveTo(donor.id)}
                        >
                          Сохранить
                        </button>
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="button--ghost"
                        onClick={() => {
                          setMovingId(donor.id);
                          setPlace(donor.location ?? '');
                        }}
                      >
                        {donor.location !== null && donor.location !== ''
                          ? donor.location
                          : 'не указано'}
                      </button>
                    )}
                  </td>
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
                      onClick={() => setCardOf(cardOf === donor.id ? null : donor.id)}
                    >
                      {cardOf === donor.id ? 'Свернуть' : 'Карточка'}
                    </button>
                    <button
                      type="button"
                      className="button--ghost"
                      onClick={() => setCostsOf(costsOf === donor.id ? null : donor.id)}
                    >
                      {costsOf === donor.id ? 'Свернуть' : 'Затраты'}
                    </button>
                    <button
                      type="button"
                      className="button--ghost"
                      onClick={() => setPhotosOf(photosOf === donor.id ? null : donor.id)}
                    >
                      {photosOf === donor.id ? 'Свернуть' : 'Фото'}
                    </button>
                  </td>
                </tr>
                {/* Затраты раскрываются под своей же строкой. Пока блок стоял
                    после таблицы, у клиента с 441 машиной он открывался
                    за одиннадцать экранов вниз — замерено: строка на 17 995
                    пикселе, блок на 27 756. Владелец нажимал «Затраты»
                    и не видел ничего, кроме сменившейся надписи на кнопке. */}
                {cardOf === donor.id && (
                  <tr>
                    <td colSpan={4}>
                      <DonorCard donorId={donor.id} vocabulary={vocabulary} online={online}
                                 onParts={onDonorParts}
                                 onSaved={() => { void reloadDonors(); onChanged(); }} />
                    </td>
                  </tr>
                )}
                {costsOf === donor.id && (
                  <tr>
                    <td colSpan={4}>
                      <DonorCosts donorId={donor.id} title={donorTitle(donor)} />
                    </td>
                  </tr>
                )}
                {/* Снимки — под своей же строкой, по той же причине, что
                    и затраты: блок после таблицы открывался бы у клиента
                    с 441 машиной за одиннадцать экранов вниз. */}
                {photosOf === donor.id && (
                  <tr>
                    <td colSpan={4}>
                      <DonorPhotos donorId={donor.id} title={donorTitle(donor)} />
                    </td>
                  </tr>
                )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
        </>
      )}
      {/* «Только в разборе» было неправдой: справочник приёмки отдаёт
          и разобранные — вернуться за забытой мелочью через неделю после
          закрытия разбора обычное дело, и это закреплено тестом
          IntakeReferenceServiceTest. А у переехавшего клиента разобраны
          все 440 машин: прочитав прежний текст, он решил бы, что принимать
          на них нельзя вовсе, и заводил бы детали без машины. */}
      <hr />

      <SupplyList supplies={reference.supplies} online={online} onChanged={onChanged}
                  canExpect={canExpect} />

      <hr />

      <p className="note">
        Деталь принимают на машину, которую разбирают или уже разобрали:
        вернуться за забытой мелочью через неделю — обычное дело. А купленной
        и той, что ещё в пути, на приёмке нет: снятая с них деталь — ошибка
        выбора, а не работа. Поэтому купленную ставят в разбор.
      </p>

    </section>
  );

  async function load(): Promise<void> {
    setLoading(true);
    try {
      const fresh = await refresh();
      if (mounted.current) setCatalog(fresh);
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Справочник не загрузился'));
    } finally {
      if (mounted.current) setLoading(false);
    }
  }

  function pickBrand(picked: Brand): void {
    setBrand(picked);
    setBrandInput(picked.name);
    setModelId(null);
    chooseGeneration(null);
  }

  function pickModel(id: number | null): void {
    setModelId(id);
    // Год уже введён — поколение подставится само, менять его руками незачем.
    chooseGeneration(matchGeneration(id, year));
  }

  function changeYear(value: string): void {
    setYear(value);
    chooseGeneration(matchGeneration(modelId, value));
  }

  /**
   * Рестайлинг принадлежит поколению: сменилось поколение — вписанный
   * рестайлинг про другую машину, и ориентир его стирает («Будет удалено»).
   */
  function chooseGeneration(id: number | null): void {
    if (id !== generationId) {
      setExtra((was) => ({ ...was, restyling: '' }));
    }
    setGenerationId(id);
  }

  /**
   * Подставляет следующий номер — если человек не вписал свой. Свой не
   * трогаем: перечитывание после заведения не должно стирать набранное.
   */
  async function suggestNumber(): Promise<void> {
    try {
      const next = await nextDonorNumber();
      if (!mounted.current) return;
      const was = suggestedRef.current;
      suggestedRef.current = next;
      setSuggested(next);
      setExtra((form) => (form.number === '' || form.number === String(was)
        ? { ...form, number: String(next) } : form));
    } catch {
      // Номер не подсказан — поле пустое, сервер выдаст следующий сам.
      if (mounted.current) setSuggested(null);
    }
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

  async function createSupply(): Promise<void> {
    setSending(true);
    setMessage(null);
    try {
      const created = await registerSupply(
        supplyNumber.trim(), supplyKind, supplier.trim() === '' ? null : supplier.trim());
      if (mounted.current) {
        setSupplyNumber('');
        setSupplier('');
        // Справочник приёмки перечитывается: без этого заведённая поставка
        // не появится в списке ни здесь, ни у приёмщика на телефоне.
        onChanged();
        setMessage(`Поставка «${created.number}» заведена — её уже можно выбрать.`);
      }
    } catch (cause) {
      if (mounted.current) {
        setMessage(cause instanceof ApiError ? cause.message : 'Поставку завести не удалось');
      }
    } finally {
      if (mounted.current) setSending(false);
    }
  }

  async function reloadDonors(): Promise<void> {
    try {
      const found = await listDonors();
      if (mounted.current) setDonors(found);
    } catch {
      // Машины не догрузились — форма заведения от этого не ломается.
    }
  }

  /**
   * Переставляет машину. Список перечитывается целиком: место видно в строке,
   * и оставить на экране прежнее значит показать владельцу площадку,
   * которой уже нет.
   */
  async function moveTo(id: number): Promise<void> {
    setBusy(true);
    setMessage(null);
    try {
      await moveDonor(id, place.trim());
      if (!mounted.current) return;
      setMovingId(null);
      await reloadDonors();
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Переставить машину не удалось'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function toDismantling(id: number): Promise<void> {
    setBusy(true);
    try {
      await startDismantling(id);
      if (!mounted.current) return;
      await reloadDonors();
      // Справочник приёмки берёт машины по состоянию: без обновления
      // поставленной в разбор машины на приёмке не появится.
      onChanged();
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Машина не поставлена в разбор'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function submit(): Promise<void> {
    if (brand === null) {
      return;
    }
    setSending(true);
    setMessage(null);
    // Номер уезжает, только если его изменили: предложенный — подсмотр,
    // и две открытые формы показали бы одно число; выдаёт его сервер.
    const typed = extra.number.trim();
    const number = typed === '' || Number(typed) === suggested ? null : Number(typed);
    try {
      const created = await request<{ number: number }>('/api/intake/donors', {
        method: 'POST',
        body: {
          brandId: brand.id,
          modelId,
          generationId,
          year: year === '' ? null : Number(year),
          bodyCode: bodyCode.trim() === '' ? null : bodyCode.trim(),
          engineCode: engineCode.trim() === '' ? null : engineCode.trim(),
          vin: vin.trim() === '' ? null : vin.trim(),
          ...specToFields(spec),
          supplyId,
          ...extraToFields(extra),
          number,
          cost: canRecordCost && cost.trim() !== '' ? Number(cost) : null,
        },
      });

      if (!mounted.current) return;
      setMessage(`Машина заведена: №${created.number}`);
      onChanged();
      void reloadDonors();
      // Марку оставляем: партию однотипных машин заводят подряд.
      setModelId(null);
      setGenerationId(null);
      setYear('');
      setBodyCode('');
      setEngineCode('');
      setVin('');
      setSpec(EMPTY_SPEC);
      setExtra(EMPTY_EXTRA);
      setCost('');
      void suggestNumber();
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Машина не заведена'));
    } finally {
      if (mounted.current) setSending(false);
    }
  }
}

/** Пусто — затраты не заводятся; иначе число не меньше нуля, как у журнала затрат. */
function costBad(value: string): boolean {
  if (value.trim() === '') return false;
  const parsed = Number(value);
  return !Number.isFinite(parsed) || parsed < 0;
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    return cause.status === 0 ? 'Нет связи с сервером' : cause.message;
  }
  return fallback;
}
