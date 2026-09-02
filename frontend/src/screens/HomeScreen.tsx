import { useEffect, useState } from 'react';
import { useSession } from '../auth/SessionProvider';
import { useOutbox } from '../outbox/useOutbox';
import { MembersScreen } from './MembersScreen';
import { OrganizationScreen } from './OrganizationScreen';
import { ReferencePanel } from '../reference/ReferencePanel';
import { useReference } from '../reference/useReference';
import { warmUpDecoder } from '../scan/decoder';
import { useOnline } from '../shell/useOnline';
import { IntakeScreen } from './IntakeScreen';
import { DonorScreen } from './DonorScreen';
import { ImportScreen } from './ImportScreen';
import { InventoryScreen } from './InventoryScreen';
import { InventoryReconcile } from './InventoryReconcile';
import { OutboxScreen } from './OutboxScreen';
import { SellerScreen } from './SellerScreen';
import { DeliveryScreen } from './DeliveryScreen';
import { LabelsScreen } from './LabelsScreen';
import { ReportsScreen } from './ReportsScreen';
import { UnmatchedScreen } from './UnmatchedScreen';
import { OrdersScreen } from './OrdersScreen';
import { FeedsScreen } from './FeedsScreen';
import { WheelsScreen } from './WheelsScreen';
import { CatalogScreen } from './CatalogScreen';
import { ordersAwaitingReply } from '../sales/sales';
import { unmatchedNames } from '../catalog/partNames';
import { deadLetters } from '../events/deadLetters';

/**
 * Оболочка после входа.
 *
 * <p>Признак связи и число неотправленных видны всегда, а не всплывают при
 * ошибке: приёмщик должен понимать, уходит его работа на сервер или
 * накапливается, — до того как накопит смену.
 *
 * <p>Роутера по-прежнему нет: три вкладки переключаются состоянием. Адреса
 * экранов приёмщику не нужны, ссылками он не делится.
 */
/** Кто имеет право продавать. Тот же список стоит на сервере в @PreAuthorize. */
const SELLING_ROLES = ['OWNER', 'MANAGER', 'SELLER'];

/**
 * Кто правит справочник наименований и смотрит отчёты. Тот же список
 * в @PreAuthorize: в отчётах лежат зарплатная база смены и себестоимость.
 */
const NAMING_ROLES = ['OWNER', 'MANAGER'];

/**
 * Кто печатает этикетки. Кладовщик здесь есть: подписывать стеллажи —
 * его работа, и гонять за этим владельца значит не подписать их вовсе.
 */
const LABEL_ROLES = ['OWNER', 'MANAGER', 'STOREKEEPER'];

/**
 * Кто заводит данные: приёмка, машины, пересчёт, очередь отправки.
 *
 * <p>Здесь все, кроме «Просмотра». Роль эта названа владельцу «только
 * смотреть», и заводят её тому, кому дают посмотреть; форма приёмки,
 * открытая ей, — это работа, которую сервер отобьёт, а очередь пометит
 * «требует внимания». Экран, называющий действие и не дающий его сделать,
 * хуже отсутствующего экрана.
 */
const WRITING_ROLES = ['OWNER', 'MANAGER', 'STOREKEEPER', 'SELLER'];

type Tab =
  | 'intake'
  | 'donor'
  | 'sales'
  | 'inventory'
  | 'outbox'
  | 'import'
  | 'names'
  | 'reports'
  | 'orders'
  | 'catalog'
  | 'wheels'
  | 'feeds'
  | 'delivery'
  | 'labels'
  | 'members'
  | 'organization'
  | 'reference';

