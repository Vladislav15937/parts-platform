import type { FeedLink } from '../publishing/feeds';
import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import { allKinds } from '../catalog/partNames';
import type { PartKind } from '../catalog/partNames';
import { loadCached, refresh } from '../catalog/vehicles';
import type { Brand } from '../catalog/vehicles';
import {
  feedUrl,
  CONDITIONS,
  countMatching,
  createFeed,
  filterSummary,
  listFeeds,
  rotateFeedUrl,
  setFilter,
  type Feed,
  type FeedFilter,
} from '../publishing/feeds';

/**
 * Выгрузки на площадки и отбор товара в каждую.
 *
 * <p>Выгрузок на одну площадку бывает несколько: у живого клиента пять прайсов
 * на Дром, разложенных по ценовым диапазонам, — у каждого свой прайс-лист
 * в кабинете площадки и своя цена размещения. Различаются они только отбором.
 *
 * <p><b>Пустое поле — «без ограничения», а не «ничего».</b> Так и написано
 * рядом с полями: выгрузка, у которой стёрли цену, отдаёт весь склад. Обратное
 * прочтение стоит дорого — пустой прайс площадка примет молча, и объявления
 * пропадут вместе с накопленными просмотрами.
 *
 * <p>Ссылка меняется отдельной кнопкой с предупреждением: смена останавливает
 * выгрузку до тех пор, пока техспециалист площадки не пропишет новую в кабинете
 * клиента, а это не минуты.
 */
/**
 * @param role кабинет заводит только владелец: ссылка на прайс открывает
 *             склад целиком, и раздавать это право управляющему рано
 */
export function FeedsScreen({ role }: { role: string }) {
  const [feeds, setFeeds] = useState<Feed[] | null>(null);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  // Справочники берутся целиком: видов сто семьдесят восемь, марок триста
  // восемь. Это не четыре тысячи моделей — те в отборе не участвуют вовсе,
  // и городить ради этого поиск с сервера незачем.
  const [kinds, setKinds] = useState<PartKind[]>([]);
  const [brands, setBrands] = useState<Brand[]>([]);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    listFeeds()
      .then((found) => {
        setFeeds(found);
        setError('');
      })
      .catch((cause) => setError(describe(cause, 'Выгрузки не загрузились')));
  }, []);

  useEffect(load, [load]);

  useEffect(() => {
    void listWarehouses()
      .then(setWarehouses)
      // Молча: без списка складов отбор по цене всё ещё работает.
      .catch(() => setWarehouses([]));

    void allKinds().then(setKinds).catch(() => setKinds([]));
    // Марки уже лежат в кэше справочника машин — он предзагружен ради
    // приёмки. Второй запрос за тем же был бы данью привычке.
    void loadCached()
      .then((cached) => (cached ? cached.brands : refresh().then((v) => v.brands)))
      .then(setBrands)
      .catch(() => setBrands([]));
  }, []);

  if (feeds === null) {
    return <p className="note">Загружаем выгрузки…</p>;
  }

  return (
    <section className="screen">
      <h2>Выгрузки</h2>
      <p className="note">
        Пустое поле отбора значит «без ограничения», а не «ничего»: такая
        выгрузка отдаёт весь склад.
      </p>

      {error && <p className="note note--error">{error}</p>}

      {feeds.length === 0 && (
        <p className="note">
          {role === 'OWNER'
            ? 'Выгрузок пока нет. Заведите кабинет — и появится постоянная ссылка на прайс.'
            : 'Выгрузок пока нет. Кабинет площадки заводит владелец.'}
        </p>
      )}

      {role === 'OWNER' && <NewFeed onCreated={load} />}

      <ul className="cards">
        {feeds.map((feed) => (
          <FeedCard
            key={feed.id}
            feed={feed}
            warehouses={warehouses}
            kinds={kinds}
            brands={brands}
            onChanged={load}
            onError={setError}
          />
        ))}
      </ul>
    </section>
  );
}

