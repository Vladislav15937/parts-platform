import type { FeedLink } from '../publishing/feeds';
import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listWarehouses } from '../organization/warehouses';
import type { Warehouse } from '../organization/warehouses';
import { allKinds } from '../catalog/partNames';
import type { PartKind } from '../catalog/partNames';
import { loadCached, refresh } from '../catalog/vehicles';
import type { Brand } from '../catalog/vehicles';
import { ColumnMenu } from './ColumnMenu';
import {
  COLUMNS,
  FILTER_EMPTY,
  FILTER_PRESENT,
  columnValues,
} from '../inventory/catalog';
import { WHEEL_COLUMNS, wheelValues } from '../inventory/wheels';
import {
  feedUrl,
  filterableColumns,
  CONDITIONS,
  countMatching,
  createFeed,
  filterSummary,
  listFeeds,
  rotateFeedUrl,
  setCredentials,
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
    // Не удалось — так и говорим. Ошибка при загрузке оставляет состояние
    // пустым, и до разметки с сообщением дело не доходило: экран показывал
    // «Загружаем выгрузки» бесконечно, а причина — «сессия кончилась» —
    // лежала рядом непоказанной.
    return error === ''
      ? <p className="note">Загружаем выгрузки…</p>
      : <p className="note note--error">{error}</p>;
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
  const [secret, setSecret] = useState('');
  const [busy, setBusy] = useState(false);
  const [matching, setMatching] = useState<number | null>(null);

  // Свои условия владельца — те же колонки, какими он смотрит склад.
  const [columns, setColumns] = useState<Record<string, string>>(
    feed.filterColumns ?? {});
  const [words, setWords] = useState<Record<string, string>>(feed.filterWords ?? {});
  const [addFor, setAddFor] = useState('');
  const [typed, setTyped] = useState('');
  const [menuAt, setMenuAt] = useState<{ left: number; top: number } | null>(null);

  const wheels = feed.productLine === 'WHEEL';
  const known = wheels
    ? WHEEL_COLUMNS.map((c) => ({ key: c.key, title: c.title }))
    : COLUMNS.map((c) => ({ key: c.key, title: c.title }));
  const titleOf = (key: string) => known.find((c) => c.key === key)?.title ?? key;

  /*
   * Список отбираемых колонок спрашивается у сервера, а не повторяется здесь.
   *
   * Повторённый, он разошёлся бы с ним на первой же новой колонке — и экран
   * предлагал бы отбор, которого нет: сервер отвечает «по этой колонке отбор
   * не делается», а владелец видит те же тридцать пять тысяч позиций
   * и решает, что отбор сломан. Ровно этим уже болело меню колонки.
   */
  const [filterable, setFilterable] = useState<string[] | null>(null);
  useEffect(() => {
    let alive = true;
    void filterableColumns(feed.productLine)
      .then((list) => { if (alive) setFilterable(list); })
      .catch(() => { if (alive) setFilterable([]); });
    return () => { alive = false; };
  }, [feed.productLine]);

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
    columns,
    words,
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

  const menu = addFor !== '' && menuAt !== null ? (
    <ColumnMenu
      column={addFor}
      at={menuAt}
      chosen={columns[addFor]}
      filterable
      sortable={undefined}
      sort=""
      desc={false}
      values={wheels ? wheelValues : columnValues}
      empty={FILTER_EMPTY}
      present={FILTER_PRESENT}
      onSort={() => {}}
      onPick={(value) => {
        const chosen = { ...columns };
        if (value === null) {
          delete chosen[addFor];
        } else {
          chosen[addFor] = value;
        }
        setColumns(chosen);
        setMenuAt(null);
        setAddFor('');
      }}
      onClose={() => setMenuAt(null)}
    />
  ) : null;

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

      {/* Свои условия владельца.
          Шесть зашитых условий покрывают не всё, а каждое седьмое означало бы
          релиз: миграция, генератор, счётчик, экран. Витрина склада к этому
          времени отбирает по двадцати девяти колонкам, и выгрузка берёт тот же
          механизм — те же колонки, те же значения, тот же разбор «пусто».
          Второй список колонок рядом с витринным разошёлся бы с ним
          на первой правке. */}
      <fieldset className="choices">
        <legend>Свои условия — пусто значит без ограничения</legend>

        {/* Тем же значком, что и на витрине склада: условия там снимаются
            нажатием на сам значок, и второй вид того же элемента заставлял бы
            владельца заново догадываться, как его убрать. */}
        {Object.entries(columns).map(([key, value]) => (
          <button
            key={`c-${key}`}
            type="button"
            className="crumb"
            onClick={() => {
              const left = { ...columns };
              delete left[key];
              setColumns(left);
            }}
          >
            {titleOf(key)}: {value} ✕
          </button>
        ))}
        {Object.entries(words).map(([key, value]) => (
          <button
            key={`w-${key}`}
            type="button"
            className="crumb"
            onClick={() => {
              const left = { ...words };
              delete left[key];
              setWords(left);
            }}
          >
            {titleOf(key)}: «{value}» ✕
          </button>
        ))}

        <div className="filter-row">
          <label className="field">
            Колонка
            <select value={addFor} onChange={(e) => {
              setAddFor(e.target.value);
              setTyped('');
              setMenuAt(null);
            }}>
              <option value="">— выберите колонку —</option>
              {known
                .filter((c) => (filterable ?? []).includes(c.key))
                .map((c) => (
                  <option key={c.key} value={c.key}>{c.title}</option>
                ))}
            </select>
          </label>

          {/* Два способа, как на витрине: вбитое руками ищется вхождением
              («Nok» находит Nokian), выбранное из списка — точным равенством.
              Это разные вопросы, и различать их магией в значении нельзя. */}
          <label className="field">
            Содержит
            <input
              value={typed}
              placeholder="часть значения"
              disabled={addFor === ''}
              onChange={(e) => setTyped(e.target.value)}
            />
          </label>

          <button
            type="button"
            className="button--ghost"
            disabled={addFor === '' || typed.trim() === ''}
            onClick={() => {
              setWords({ ...words, [addFor]: typed.trim() });
              setAddFor('');
              setTyped('');
            }}
          >
            Добавить
          </button>

          <button
            type="button"
            className="button--ghost"
            disabled={addFor === ''}
            onClick={(e) => {
              // Без прокрутки: список значений рисуется `position: fixed`,
              // и прибавленный scrollY уводит его за нижний край экрана —
              // кнопка нажата, а ответа на ней нет.
              const box = (e.target as HTMLElement).getBoundingClientRect();
              setMenuAt({ left: box.left, top: box.bottom });
            }}
          >
            Выбрать значение
          </button>
        </div>

        {filterable !== null && filterable.length === 0 && (
          <p className="muted">Список колонок не прочитан — обновите страницу</p>
        )}
      </fieldset>

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
      {menu}

      {/*
        * Ключ синхронизации вводится здесь, и до этого его нельзя было
        * ввести нигде.
        *
        * Экран рядом честно писал «ключ к ним Дром выдаёт по заявке»,
        * а поля не было: `PUT /credentials` не звала ни одна строка
        * фронтенда. Без ключа дельты не уходят вовсе — принятая деталь,
        * подорожавшая или проданная, ждёт полного забора прайса, то есть
        * до трёх суток на бесплатном размещении. Заметить это нельзя:
        * очередь разгребается, `publication_log` пуст, и всё выглядит
        * работающим.
        *
        * Поле всегда пустое: прочитать ключ нельзя ни одним эндпоинтом,
        * и это часть защиты, а не недоделка. Состояние показывает
        * `hasCredentials`.
        */}
      <details>
        <summary>Ключ синхронизации {feed.hasCredentials ? '· задан' : '· не задан'}</summary>
        <p className="note">
          {feed.hasCredentials
            ? 'Ключ хранится зашифрованным и наружу не отдаётся: показать его нельзя, можно только заменить.'
            : 'Без ключа дельты по API не уходят: цена и остаток обновятся у площадки только с полным прайсом, а его забирают раз в трое суток.'}
        </p>
        <p className="note">
          Ключ выдаёт поддержка Дрома по обращению — он один на кабинет,
          выглядит как UUID и в кабинете площадки нигде не показывается.
          Обновление по API включают отдельно каждому прайс-листу.
        </p>
        <label>
          Ключ
          <input
            type="password"
            value={secret}
            autoComplete="off"
            placeholder="00000000-0000-0000-0000-000000000000"
            onChange={(e) => setSecret(e.target.value)}
          />
        </label>
        <button
          type="button"
          disabled={busy || secret.trim() === ''}
          onClick={() => void saveSecret()}
        >
          {feed.hasCredentials ? 'Заменить ключ' : 'Сохранить ключ'}
        </button>
      </details>
    </li>
  );

  async function saveSecret(): Promise<void> {
    setBusy(true);
    try {
      await setCredentials(feed.id, secret.trim());
      // Поле чистим сразу: ключ прочитать нельзя, и оставленный в поле
      // он выглядел бы как «мы его вам показываем».
      setSecret('');
      onChanged();
    } catch (cause) {
      onError(describe(cause, 'Ключ не сохранён'));
    } finally {
      setBusy(false);
    }
  }
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