export function HomeScreen() {
  const { state, signOut } = useSession();
  const online = useOnline();
  const { status, refresh: refreshReference } = useReference();
  // Локальные данные принадлежат компании: IndexedDB — хранилище браузера,
  // а не арендатора, и войдя другой компанией на том же устройстве кладовщик
  // видел бы её лист обхода и справочники.
  const company = state.status === 'authenticated' ? state.me.companySchema : undefined;
  const outbox = useOutbox(company);
  // «Просмотр» открывается на складе: приёмки у него в рельсе нет,
  // и начать с экрана, которого не видно, значит открыться пустым местом.
  const [tab, setTab] = useState<Tab>(
    state.status === 'authenticated' && !WRITING_ROLES.includes(state.me.role)
      ? 'catalog'
      : 'intake',
  );
  // Число на вкладке — единственное, что сообщает о накопившемся: сам список
  // владелец не откроет, пока не узнает, что там что-то есть. После импорта
  // склада там сразу сотня.
  const [unmatched, setUnmatched] = useState(0);
  // То же соображение: недоставленное не всплывает само, а звонок клиента
  // за проданной деталью — плохой способ о нём узнать.
  const [undelivered, setUndelivered] = useState(0);
  // И то же самое про заказы: у Дрома трое суток на ответ, после чего деньги
  // возвращаются покупателю. Заказ, о котором продавец не узнал, — это
  // не просто несделанная работа, а потерянные деньги и баллы рейтинга.
  const [awaitingOrders, setAwaitingOrders] = useState(0);

  // Запасной распознаватель тянем сразу после входа, пока связь заведомо есть:
  // первое сканирование случится в ангаре, где её уже не будет.
  useEffect(warmUpDecoder, []);

  // Заголовок вкладки — тот же раздел, что и в верхней полосе. Пока он был
  // прибит к «Приёмке» в index.html, владелец с открытыми складом, продажей
  // и отчётами видел три одинаковые вкладки и искал нужную перебором.
  useEffect(() => {
    document.title = `${sectionName(tab)} — PartsFlow`;
  }, [tab]);

  const role = state.status === 'authenticated' ? state.me.role : null;

  useEffect(() => {
    // Один запрос за вход, и только тем, кто может разбирать: приёмщику
    // это число ничего не даёт, а без связи оно и не приедет.
    if (role === null || !NAMING_ROLES.includes(role) || !online) {
      return;
    }
    void unmatchedNames(0, 1)
      .then((page) => setUnmatched(page.total))
      // Молча: справочник — не то, ради чего стоит показывать ошибку
      // на весь экран сразу после входа.
      .catch(() => setUnmatched(0));
    void deadLetters()
      .then((page) => setUndelivered(page.total))
      .catch(() => setUndelivered(0));
  }, [role, online]);

  useEffect(() => {
    // Заказы — продавцу, а не разбирающему справочник: у ролей разный список
    // вкладок, и число на чужой вкладке только мешает.
    if (role === null || !SELLING_ROLES.includes(role) || !online) {
      return;
    }
    void ordersAwaitingReply()
      .then((found) => setAwaitingOrders(found.length))
      .catch(() => setAwaitingOrders(0));
  }, [role, online]);

  if (state.status !== 'authenticated') {
    return null;
  }

  // Личность, восстановленная локально, — тоже признак отсутствия связи,
  // и более достоверный, чем navigator.onLine: сервер только что не ответил.
  // Связь считается по последнему проходу очереди, а не по navigator.onLine:
  // тот знает только про интерфейс, а в ангаре wi-fi поднят и сервера за ним
  // нет. Значок «на связи» при лежащем сервере — это обещание, которого
  // никто не давал: приёмщик видит его и решает, что работа ушла.
  // Пока проход не состоялся, показываем то, что знает браузер.
  const connected = (outbox.reachedServer ?? online) && !state.offline;
  const unsent = outbox.records.length;

  return (
    <div className="app">
      <nav className="rail">
        <div className="rail__brand">PartsFlow</div>
        {WRITING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'intake' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('intake')}
          >
            Приёмка
          </button>
        )}
        {WRITING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'donor' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('donor')}
          >
            Машина
          </button>
        )}
        <button
          type="button"
          className={tab === 'sales' ? 'rail__item rail__item--active' : 'rail__item'}
          onClick={() => setTab('sales')}
        >
          Продажа
        </button>
        {SELLING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'orders' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('orders')}
          >
            Заказы{awaitingOrders > 0 && ` · ${awaitingOrders}`}
          </button>
        )}
        <button
          type="button"
          className={tab === 'catalog' ? 'rail__item rail__item--active' : 'rail__item'}
          onClick={() => setTab('catalog')}
        >
          Склад
        </button>
        <button
          type="button"
          className={tab === 'wheels' ? 'rail__item rail__item--active' : 'rail__item'}
          onClick={() => setTab('wheels')}
        >
          Шины и диски
        </button>
        {WRITING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'inventory' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('inventory')}
          >
            Пересчёт
          </button>
        )}
        {WRITING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'outbox' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('outbox')}
          >
            Очередь{unsent > 0 && ` · ${unsent}`}
          </button>
        )}
        {WRITING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'import' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('import')}
          >
            Загрузка
          </button>
        )}
        <button
          type="button"
          className={tab === 'names' ? 'rail__item rail__item--active' : 'rail__item'}
          onClick={() => setTab('names')}
        >
          Наименования{unmatched > 0 && ` · ${unmatched}`}
        </button>
        {NAMING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'reports' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('reports')}
          >
            Отчёты
          </button>
        )}
        {NAMING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'feeds' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('feeds')}
          >
            Выгрузки
          </button>
        )}
        {NAMING_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'delivery' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('delivery')}
          >
            Доставка{undelivered > 0 && ` · ${undelivered}`}
          </button>
        )}
        {LABEL_ROLES.includes(state.me.role) && (
          <button
            type="button"
            className={tab === 'labels' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('labels')}
          >
            Этикетки
          </button>
        )}
        {state.me.role === 'OWNER' && (
          <button
            type="button"
            className={tab === 'members' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('members')}
          >
            Сотрудники
          </button>
        )}
        {state.me.role === 'OWNER' && (
          <button
            type="button"
            className={tab === 'organization' ? 'rail__item rail__item--active' : 'rail__item'}
            onClick={() => setTab('organization')}
          >
            Склады
          </button>
        )}
        <button
          type="button"
          className={tab === 'reference' ? 'rail__item rail__item--active' : 'rail__item'}
          onClick={() => setTab('reference')}
        >
          Справочники
        </button>
      </nav>

      <div className="app__main">
        <header className="topbar">
          <div className="topbar__title">{sectionName(tab)}</div>
          <div>
            <strong>{state.me.displayName}</strong>
            <span className="muted"> · {roleName(state.me.role)}</span>
          </div>
          <span className={connected ? 'badge badge--online' : 'badge badge--offline'}>
            {connected ? 'на связи' : 'без связи'}
          </span>
        </header>

        {state.offline && (
          <p className="note">
            Работаем без связи. Вход подтвердится, когда сеть появится; собранное
            до тех пор не потеряется.
          </p>
        )}

        <main className="app__content">

      {tab === 'intake' &&
        (status.kind === 'ready' ? (
          <IntakeScreen
            reference={status.reference}
            onSend={(payload, title, photos) => void outbox.add('receipt', payload, title, photos)}
          />
        ) : (
          <p className="note">
            Справочники не загружены — приёмка невозможна. Откройте вкладку
            «Справочники».
          </p>
        ))}

      {tab === 'donor' &&
        (status.kind === 'ready' ? (
          <DonorScreen
            reference={status.reference}
            online={connected}
            onChanged={() => {
              // Справочники приёмки перечитаются сами: машина попадает
              // в список на экране деталей, когда её ставят в разбор.
              void refreshReference();
            }}
          />
        ) : (
          <p className="note">Справочники не загружены. Откройте вкладку «Справочники».</p>
        ))}

      {tab === 'sales' &&
        (connected ? (
          <SellerScreen canSell={SELLING_ROLES.includes(state.me.role)} role={state.me.role} />
        ) : (
          <p className="note note--error">
            Нет связи. Продажа без неё невозможна: остаток из кэша — это деталь,
            которой уже нет, а отложенная в телефоне сделка ничего не резервирует.
          </p>
        ))}

      {tab === 'catalog' && <CatalogScreen role={state.me.role} />}

      {tab === 'wheels' && (
        <WheelsScreen canIntake={LABEL_ROLES.includes(state.me.role)}
                      role={state.me.role} />
      )}

      {tab === 'feeds' && NAMING_ROLES.includes(state.me.role)
        && <FeedsScreen role={state.me.role} />}

      {tab === 'orders' && (
        <OrdersScreen canSell={SELLING_ROLES.includes(state.me.role)} />
      )}

      {tab === 'inventory' &&
        (status.kind === 'ready' ? (
          <InventoryScreen
            reference={status.reference}
            onCount={(sessionId, line, qty, countedAt) =>
              void outbox.add(
                'count',
                { sessionId, partId: line.partId, qty, countedAt },
                `${line.title} · ${qty} шт`,
              )
            }
          />
        ) : (
          <p className="note">
            Справочники не загружены — сканировать ячейки будет нечем. Откройте
            вкладку «Справочники».
          </p>
        ))}

      {/* Сведение расхождений — владельцу и менеджеру: списанная недостача
          это убыток, и решение принимает тот, кто отвечает за склад.
          Кладовщик обходит полки и вносит факт. */}
      {tab === 'inventory' && status.kind === 'ready'
        && ['OWNER', 'MANAGER'].includes(state.me.role) && (
          <InventoryReconcile reference={status.reference} />
        )}

      {tab === 'outbox' && (
        <OutboxScreen
          records={outbox.records}
          needsSignIn={outbox.needsSignIn}
          onRetry={(id) => void outbox.retry(id)}
          onDrop={(id) => void outbox.drop(id)}
        />
      )}

      {tab === 'import' &&
        (status.kind === 'ready' ? (
          <ImportScreen
            reference={status.reference}
            canImport={state.me.role === 'OWNER'}
          />
        ) : (
          <p className="note">Справочники не загружены — некуда класть склад.</p>
        ))}

      {tab === 'names' &&
        (connected ? (
          <UnmatchedScreen
            canManage={NAMING_ROLES.includes(state.me.role)}
            onTotalChanged={setUnmatched}
          />
        ) : (
          <p className="note note--error">
            Нет связи. Сопоставление переписывает заголовки всех позиций под
            написанием разом — вслепую из очереди такое не отправляют.
          </p>
        ))}

      {tab === 'reports' &&
        (connected ? (
          <ReportsScreen canRead={NAMING_ROLES.includes(state.me.role)} />
        ) : (
          <p className="note note--error">
            Нет связи. Отчёты считает база — закэшированная зарплата хуже
            её отсутствия.
          </p>
        ))}

      {tab === 'delivery' &&
        (connected ? (
          <DeliveryScreen
            canManage={NAMING_ROLES.includes(state.me.role)}
            onTotalChanged={setUndelivered}
          />
        ) : (
          <p className="note note--error">
            Нет связи. Повтор отправляет данные на площадку — вслепую
            из очереди такое не отправляют.
          </p>
        ))}

      {tab === 'labels' &&
        (connected ? (
          <LabelsScreen canPrint={LABEL_ROLES.includes(state.me.role)} />
        ) : (
          <p className="note note--error">
            Нет связи. Коды ячеек и деталей берутся из базы — печатать нечего.
          </p>
        ))}

      {tab === 'members' && <MembersScreen />}

      {tab === 'organization' && <OrganizationScreen />}

      {tab === 'reference' && <ReferencePanel />}

          <button type="button" className="button--ghost" onClick={signOut}>
            Выйти
          </button>
        </main>
      </div>
    </div>
  );
}

/** Название раздела в верхней полосе: видно, где ты, не считая вкладки. */
function sectionName(tab: string): string {
  switch (tab) {
    case 'intake': return 'Приёмка';
    case 'donor': return 'Машины';
    case 'sales': return 'Продажа';
    case 'orders': return 'Заказы с площадок';
    case 'catalog': return 'Склад';
    case 'wheels': return 'Шины и диски';
    case 'inventory': return 'Пересчёт склада';
    case 'outbox': return 'Очередь отправки';
    case 'import': return 'Загрузка склада';
    case 'names': return 'Нераспознанные наименования';
    case 'reports': return 'Отчёты';
    case 'feeds': return 'Выгрузки на площадки';
    case 'delivery': return 'Доставка событий';
    case 'labels': return 'Этикетки';
    case 'members': return 'Сотрудники';
    case 'organization': return 'Филиалы и склады';
    default: return 'Справочники';
  }
}

function roleName(role: string): string {
  switch (role) {
    case 'OWNER':
      return 'владелец';
    case 'MANAGER':
      return 'менеджер';
    case 'STOREKEEPER':
      return 'кладовщик';
    case 'SELLER':
      return 'продавец';
    default:
      return 'просмотр';
  }
}