function FeedCard({
  feed,
  warehouses,
  kinds,
  brands,
  onChanged,
  onError,
}: {
  feed: Feed;
  warehouses: Warehouse[];
  kinds: PartKind[];
  brands: Brand[];
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  // Через String: с сервера цена приходит числом, и `?? ''` оставил бы
  // в состоянии число — а поле правится как строка.
  const [priceFrom, setPriceFrom] = useState(
    feed.priceFrom === null ? '' : String(feed.priceFrom));
  const [priceTo, setPriceTo] = useState(
    feed.priceTo === null ? '' : String(feed.priceTo));
  const [conditions, setConditions] = useState<string[]>(feed.conditions);
  const [warehouseIds, setWarehouseIds] = useState<number[]>(feed.warehouseIds);
  const [link, setLink] = useState<FeedLink | null>(null);
  const [busy, setBusy] = useState(false);
  const [matching, setMatching] = useState<number | null>(null);

  const [kindIds, setKindIds] = useState<number[]>(feed.kindIds);
  const [kindsExcluded, setKindsExcluded] = useState(feed.kindsExcluded);
  const [brandIds, setBrandIds] = useState<number[]>(feed.brandIds);
  const [brandsExcluded, setBrandsExcluded] = useState(feed.brandsExcluded);

  const current = (): FeedFilter => ({
    priceFrom: priceFrom.trim() === '' ? null : priceFrom.trim(),
    priceTo: priceTo.trim() === '' ? null : priceTo.trim(),
    conditions,
    warehouseIds,
    kindIds,
    kindsExcluded,
    brandIds,
    brandsExcluded,
  });

  async function save() {
    setBusy(true);
    try {
      await setFilter(feed.id, current());
      onChanged();
    } catch (cause) {
      onError(describe(cause, 'Отбор не сохранён'));
    } finally {
      setBusy(false);
    }
  }

  async function count() {
    setBusy(true);
    try {
      setMatching((await countMatching(current(), feed.productLine)).parts);
    } catch (cause) {
      onError(describe(cause, 'Посчитать не удалось'));
    } finally {
      setBusy(false);
    }
  }

  async function rotate() {
    setBusy(true);
    try {
      setLink(await rotateFeedUrl(feed.id));
      onChanged();
    } catch (cause) {
      onError(describe(cause, 'Ссылка не сменилась'));
    } finally {
      setBusy(false);
    }
  }

  function toggle<T>(list: T[], value: T): T[] {
    return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
  }

  return (
    <li className="card">
      <div className="order-head">
        <strong>{feed.title}</strong>
        <span>
          {feed.marketplace === 'AVITO' ? 'Авито' : 'Дром'}
          {/* Вид товара — не украшение: две выгрузки на одну площадку иначе
              различаются только содержимым файла, а его открывают раз в жизни. */}
          {feed.productLine === 'WHEEL' ? ' · шины и диски' : ''}
        </span>
      </div>
      <p className="muted">{filterSummary(feed)}</p>

      {feed.lastError && <p className="note note--error">{feed.lastError}</p>}

      <div className="filter-row">
        <label className="field">
          Цена от, ₽
          <input
            inputMode="decimal"
            value={priceFrom}
            placeholder="без границы"
            onChange={(e) => setPriceFrom(e.target.value)}
          />
        </label>
        <label className="field">
          Цена до, ₽
          <input
            inputMode="decimal"
            value={priceTo}
            placeholder="без границы"
            onChange={(e) => setPriceTo(e.target.value)}
          />
        </label>
      </div>

      <fieldset className="choices">
        <legend>Состояние — пусто значит любое</legend>
        {CONDITIONS.map((condition) => (
          <label key={condition.code}>
            <input
              type="checkbox"
              checked={conditions.includes(condition.code)}
              onChange={() => setConditions(toggle(conditions, condition.code))}
            />
            {condition.name}
          </label>
        ))}
      </fieldset>

      {warehouses.length > 1 && (
        <fieldset className="choices">
          <legend>Склады — пусто значит все</legend>
          {warehouses.map((warehouse) => (
            <label key={warehouse.id}>
              <input
                type="checkbox"
                checked={warehouseIds.includes(warehouse.id)}
                onChange={() => setWarehouseIds(toggle(warehouseIds, warehouse.id))}
              />
              {warehouse.name}
            </label>
          ))}
        </fieldset>
      )}

      {/* Вид детали и марка машины есть только у запчасти. У колеса part_kind
          не заполнен вовсе, а марка — это Dunlop, а не Toyota; прайс колёс
          их и не отбирает. Показанный тут отбор был бы обещанием, которого
          нет: владелец сузил бы выгрузку, а уехал бы весь склад колёс. */}
      {feed.productLine === 'PART' && (
        <>
          <Picker
            title="Наименования"
            options={kinds.map((k) => ({ id: k.id, name: k.name }))}
            chosen={kindIds}
            excluded={kindsExcluded}
            onChosen={setKindIds}
            onExcluded={setKindsExcluded}
          />

          <Picker
            title="Марки"
            options={brands.map((b) => ({ id: b.id, name: b.nameRu ?? b.name }))}
            chosen={brandIds}
            excluded={brandsExcluded}
            onChosen={setBrandIds}
            onExcluded={setBrandsExcluded}
          />
        </>
      )}

      <div className="filter-row">
        <button type="button" className="button--ghost" disabled={busy}
                onClick={() => void count()}>
          Посчитать
        </button>
        <button type="button" disabled={busy} onClick={() => void save()}>
          Сохранить отбор
        </button>
      </div>

      {matching !== null && (
        <p className={matching === 0 ? 'note note--error' : 'note'}>
          {matching === 0
            ? 'С таким отбором в прайс не попадёт ничего. Площадка примет пустой '
              + 'прайс молча, и объявления пропадут вместе с просмотрами'
            : `В прайс попадёт позиций: ${matching}`}
        </p>
      )}

      {/* Ссылка спрашивается при раскрытии, а не показывается только сразу
          после выдачи. Иначе узнать её нельзя вовсе: владелец завёл прайс,
          отдал адрес техспециалисту площадки, а через неделю адрес спросили
          снова — и посмотреть его негде. Единственной кнопкой была «Сменить
          ссылку», которая, как честно написано рядом, выгрузку останавливает:
          чтобы узнать ссылку, приходилось её сломать. */}
      <details onToggle={(e) => {
        if ((e.target as HTMLDetailsElement).open && link === null && feed.hasFeed) {
          void feedUrl(feed.id).then(setLink).catch(() => {});
        }
      }}>
        <summary>Ссылка для площадки</summary>
        <p className="note">
          Смена ссылки останавливает выгрузку: новую в кабинет площадки
          прописывает её техспециалист руками, и до этого прайс забирать
          будет неоткуда.
        </p>
        {link !== null && link.path !== null && (
          <>
            {/* Полный адрес, а не путь: его копируют и отдают человеку
                на той стороне, и по «/feeds/drom/…» тот не сходит никуда. */}
            <p className="muted">{link.url ?? link.path}</p>
            {/* Скачать — потому что заливка файлом руками это не запасной
                путь, а единственный быстрый: забор по ссылке идёт раз
                в трое суток, и до него новая деталь на площадке
                не появится. Ссылкой, а не кнопкой с запросом: файл
                на двадцать мегабайт качает браузер, показывая ход. */}
            <p>
              <a href={link.path} download>Скачать файл прайса</a>
            </p>
          </>
        )}
        <button type="button" className="button--ghost" disabled={busy}
                onClick={() => void rotate()}>
          {feed.hasFeed ? 'Сменить ссылку' : 'Выдать ссылку'}
        </button>
      </details>
    </li>
  );
}

/**
 * Выбор из справочника: что попадает в выгрузку или что из неё исключено.
 *
 * <p><b>Направление названо словом, а не галочкой «инвертировать».</b>
 * «Только эти» и «кроме этих» — противоположные решения, и перепутать их
 * значит выгрузить ровно то, что выгружать не хотели.
 *
 * <p>Список показывается не весь: сто семьдесят восемь строк подряд никто
 * не читает. Поиск идёт по уже загруженному справочнику — он статичный
 * и маленький, запрос на каждую букву тут был бы данью привычке.
 *
 * <p>Выбранное показывается отдельно и всегда: иначе, стерев поиск, владелец
 * перестаёт видеть, что вообще выбрано, — а это и есть причина, по которой
 * прайс короче склада.
 */
function Picker({
  title,
  options,
  chosen,
  excluded,
  onChosen,
  onExcluded,
}: {
  title: string;
  options: Array<{ id: number; name: string }>;
  chosen: number[];
  excluded: boolean;
  onChosen: (ids: number[]) => void;
  onExcluded: (value: boolean) => void;
}) {
  const [query, setQuery] = useState('');

  const found = query.trim() === ''
    ? []
    : options
        .filter((o) => o.name.toLowerCase().includes(query.trim().toLowerCase()))
        .slice(0, 12);

  const chosenNames = chosen.map((id) => ({
    id,
    // Название могло исчезнуть из справочника между релизами: показываем
    // хотя бы номер, а не пустую строку, иначе выбранное выглядит пропавшим.
    name: options.find((o) => o.id === id)?.name ?? `№ ${id}`,
  }));

  return (
    <fieldset className="picker">
      <legend>{title}</legend>

      {chosen.length > 0 && (
        <label className="field">
          Направление
          <select
            value={excluded ? 'exclude' : 'include'}
            onChange={(e) => onExcluded(e.target.value === 'exclude')}
          >
            <option value="include">только эти</option>
            <option value="exclude">все, кроме этих</option>
          </select>
        </label>
      )}

      <label className="field">
        Найти
        <input
          value={query}
          placeholder="начните вводить"
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>

      {found.length > 0 && (
        <ul className="picker__found">
          {found.map((option) => (
            <li key={option.id}>
              <button
                type="button"
                className="button--ghost"
                disabled={chosen.includes(option.id)}
                onClick={() => onChosen([...chosen, option.id])}
              >
                {option.name}
              </button>
            </li>
          ))}
        </ul>
      )}

      {chosen.length === 0 ? (
        <p className="muted">не ограничено</p>
      ) : (
        <ul className="picker__chosen">
          {chosenNames.map((option) => (
            <li key={option.id}>
              {option.name}
              <button
                type="button"
                className="button--ghost"
                onClick={() => onChosen(chosen.filter((id) => id !== option.id))}
              >
                убрать
              </button>
            </li>
          ))}
        </ul>
      )}
    </fieldset>
  );
}

/**
 * Заведение кабинета площадки.
 *
 * <p>Только Дром: генератор фида Авито написан, но отдавать его наружу
 * нечем, и предлагать в форме площадку, выгрузка на которую не работает, —
 * это обещание, которого нет.
 *
 * <p>Номер прайс-листа необязателен: постоянная ссылка работает без него,
 * он нужен дельтам по API — а ключ к ним Дром выдаёт по заявке.
 */
function NewFeed({ onCreated }: { onCreated: () => void }) {
  const [title, setTitle] = useState('');
  const [packetId, setPacketId] = useState('');
  const [productLine, setProductLine] = useState<'PART' | 'WHEEL'>('PART');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  return (
    <div className="card">
      <h3>Завести выгрузку</h3>
      <p className="note">
        Выгрузок на одну площадку бывает несколько: у каждой свой отбор,
        свой прайс-лист в кабинете и своя цена размещения.
      </p>

      <label>
        Название
        <input
          value={title}
          placeholder="например, Дром: основной"
          onChange={(e) => setTitle(e.target.value)}
        />
      </label>

      {/* Площадка требует держать шины отдельным прайс-листом, и требование
          по делу: у шины свои поля — маркировка, сезон, шиповка, износ, —
          а объявление «Шина 195/65 R15» среди запчастей уезжает в чужую
          категорию, откуда его снимают. */}
      <label>
        Что выгружаем
        <select
          value={productLine}
          onChange={(e) => setProductLine(e.target.value === 'WHEEL' ? 'WHEEL' : 'PART')}
        >
          <option value="PART">Запчасти</option>
          <option value="WHEEL">Шины и диски</option>
        </select>
      </label>

      <label>
        Номер прайс-листа в кабинете
        <input
          value={packetId}
          placeholder="необязательно"
          onChange={(e) => setPacketId(e.target.value)}
        />
      </label>

      {error !== '' && <p className="note note--error">{error}</p>}

      <button type="button" disabled={busy || title.trim() === ''} onClick={() => void save()}>
        {busy ? 'Заводим…' : 'Завести кабинет Дрома'}
      </button>
    </div>
  );

  async function save(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await createFeed(title.trim(), packetId, productLine);
      setTitle('');
      setPacketId('');
      onCreated();
    } catch (cause) {
      setError(describe(cause, 'Кабинет не заведён'));
    } finally {
      setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}
