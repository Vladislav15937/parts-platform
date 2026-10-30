import { StockCount, hasHeld } from '../inventory/StockCount';
import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import {
  COLUMNS,
  columnValues,
  FILTER_EMPTY,
  FILTER_PRESENT,
  loadCatalog,
  bulkNotice,
  loadPhotos,
  exportUrl,
  DEFAULT_VISIBLE,
  conditionAside,
  defaultQuery,
  loadView,
  saveView,
  withoutFilters,
  withDefaultSort,
  sortOfColumn,
  warehouseSort,
  hasFilters,
  isSorted,
  viewKey,
  vehicleLabel,
  NO_VEHICLE,
  type CatalogPage,
  type CatalogQuery,
  type CatalogView,
  type CatalogRow,
  type PartPhoto,
} from '../inventory/catalog';
import { PartCard } from './PartCard';
import { ApplicabilityList } from './ApplicabilityList';
import { useMounted } from '../ui/useMounted';
import { BulkEditForm } from './BulkEditForm';
import { BulkMoveForm } from './BulkMoveForm';
import { count, goods } from '../ui/plural';
import { ColumnMenu } from './ColumnMenu';
import { VehiclePicker } from './VehiclePicker';
import type { Rights } from '../access/rights';
import { LockedAction } from '../ui/LockedAction';
import { CatalogLabelDialog } from './CatalogLabelDialog';
import { LabelSheet } from '../labels/LabelSheet';
import { LABEL_SIZE_NOTE, type Label } from '../labels/labels';
import { plural } from '../ui/plural';

/**
 * Витрина склада: таблица товаров, как её видит владелец.
 *
 * <p>Собрана по устройству прежней системы клиента: те же колонки, тот же
 * набор фильтров, настройка видимости, сортировка по колонке и счётчик.
 *
 * <p><b>Колонок больше двадцати, но показываются не все.</b> Разом это
 * горизонтальная простыня, в которой не найти цену. Выбор владельца
 * запоминается: настройка таблицы — работа на несколько минут, и терять её
 * при каждом заходе значит не дать ею пользоваться.
 *
 * <p><b>Колонки складов приходят с сервера.</b> У клиента их два, у другого
 * будет пять, и остаток по каждому — своя колонка. Знать это заранее экран
 * не может.
 */
const SIZE = 50;

/**
 * Сколько позиций отбора печатается этикетками разом (задача 0371).
 *
 * <p>Весь отбор едет страницами по 200 (предел сервера), и на тридцать пять
 * тысяч это сто восемьдесят запросов и лист в десятки тысяч наклеек, который
 * браузер не нарисует. Приёмка контейнера — десятки и сотни позиций; тысяча
 * — пять запросов. Больше — кнопка гаснет и просит сузить отбор.
 */
const WHOLE_PRINT_LIMIT = 1000;
const WHOLE_PRINT_PAGE = 200;

/** Название колонки для чипа отбора: ключ ничего не говорит владельцу. */
function columnTitle(key: string): string {
  return COLUMNS.find((c) => c.key === key)?.title ?? key;
}

