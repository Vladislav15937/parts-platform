import { useEffect, useState } from 'react';
import { useSession } from '../auth/SessionProvider';
import { useOutbox } from '../outbox/useOutbox';
import { MembersScreen } from './MembersScreen';
import { OrganizationScreen } from './OrganizationScreen';
import { SettingsScreen } from './SettingsScreen';
import { ReferencePanel } from '../reference/ReferencePanel';
import { useReference } from '../reference/useReference';
import { warmUpDecoder } from '../scan/decoder';
import { useOnline } from '../shell/useOnline';
import { useMounted } from '../ui/useMounted';
import { countLabel } from '../inventory/inventory';
import { IntakeScreen } from './IntakeScreen';
import { DonorScreen } from './DonorScreen';
import { ImportScreen } from './ImportScreen';
import { InventoryScreen } from './InventoryScreen';
import { InventoryReconcile } from './InventoryReconcile';
import { OutboxScreen } from './OutboxScreen';
import { SellerScreen } from './SellerScreen';
import { DealsScreen } from './DealsScreen';
import { ReturnsScreen } from './ReturnsScreen';
import { PaymentsScreen } from './PaymentsScreen';
import { CustomersScreen } from './CustomersScreen';
import { DeliveryScreen } from './DeliveryScreen';
import { LabelsScreen } from './LabelsScreen';
import { ReportsScreen } from './ReportsScreen';
import { UnmatchedScreen } from './UnmatchedScreen';
import { OrdersScreen } from './OrdersScreen';
import { FeedsScreen } from './FeedsScreen';
import { WheelsScreen } from './WheelsScreen';
import { CatalogScreen } from './CatalogScreen';
import { StockMovesScreen } from './StockMovesScreen';
import { AuditJournalScreen } from './AuditJournalScreen';
import { AdminVisitBanner } from '../admin/AdminVisitBanner';
import { ordersAwaitingReply } from '../sales/sales';
import { unmatchedNames } from '../catalog/partNames';
import { deadLetters } from '../events/deadLetters';
import {
  COUNTER_NOTE_ROLES,
  JOURNAL_ROLES,
  LABEL_ROLES,
  MOVE_ROLES,
  NAMING_ROLES,
  SELLING_ROLES,
  TABS,
  WRITING_ROLES,
  rightsOf,
  sectionName,
  visibleTo,
  type Tab,
} from './tabs';

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
    state.status === 'authenticated' && !WRITING_ROLES.includes(rightsOf(state.me.role))
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
  // Куда открыть сделку, найденную в реестре возвратов: тот же экран
  // продажи открывает её карточкой, как при поиске «Найти сделку клиента»,
  // а не заводит второй способ показать документ.
  const [openDealId, setOpenDealId] = useState<number | null>(null);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  // Запасной распознаватель тянем сразу после входа, пока связь заведомо есть:
  // первое сканирование случится в ангаре, где её уже не будет.
  useEffect(warmUpDecoder, []);

  // Заголовок вкладки — тот же раздел, что и в верхней полосе. Пока он был
  // прибит к «Приёмке» в index.html, владелец с открытыми складом, продажей
  // и отчётами видел три одинаковые вкладки и искал нужную перебором.
  useEffect(() => {
    document.title = `${sectionName(tab)} — PartsFlow`;
  }, [tab]);

  const role = state.status === 'authenticated' ? rightsOf(state.me.role) : null;

  useEffect(() => {
    // Один запрос за вход, и только тем, кто может разбирать: приёмщику
    // это число ничего не даёт, а без связи оно и не приедет.
    if (role === null || !NAMING_ROLES.includes(role) || !online) {
      return;
    }
    void unmatchedNames(0, 1)
      .then((page) => { if (mounted.current) setUnmatched(page.total); })
      // Молча: справочник — не то, ради чего стоит показывать ошибку
      // на весь экран сразу после входа.
      .catch(() => { if (mounted.current) setUnmatched(0); });
    void deadLetters()
      .then((page) => { if (mounted.current) setUndelivered(page.total); })
      .catch(() => { if (mounted.current) setUndelivered(0); });
  }, [role, online, mounted]);

  useEffect(() => {
    // Заказы — продавцу, а не разбирающему справочник: у ролей разный список
    // вкладок, и число на чужой вкладке только мешает.
    if (role === null || !SELLING_ROLES.includes(role) || !online) {
      return;
    }
    void ordersAwaitingReply()
      .then((found) => { if (mounted.current) setAwaitingOrders(found.length); })
      .catch(() => { if (mounted.current) setAwaitingOrders(0); });
  }, [role, online, mounted]);

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
  // Права экранов — по `rightsOf`: администратор сервиса видит всё, что
  // владелец. Своя роль остаётся в шапке.
  const rights = rightsOf(state.me.role);
  const connected = (outbox.reachedServer ?? online) && !state.offline;
  const unsent = outbox.records.length;

  // Числа на вкладках. Их четыре, и все четыре про накопившееся, о чём
  // иначе никто не узнает: вкладка — единственное место, где о нём сказано.
  const badges: Partial<Record<Tab, number>> = {
    orders: awaitingOrders,
    outbox: unsent,
    names: unmatched,
    delivery: undelivered,
  };

  return (
    <div className="app">
      {/* Рельс собирается из общего перечня разделов (`tabs.ts`), а не из
          двадцати одного блока JSX подряд: второй перечень — в названии
          раздела или в проверке раскладки — разошёлся бы с этим на первом
          же новом экране, и разошёлся бы молча. */}
      <nav className="rail">
        <div className="rail__brand">PartsFlow</div>
        {TABS.filter((spec) => visibleTo(spec, rights)).map((spec) => {
          const count = badges[spec.id] ?? 0;
          return (
            <button
              key={spec.id}
              type="button"
              className={tab === spec.id ? 'rail__item rail__item--active' : 'rail__item'}
              onClick={() => setTab(spec.id)}
            >
              {spec.label}{count > 0 && ` · ${count}`}
            </button>
          );
        })}
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

        {/* Администратор сервиса в чужом кабинете видит это всегда: срок
            доступа и путь обратно (задача 0042). */}
        {state.me.role === 'ADMIN' && <AdminVisitBanner />}

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
            canExpect={rights === 'OWNER'}
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
          <SellerScreen
            canSell={SELLING_ROLES.includes(rights)}
            role={rights}
            company={state.me.companySchema}
            memberId={state.me.memberId}
            openDealId={openDealId}
            onDealOpened={() => setOpenDealId(null)}
          />
        ) : (
          <p className="note note--error">
            Нет связи. Продажа без неё невозможна: остаток из кэша — это деталь,
            которой уже нет, а отложенная в телефоне сделка ничего не резервирует.
          </p>
        ))}

      {tab === 'deals' && SELLING_ROLES.includes(rights) && (
        <DealsScreen
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {tab === 'returns' && (
        <ReturnsScreen
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {/* Касса компании — владельцу и менеджеру: в реестре видно всё,
          включая возвраты покупателям и выдачи с лицевых счетов. */}
      {tab === 'payments' && NAMING_ROLES.includes(rights) && (
        <PaymentsScreen
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {tab === 'customers' && SELLING_ROLES.includes(rights) && (
        <CustomersScreen
          role={rights}
          company={state.me.companySchema}
          memberId={state.me.memberId}
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {tab === 'catalog' && <CatalogScreen role={rights} />}

      {tab === 'wheels' && (
        <WheelsScreen canIntake={LABEL_ROLES.includes(rights)}
                      role={rights} />
      )}

      {tab === 'feeds' && NAMING_ROLES.includes(rights)
        && <FeedsScreen role={rights} />}

      {tab === 'moves' && MOVE_ROLES.includes(rights)
        && <StockMovesScreen role={rights} />}

      {tab === 'orders' && (
        <OrdersScreen canSell={SELLING_ROLES.includes(rights)} />
      )}

      {/* Журнал пересчётов — над обходом полок и сведением расхождений:
          список документов это то, с чего теперь начинают вкладку, а не
          поиск открытой сессии по складу. Владельцу, менеджеру и «Просмотру» —
          сведение расхождений компонент сам показывает только первым двум. */}
      {tab === 'inventory' && status.kind === 'ready'
        && ['OWNER', 'MANAGER', 'VIEWER'].includes(rights) && (
          <InventoryReconcile reference={status.reference} role={rights} />
        )}

      {/* Обход полок сканером — не «Просмотру»: экран называет действие,
          которое сервер эту роль отобьёт. */}
      {tab === 'inventory' && WRITING_ROLES.includes(rights) &&
        (status.kind === 'ready' ? (
          <InventoryScreen
            reference={status.reference}
            onCount={(sessionId, line, qty, countedAt) =>
              void outbox.add(
                'count',
                { sessionId, partId: line.partId, qty, countedAt },
                countLabel(line, qty),
              )
            }
            onNote={COUNTER_NOTE_ROLES.includes(rights)
              ? (sessionId, text) => void outbox.add(
                'inventoryNote',
                { sessionId, note: text },
                `Комментарий к пересчёту: ${text}`,
              )
              : undefined}
            noteQueue={outbox.records.filter((record) => record.kind === 'inventoryNote')}
          />
        ) : (
          <p className="note">
            Справочники не загружены — сканировать ячейки будет нечем. Откройте
            вкладку «Справочники».
          </p>
        ))}

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
            canImport={rights === 'OWNER'}
          />
        ) : (
          <p className="note">Справочники не загружены — некуда класть склад.</p>
        ))}

      {tab === 'names' &&
        (connected ? (
          <UnmatchedScreen
            canManage={NAMING_ROLES.includes(rights)}
            onTotalChanged={setUnmatched}
          />
        ) : (
          <p className="note note--error">
            Нет связи. Сопоставление меняет вид детали и категорию всех позиций под
            написанием разом — вслепую из очереди такое не отправляют.
          </p>
        ))}

      {tab === 'reports' &&
        (connected ? (
          <ReportsScreen canRead={NAMING_ROLES.includes(rights)} />
        ) : (
          <p className="note note--error">
            Нет связи. Отчёты считает база — закэшированная зарплата хуже
            её отсутствия.
          </p>
        ))}

      {tab === 'delivery' &&
        (connected ? (
          <DeliveryScreen
            canManage={NAMING_ROLES.includes(rights)}
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
          <LabelsScreen canPrint={LABEL_ROLES.includes(rights)} />
        ) : (
          <p className="note note--error">
            Нет связи. Коды ячеек и деталей берутся из базы — печатать нечего.
          </p>
        ))}

      {/* Журнал действий — владельцу и «Ревизору»: роль проверяется здесь
          так же явно, как у остальных разделов, а `visibleTo` управляет
          только кнопкой на рельсе. */}
      {tab === 'journal' && JOURNAL_ROLES.includes(rights) && <AuditJournalScreen />}

      {/* Кто входит в кабинет — сотрудники, их пароли и доступ администратора
          сервиса — решает только сам владелец, а не администратор, у которого
          права владельца в кабинете. Сервер держит то же правилом в
          SecurityConfig (ACCESS_CONTROL). */}
      {tab === 'members' && <MembersScreen managesAccess={state.me.role === 'OWNER'} />}

      {tab === 'organization' && <OrganizationScreen />}

      {tab === 'settings' && rights === 'OWNER' && <SettingsScreen />}

      {tab === 'reference' && <ReferencePanel />}

          <button type="button" className="button--ghost" onClick={signOut}>
            Выйти
          </button>
        </main>
      </div>
    </div>
  );
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
    case 'AUDITOR':
      return 'ревизор';
    case 'ADMIN':
      return 'администратор сервиса';
    default:
      return 'просмотр';
  }
}