export function CatalogScreen({ rights, donorFilter = null, onDonorFilterUsed }: {
  rights: Rights;
  /**
   * Открыт с карточки машины («N запчастей — Посмотреть», задача 0364):
   * склад с отбором по её номеру поверх запомненной настройки.
   */
  donorFilter?: string | null;
  onDonorFilterUsed?: () => void;
}) {
  const [page, setPage] = useState<CatalogPage | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [visible, setVisible] = useState<string[]>(DEFAULT_VISIBLE);
  const [settings, setSettings] = useState(false);
  const [query, setQuery] = useState<CatalogQuery>(() => defaultQuery(SIZE));
  /*
   * Настройка приехала — можно грузить склад.
   *
   * Без этого признака экран сначала показал бы склад умолчанием, а через
   * мгновение перерисовал его сохранённым отбором: владелец успевает
   * прочитать первую выдачу и решить, что отбор слетел. И лишний запрос
   * по тридцати пяти тысячам позиций на каждое открытие.
   */
  const [restored, setRestored] = useState(false);
  const [search, setSearch] = useState('');
  const [picking, setPicking] = useState(false);
  // Снимки показываются по наведению, накладкой поверх таблицы.
  // Не вставкой строки: вставленная сдвигает всё ниже, и строка, на которую
  // человек вёл курсор, уезжает из-под него.
  const [hovered, setHovered] = useState<number | null>(null);
  // Где показывать накладку. Считается от миниатюры, а не задаётся стилями:
  // таблица прокручивается по горизонтали, и absolute внутри неё обрезается
  // контейнером — накладка превращалась в белую полоску сбоку.
  const [at, setAt] = useState<{ left: number; top: number } | null>(null);
  const [photos, setPhotos] = useState<PartPhoto[]>([]);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();
  // Карточка позиции — по нажатию на строку.
  const [card, setCard] = useState<CatalogRow | null>(null);
  // Режим правки списком: флажки в строках и форма над таблицей. Отдельным
  // режимом, а не всегда, потому что нажатие по строке в обычном режиме
  // открывает карточку — и промах по флажку менял бы не то.
  const [selecting, setSelecting] = useState(false);
  const [chosen, setChosen] = useState<number[]>([]);
  /**
   * Выбран весь отбор, а не отмеченные строки.
   *
   * <p>Отметить можно только видимое, а видно пятьдесят строк из тридцати
   * пяти тысяч. После переезда без колонки «Выгружать» включить публикацию
   * надо всему складу — постранично это семьсот семнадцать заходов, и
   * до тех пор прайс уезжает пустым.
   */
  const [whole, setWhole] = useState(false);
  const [editing, setEditing] = useState(false);
  // «Применимость подробно» — список позиций с автором и временем (0363).
  const [fitList, setFitList] = useState(false);
  // Перевозка пачкой — свой режим формы, отдельно от правки: кладовщик
  // видит эту кнопку, а «Изменить» (цена, секция) — нет, роль другая.
  const [movingBulk, setMovingBulk] = useState(false);
  // Какая колонка сейчас набирается и какая показывает меню: открытых
  // больше одной быть не может — они перекрывают друг друга.
  const [typing, setTyping] = useState<string | null>(null);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  // Печать этикеток выбранного (задача 0371): окно режимов, строки для него
  // (весь отбор догружается) и лист, отданный в печать.
  const [labelling, setLabelling] = useState(false);
  const [labelRows, setLabelRows] = useState<CatalogRow[] | null>(null);
  const [labelFailure, setLabelFailure] = useState<string | null>(null);
  const [sheet, setSheet] = useState<Label[] | null>(null);
  /*
   * Строки, уже виденные на страницах, — по номеру.
   *
   * Отметка переживает переход по страницам (`chosen` хранит номера), а строки
   * в `page` — только текущей: без этого отмеченное на соседней странице
   * ушло бы из печати молча.
   */
  const seen = useRef(new Map<number, CatalogRow>());

  // Печать после отрисовки листа, а не в обработчике: window.print()
  // останавливает страницу, и вызванный раньше, он снял бы пустой лист.
  useEffect(() => {
    if (sheet !== null) window.print();
  }, [sheet]);

  /*
   * Отбор предлагается только там, где он есть.
   *
   * Список отбираемых колонок приезжает с сервера вместе со страницей:
   * повторённый здесь, он разошёлся бы с серверным на первой же новой
   * колонке — и разошёлся бы молча. Пока меню открывалось у любой колонки,
   * по превью, состоянию и комплектации оно предлагало отбор, которого нет:
   * сервер отвечал «по этой колонке отбор не делается», отказ проглатывался,
   * и владелец видел те же тридцать пять тысяч строк.
   */
  const canFilter = (key: string | null): boolean =>
    key !== null && (page?.filterable ?? []).includes(key);

  const [menuAt, setMenuAt] = useState<{ left: number; top: number } | null>(null);

  function showPhotos(id: number, target: HTMLElement) {
    if (hovered === id) {
      return;
    }
    const box = target.getBoundingClientRect();
    setAt({ left: box.right + 8, top: box.top - 4 });
    setHovered(id);
    setPhotos([]);
    // Ссылки подписанные и короткоживущие — берутся при показе, а не заранее
    // на все тридцать пять тысяч строк.
    void loadPhotos(id)
      .then((found) => { if (mounted.current) setPhotos(found); })
      .catch(() => { if (mounted.current) setPhotos([]); });
  }

  const load = useCallback((next: CatalogQuery) => {
    loadCatalog(next)
      .then((found) => {
        if (mounted.current) {
          for (const row of found.rows) seen.current.set(row.id, row);
          setPage(found);
          setError('');
        }
      })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Склад не загрузился'));
      });
  }, [mounted]);

  // Настройка экрана живёт на сервере, за сотрудником: «даже если он закрыл
  // браузер, выключил компьютер, открыл в другом браузере или из другого
  // места» (решение владельца продукта от 11 сентября 2026). localStorage,
  // в котором до этой задачи лежал состав колонок, не даёт ни одного
  // из трёх последних случаев.
  // Снимок последней записанной настройки: перелистывание страниц меняет
  // отбор ни на что, и писать в базу на каждую стрелку незачем.
  const saved = useRef('');

  useEffect(() => {
    void loadView(SIZE)
      .then((view) => {
        if (mounted.current) {
          const shown = donorFilter === null ? view : donorView(view, donorFilter);
          setQuery(shown.query);
          setVisible(shown.visible);
          saved.current = viewKey(view);
          if (shown !== view) {
            // Запоминается, как и тот же отбор, поставленный с карточки
            // позиции: настройка — то, что сейчас на экране.
            saved.current = viewKey(shown);
            void saveView(shown);
            onDonorFilterUsed?.();
          }
          setRestored(true);
        }
      });
  }, [mounted]);

  useEffect(() => {
    if (restored) load(query);
  }, [restored, load, query]);

  /** Помнит ровно то, что сейчас на экране: порядок, отборы, колонки. */
  function remember(nextQuery: CatalogQuery, nextVisible: string[]) {
    // До восстановления писать нечего и опасно: нажатие, успевшее случиться
    // раньше ответа сервера, записало бы поверх сохранённого умолчание —
    // то есть стёрло бы настройку, которую как раз просили помнить.
    if (!restored) {
      return;
    }
    const view = { query: nextQuery, visible: nextVisible };
    const key = viewKey(view);
    if (key === saved.current) {
      return;
    }
    saved.current = key;
    void saveView(view);
  }

  function change(patch: Partial<CatalogQuery>) {
    // Любая смена отбора возвращает на первую страницу: остаться на сорок
    // второй после нового фильтра значит увидеть пустоту и решить,
    // что ничего не нашлось.
    // Курсор живёт ровно один переход: смена отбора или прыжок по номеру
    // страницы его сбрасывают, иначе он указывал бы в прежнюю выдачу.
    const next = { ...query, after: undefined, ...patch, page: patch.page ?? 0 };
    setQuery(next);
    // Страница и курсор не запоминаются: «всё» сказано про то, как разложен
    // экран, а не про то, где человек остановился.
    remember(next, visible);
  }

  function toggleColumn(key: string) {
    const next = visible.includes(key)
      ? visible.filter((k) => k !== key)
      : [...visible, key];
    setVisible(next);
    remember(query, next);
  }

  // Постоянные колонки не зависят от сохранённого выбора: настройка могла
  // быть записана до их появления.
  const columns = COLUMNS.filter((c) => c.fixed === true || visible.includes(c.key));
  const warehouses = page?.warehouses ?? [];
  const pages = page === null ? 0 : Math.ceil(page.total / SIZE);
  const canLabel = rights.has('part.cell');
  const tooManyToLabel = whole && (page?.total ?? 0) > WHOLE_PRINT_LIMIT;

  /** Окно печати: строки отмеченного сразу, весь отбор — догрузкой. */
  function startLabels() {
    setLabelling(true);
    setLabelFailure(null);
    if (!whole) {
      setLabelRows(chosen
        .map((id) => seen.current.get(id))
        .filter((row): row is CatalogRow => row !== undefined));
      return;
    }
    setLabelRows(null);
    const total = page?.total ?? 0;
    const requests = Array.from(
      { length: Math.ceil(total / WHOLE_PRINT_PAGE) },
      (_, at) => loadCatalog({ ...query, after: undefined, page: at, size: WHOLE_PRINT_PAGE }),
    );
    void Promise.all(requests)
      .then((loaded) => {
        if (mounted.current) setLabelRows(loaded.flatMap((one) => one.rows));
      })
      .catch((cause) => {
        if (mounted.current) setLabelFailure(describe(cause, 'Позиции отбора не загрузились'));
      });
  }

  /*
   * Лист этикеток — вместо экрана, а не под таблицей.
   *
   * Печать прячет интерфейс видимостью, а не убирает его: таблица в пятьдесят
   * строк оставалась бы в раскладке, и рулон получил бы за этикетками
   * десяток пустых страниц — по наклейке на каждую. Выделение при этом
   * живо: «Вернуться к складу» показывает ту же страницу с теми же отметками.
   */
  if (sheet !== null) {
    return (
      <section className="screen screen--wide">
        <div className="no-print">
          <h2>Печать этикеток</h2>
          <p className="note">
            К печати: {count(sheet.length)}{' '}
            {plural(sheet.length, 'этикетка', 'этикетки', 'этикеток')}. {LABEL_SIZE_NOTE}
          </p>
          <div className="filter-row">
            {/* Диалог печати закрывают и по ошибке, и чтобы поправить размер:
                повторить надо отсюда, где лист перед глазами. */}
            <button type="button" onClick={() => window.print()}>Печать</button>
            <button type="button" className="button--ghost" onClick={() => setSheet(null)}>
              Вернуться к складу
            </button>
          </div>
        </div>
        <LabelSheet labels={sheet} />
      </section>
    );
  }

  return (
    <section className="screen screen--wide">
      <h2>
        Склад{' '}
        <span className="muted counter">
          {page === null ? '' : goods(page.total)}
        </span>
      </h2>

      {error && <p className="note note--error">{error}</p>}
      {notice && <p className="note">{notice}</p>}

      <div className="filter-row filter-row--search">
        <label className="field">
          Поиск
          <input
            value={search}
            placeholder="Номер товара, наименование или номер детали"
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && change({ q: search })}
          />
        </label>
        <button type="button" onClick={() => change({ q: search })}>Найти</button>
      </div>

      <fieldset className="choices">
        <legend>Показывать</legend>
        <label>
          <input
            type="checkbox"
            checked={query.reserved}
            onChange={(e) => change({ reserved: e.target.checked })}
          />
          зарезервированные
        </label>
        <label>
          <input
            type="checkbox"
            checked={query.missing}
            onChange={(e) => change({ missing: e.target.checked })}
          />
          отсутствующие
        </label>
        {warehouses.map((warehouse) => (
          <label key={warehouse.id}>
            <input
              type="checkbox"
              checked={query.warehouses.includes(warehouse.id)}
              onChange={() =>
                change({
                  warehouses: query.warehouses.includes(warehouse.id)
                    ? query.warehouses.filter((id) => id !== warehouse.id)
                    : [...query.warehouses, warehouse.id],
                })
              }
            />
            {warehouse.name}
          </label>
        ))}
      </fieldset>

      <div className="filter-row">
        <button type="button" className="button--ghost" onClick={() => setPicking(true)}>
          Подбор по машине
        </button>
        {query.vehicle.brandId !== null && (
          <button
            type="button"
            className="crumb"
            onClick={() => change({ vehicle: NO_VEHICLE })}
          >
            {vehicleLabel(query.vehicle)} ✕
          </button>
        )}

        {/* Отборы колонок — здесь, а не только в шапке таблицы. Снимаются они
            в меню колонки, а при пустой выдаче таблицы нет вовсе: отбор
            действует, объяснения ему нет нигде, и снять его нечем — остаётся
            перезагрузить страницу. Та же беда, что со скрытой колонкой
            «Номер донора»: почему из тридцати пяти тысяч осталось ноль,
            на экране не написано. */}
        {Object.entries(query.columns).map(([key, value]) => (
          <button
            key={`col-${key}`}
            type="button"
            className="crumb"
            onClick={() => {
              const columns = { ...query.columns };
              delete columns[key];
              change({ columns, page: 0 });
            }}
          >
            {columnTitle(key)}: {value} ✕
          </button>
        ))}

        {Object.entries(query.words).map(([key, value]) => (
          <button
            key={`word-${key}`}
            type="button"
            className="crumb"
            onClick={() => {
              const words = { ...query.words };
              delete words[key];
              change({ words, page: 0 });
            }}
          >
            {columnTitle(key)}: «{value}» ✕
          </button>
        ))}
        {/* Сброс — не удобство, а условие безопасности самой памяти: отбор
            теперь переживает и браузер, и устройство, а значит владелец
            открывает склад через неделю, видит «Ничего не найдено» и решает,
            что склад пуст или сломан. Кнопка стоит здесь, а не в шапке
            таблицы, ровно потому, что при пустой выдаче таблицы нет вовсе.

            Две кнопки, а не одна: «сбросить отбор» и «сбросить сортировку» —
            разные желания. Первое возвращает весь склад, второе — порядок
            по умолчанию. Показываются, только когда есть что сбрасывать:
            кнопка, которая ничего не меняет, хуже отсутствующей. */}
        {hasFilters(query) && (
          <button
            type="button"
            className="button--ghost"
            onClick={() => {
              // И набранный поиск тоже: «нажимающий „сбросить“ хочет увидеть
              // весь склад» (решение владельца продукта от 11 сентября 2026).
              setSearch('');
              change(withoutFilters(query));
            }}
          >
            Сбросить отбор
          </button>
        )}
        {isSorted(query) && (
          <button
            type="button"
            className="button--ghost"
            onClick={() => change(withDefaultSort(query))}
          >
            Сбросить сортировку
          </button>
        )}
        <button type="button" className="button--ghost" onClick={() => setSettings(!settings)}>
          Настроить таблицу
        </button>
        {rights.has('part.edit') ? (
          <button
            type="button"
            className="button--ghost"
            onClick={() => {
              setSelecting(!selecting);
              setChosen([]);
              setWhole(false);
              setEditing(false);
              setMovingBulk(false);
            }}
          >
            {selecting ? 'Выйти из правки' : 'Правка списком'}
          </button>
        ) : !rights.has('stock.move') && !canLabel && (
          <LockedAction permission="part.edit" label="Правка списком" />
        )}
        {rights.has('part.edit') ? (
          <button type="button" className="button--ghost" onClick={() => setFitList(!fitList)}>
            {fitList ? 'Скрыть применимость' : 'Применимость подробно'}
          </button>
        ) : (
          <LockedAction permission="part.edit" label="Применимость подробно" />
        )}
        {fitList && <ApplicabilityList canEdit={rights.has('part.edit')}
                                       onClose={() => setFitList(false)} />}
        {/* Без правки карточек, но с перевозкой или печатью этикеток
            (кладовщик до 0044) — свой вход в тот же режим отметки строк:
            деталь у него в руках. */}
        {!rights.has('part.edit') && (rights.has('stock.move') || canLabel) && (
          <button
            type="button"
            className="button--ghost"
            onClick={() => {
              setSelecting(!selecting);
              setChosen([]);
              setWhole(false);
              setMovingBulk(false);
            }}
          >
            {selecting ? 'Отменить' : 'Отметить позиции'}
          </button>
        )}
        {/* Ссылкой, а не кнопкой с запросом: файл на двенадцать мегабайт
            качает браузер, показывая ход, и вкладка при этом жива.

            Не всем, кому открыт экран: в файле вся номенклатура разом.
            Прячется здесь и отбивается на сервере — спрятанная кнопка
            при открытом адресе это не защита, а закрытый адрес при видимой
            кнопке — обещание, которое не выполняется. */}
        {rights.has('export.table') ? (
          <a className="button--ghost" href={exportUrl(query)} download>
            Скачать таблицу
          </a>
        ) : (
          <LockedAction permission="export.table" label="Скачать таблицу" />
        )}
      </div>

      {/* Накладка со снимками — вне таблицы: внутри её обрезал бы контейнер
          с горизонтальной прокруткой. */}
      {hovered !== null && at !== null && photos.length > 0 && (
        <div className="thumb-popover" style={{ left: at.left, top: at.top }}>
          {photos.map((photo) => (
            <img key={photo.photoId} src={photo.url} alt="" />
          ))}
        </div>
      )}

      {menuFor !== null && menuAt !== null && (
        <ColumnMenu
          column={menuFor}
          at={menuAt}
          chosen={query.columns[menuFor]}
          filterable={canFilter(menuFor)}
          sortable={sortOfColumn(menuFor)}
          sort={query.sort}
          desc={query.desc}
          onSort={(desc) => {
            const sort = sortOfColumn(menuFor);
            if (sort !== undefined) change({ sort, desc, page: 0 });
            setMenuFor(null);
          }}
          onPick={(value) => {
            const chosenColumns = { ...query.columns };
            if (value === null) delete chosenColumns[menuFor];
            else chosenColumns[menuFor] = value;
            change({ columns: chosenColumns, page: 0 });
            setMenuFor(null);
          }}
          values={columnValues}
          empty={FILTER_EMPTY}
          present={FILTER_PRESENT}
          onClose={() => setMenuFor(null)}
        />
      )}

      {card !== null && (
        <PartCard
          row={card}
          warehouses={warehouses}
          rights={rights}
          onClose={() => setCard(null)}
          onChanged={() => {
            setCard(null);
            load(query);
          }}
          // «Что ещё сняли с этой машины» — обычный отбор по колонке
          // «Номер донора», а не отдельный экран: список тот же самый,
          // с теми же колонками и той же выгрузкой.
          //
          // Колонка при этом включается, даже если была скрыта: иначе
          // отбор невидим — из трёхсот семидесяти пяти позиций остаётся две,
          // и почему, на экране не написано нигде, а снять его нечем.
          onDonorParts={(donorCode) => {
            setCard(null);
            if (!visible.includes('donor')) {
              toggleColumn('donor');
            }
            change({ columns: { ...query.columns, donor: donorCode } });
          }}
        />
      )}

      {picking && (
        <VehiclePicker
          chosen={query.vehicle}
          onPick={(vehicle) => {
            setPicking(false);
            change({ vehicle });
          }}
          onClose={() => setPicking(false)}
        />
      )}

      {/* Панель показывается на весь режим правки, а не с первого выбранного:
          появившись, она сдвигает таблицу вниз, и следующее нажатие попадает
          в соседнюю строку — снимая то, что было только что выбрано. Та же
          ловушка, что с накладкой снимков. Поймано живым прогоном. */}
      {selecting && !editing && !movingBulk && (
        <div className="filter-row">
          <span className="note">
            {whole ? `Выбран весь отбор: ${count(page?.total ?? 0)}`
              : chosen.length === 0 ? 'Отметьте позиции'
              : `Выбрано ${chosen.length}`}
          </span>
          {rights.has('part.edit') && (
            <button type="button" disabled={!whole && chosen.length === 0}
                    onClick={() => setEditing(true)}>
              Изменить
            </button>
          )}
          {/* Перевозка не берёт «весь отбор»: пачка в тридцать пять тысяч
              позиций через два склада — это уже не то, что отмечают
              на витрине руками, а отдельная задача. Отмечают до пары сотен. */}
          {!whole && rights.has('stock.move') && (
            <button type="button" className="button--ghost"
                    disabled={chosen.length === 0}
                    onClick={() => setMovingBulk(true)}>
              Перевезти
            </button>
          )}
          {/* Печать этикеток отмеченного (задача 0371): приёмка контейнера —
              десятки строк подряд, и искать их на «Этикетках» по одной значит
              двадцать поисков на ряд из двадцати деталей. */}
          {canLabel ? (
            <button type="button"
                    disabled={(!whole && chosen.length === 0) || tooManyToLabel}
                    onClick={startLabels}>
              Напечатать этикетки
            </button>
          ) : (
            <LockedAction permission="part.cell" label="Напечатать этикетки" />
          )}
          {canLabel && tooManyToLabel && (
            <span className="note">
              Этикетки печатаются пачкой до {count(WHOLE_PRINT_LIMIT)} товаров — сузьте отбор.
            </span>
          )}
          {/* Весь отбор — единственный способ тронуть больше страницы.
              Кнопка стоит рядом с отметками, а не в настройках: нужна она
              ровно тогда, когда владелец уже понял, что отмечать придётся
              семьсот страниц. Перевозке она не служит — только правке
              и печати этикеток. */}
          {(rights.has('part.edit') || canLabel) && !whole
            && page !== null && page.total > page.rows.length && (
            <button type="button" className="button--ghost"
                    onClick={() => { setWhole(true); setChosen([]); }}>
              Выбрать весь отбор ({count(page.total)})
            </button>
          )}
          {/* «Отменить выделение» — слово ориентира (задача 0371). */}
          <button type="button" className="button--ghost"
                  disabled={!whole && chosen.length === 0}
                  onClick={() => { setChosen([]); setWhole(false); }}>
            Отменить выделение
          </button>
        </div>
      )}

      {labelling && (
        <CatalogLabelDialog
          total={whole ? (page?.total ?? 0) : chosen.length}
          rows={labelRows}
          failure={labelFailure}
          onSend={(labels) => {
            setLabelling(false);
            setSheet(labels);
          }}
          onCancel={() => setLabelling(false)}
        />
      )}

      {editing && (
        <BulkEditForm
          partIds={chosen}
          whole={whole ? query : undefined}
          count={whole ? (page?.total ?? 0) : chosen.length}
          onSaved={(result) => {
            setEditing(false);
            setChosen([]);
            setWhole(false);
            setNotice(bulkNotice(result));
            load(query);
          }}
          onCancel={() => setEditing(false)}
        />
      )}

      {movingBulk && (
        <BulkMoveForm
          rows={(page?.rows ?? []).filter((r) => chosen.includes(r.id))}
          warehouses={warehouses}
          onDone={(notice) => {
            setMovingBulk(false);
            setSelecting(false);
            setChosen([]);
            setNotice(notice);
            load(query);
          }}
          onCancel={() => setMovingBulk(false)}
        />
      )}

      {settings && (
        <fieldset className="choices">
          <legend>Колонки</legend>
          {/* Постоянные колонки в список не идут: отключить их нельзя,
              а флажок, который не работает, хуже отсутствующего. */}
          {COLUMNS.filter((c) => c.fixed !== true).map((column) => (
            <label key={column.key}>
              <input
                type="checkbox"
                checked={visible.includes(column.key)}
                onChange={() => toggleColumn(column.key)}
              />
              {column.title}
            </label>
          ))}
        </fieldset>
      )}

      {page === null ? (
        // Не «Загружаем…» вечно, когда загрузка провалилась: причина
        // показана выше, и висящее рядом ожидание ей противоречит.
        error !== '' ? null : <p className="note">Загружаем…</p>
      ) : page.rows.length === 0 ? (
        <p className="note">Ничего не найдено.</p>
      ) : (
        <div className="table-scroll">
          <table className="report">
            <thead>
              <tr>
                {selecting && (
                  <th className="th--check">
                    <input
                      type="checkbox"
                      checked={chosen.length === page.rows.length && page.rows.length > 0}
                      onChange={(e) => setChosen(
                        e.target.checked ? page.rows.map((r) => r.id) : [])}
                    />
                  </th>
                )}
                {columns.map((column) => (
                  <th
                    key={column.key}
                    className={
                      [column.numeric ? 'num' : '',
                       query.columns[column.key] !== undefined
                         || query.words[column.key] !== undefined ? 'th--filtered' : '']
                        .filter((c) => c !== '').join(' ') || undefined
                    }
                  >
                    {/* Нажатие на название даёт поле ввода, список значений
                        и сортировка — на стрелке. Так в кабинете, и так
                        быстрее: набрать три буквы легче, чем искать значение
                        глазами в списке из сотни. */}
                    {typing === column.key ? (
                      <input
                        autoFocus
                        className="th__input"
                        defaultValue={query.words[column.key] ?? ''}
                        onKeyDown={(e) => {
                          if (e.key === 'Escape') setTyping(null);
                          if (e.key !== 'Enter') return;
                          const words = { ...query.words };
                          const typed = e.currentTarget.value.trim();
                          if (typed === '') delete words[column.key];
                          else words[column.key] = typed;
                          change({ words, page: 0 });
                          setTyping(null);
                        }}
                        onBlur={() => setTyping(null)}
                      />
                    ) : (
                      <span
                        className="th__title"
                        onClick={canFilter(column.key)
                          ? () => setTyping(column.key)
                          : undefined}
                        style={{ cursor: canFilter(column.key) ? 'text' : 'default' }}
                      >
                        {column.title}
                      </span>
                    )}
                    {/* Стрелка — только если меню есть что предложить:
                        отбор по этой колонке или сортировку. Пустое меню
                        обещает действие, которого нет. */}
                    {(canFilter(column.key) || column.sort !== undefined) && (
                    <button
                      type="button"
                      className="th__menu"
                      onClick={(e) => {
                        const box = e.currentTarget.getBoundingClientRect();
                        setMenuAt({ left: box.left, top: box.bottom });
                        setMenuFor(menuFor === column.key ? null : column.key);
                      }}
                    >
                      {column.sort === query.sort ? (query.desc ? '↓' : '↑') : '▾'}
                    </button>
                    )}
                    {query.columns[column.key] !== undefined && (
                      <div className="th__value">«{query.columns[column.key]}»</div>
                    )}
                    {query.words[column.key] !== undefined && (
                      <div className="th__value">~{query.words[column.key]}</div>
                    )}
                  </th>
                ))}
                {/* Колонка склада сортируется, как и остальные (задача 0367),
                    по свободному остатку — крупному числу клетки «0 (1)».
                    Отбора у неё нет: склады отбираются флажками «Показывать». */}
                {warehouses.map((warehouse) => {
                  const sort = warehouseSort(warehouse.id);
                  return (
                    <th key={warehouse.id} className="num">
                      <span className="th__title">{warehouse.name}</span>
                      <button
                        type="button"
                        className="th__menu"
                        aria-label={`Сортировка: ${warehouse.name}`}
                        onClick={(e) => {
                          const box = e.currentTarget.getBoundingClientRect();
                          setMenuAt({ left: box.left, top: box.bottom });
                          setMenuFor(menuFor === sort ? null : sort);
                        }}
                      >
                        {sort === query.sort ? (query.desc ? '↓' : '↑') : '▾'}
                      </button>
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {page.rows.map((row) => (
                <Fragment key={row.id}>
                <tr
                  className={[
                    'row--clickable',
                    chosen.includes(row.id) ? 'is-chosen' : '',
                    hasHeld(row) ? 'row--held' : '',
                  ].filter(Boolean).join(' ')}
                  onClick={() => (selecting
                    ? setChosen(chosen.includes(row.id)
                        ? chosen.filter((id) => id !== row.id)
                        : [...chosen, row.id])
                    : setCard(row))}
                >
                  {selecting && (
                    <td className="th--check">
                      <input type="checkbox" readOnly checked={chosen.includes(row.id)} />
                    </td>
                  )}
                  {columns.map((column) => (
                    <td
                      key={column.key}
                      className={column.numeric ? 'num' : undefined}
                      title={column.hint?.(row)}
                    >
                      {column.image
                        ? column.image(row) !== null && (
                            <span
                              className="thumb-hover"
                              onMouseEnter={(e) => showPhotos(row.id, e.currentTarget)}
                              onMouseLeave={() => setHovered(null)}
                            >
                              <img
                                className="thumb"
                                src={column.image(row) ?? ''}
                                alt=""
                                loading="lazy"
                              />
                            </span>
                          )
                        : column.value(row)}
                      {column.key === 'title' && conditionAside(row, visible) !== null && (
                        <span className="title-aside">{conditionAside(row, visible)}</span>
                      )}
                    </td>
                  ))}
                  {warehouses.map((warehouse) => (
                    <td key={warehouse.id} className="num">
                      {/* Прочерк, а не ноль: «на этом складе её нет»
                          и «есть ноль штук» читаются одинаково, но первое
                          понятнее. */}
                      <StockCount stock={row.stock} reserved={row.reserved} warehouseId={warehouse.id} />
                    </td>
                  ))}
                </tr>
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {pages > 1 && (
        <div className="filter-row">
          <button
            type="button"
            className="button--ghost"
            disabled={query.page === 0}
            onClick={() => change({ page: query.page - 1 })}
          >
            ←
          </button>
          <span className="note">
            {query.page + 1} из {pages.toLocaleString('ru-RU')}
          </span>
          <button
            type="button"
            className="button--ghost"
            disabled={query.page + 1 >= pages}
            onClick={() => change({
              page: query.page + 1,
              // Вперёд — от последней строки: серверу не придётся читать
              // и выбрасывать всё, что до неё. Назад и при прыжке курсора
              // нет, и страница берётся отступом, как раньше.
              // Только по колонкам без пустых значений — номер позиции
              // и номер товара: по колонке с NULL курсор не построить,
              // сравнение с NULL не отвечает ни «больше», ни «меньше».
              after: page === null ? undefined
                : query.sort === 'number'
                  ? String(page.rows[page.rows.length - 1]?.number ?? '')
                  : query.sort === 'code'
                    ? (page.rows[page.rows.length - 1]?.code ?? undefined)
                    : undefined,
            })}
          >
            →
          </button>
        </div>
      )}
    </section>
  );
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message ? cause.message : fallback;
}

/**
 * Склад, открытый с карточки машины: всё, что с неё снято (задача 0364).
 *
 * <p>Прочие отборы сняты, а отсутствующие показаны: плитка считает каждую
 * позицию, снятую с машины, — и проданную тоже, — и число на ней обязано
 * совпасть с числом строк, к которым она ведёт. Колонка «Номер донора»
 * включается, иначе отбор невидим и снять его нечем (запись про
 * `onDonorParts` у карточки позиции). Порядок остаётся запомненный.
 */
export function donorView(view: CatalogView, donorCode: string): CatalogView {
  return {
    query: { ...withoutFilters(view.query), missing: true, columns: { donor: donorCode } },
    visible: view.visible.includes('donor') ? view.visible : [...view.visible, 'donor'],
  };
}
