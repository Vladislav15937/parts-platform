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
import { lockedReasonAny, rightsOf, useCatalogTitles, type Rights } from '../access/rights';
import { MEMBERS_RIGHTS, TABS, allowed, neededFor, sectionName, type Tab, type TabSpec } from './tabs';

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
  // Без права принимать товар открываемся на складе: приёмка у него погашена,
  // и начать с экрана, который ничего не даёт сделать, значит открыться
  // упрёком вместо работы.
  const [tab, setTab] = useState<Tab>(
    state.status === 'authenticated' && !rightsOf(state.me).has('intake.receive')
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
  // «N запчастей — Посмотреть» с карточки машины: номер машины едет на склад
  // тем же приёмом, что номер сделки на продажу, — состоянием оболочки.
  const [donorFilter, setDonorFilter] = useState<string | null>(null);
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

  // Слова причины погашенного — справочником с сервера, своей копии нет.
  const titles = useCatalogTitles();
  const signed = state.status === 'authenticated' ? rightsOf(state.me) : null;
  const names = signed?.has('names.match') === true;
  const delivery = signed?.has('delivery.manage') === true;
  const sells = signed?.has('deal.sell') === true;

  useEffect(() => {
    // Один запрос за вход, и только тем, кто может разбирать: приёмщику
    // это число ничего не даёт, а без связи оно и не приедет.
    if (!online || (!names && !delivery)) {
      return;
    }
    if (names) {
      void unmatchedNames(0, 1)
        .then((page) => { if (mounted.current) setUnmatched(page.total); })
        // Молча: справочник — не то, ради чего стоит показывать ошибку
        // на весь экран сразу после входа.
        .catch(() => { if (mounted.current) setUnmatched(0); });
    }
    if (delivery) {
      void deadLetters()
        .then((page) => { if (mounted.current) setUndelivered(page.total); })
        .catch(() => { if (mounted.current) setUndelivered(0); });
    }
  }, [names, delivery, online, mounted]);

  useEffect(() => {
    // Заказы — продавцу, а не разбирающему справочник: у ролей разный список
    // вкладок, и число на чужой вкладке только мешает.
    if (!sells || !online) {
      return;
    }
    void ordersAwaitingReply()
      .then((found) => { if (mounted.current) setAwaitingOrders(found.length); })
      .catch(() => { if (mounted.current) setAwaitingOrders(0); });
  }, [sells, online, mounted]);

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
  // Права экранов — с сервера (`rightsOf`): администратор сервиса может всё,
  // что владелец. Своя роль остаётся в шапке.
  const rights: Rights = rightsOf(state.me);
  const spec = TABS.find((t) => t.id === tab);
  // Раздел без права не прячется, а гаснет и называет причину (решение
  // владельца 23.10.2026, задача 0044, вопрос 3): открыв его, человек видит,
  // какого права не хватает и у кого его просить.
  const locked = spec !== undefined && !allowed(spec, rights);
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
        {TABS.map((item) => {
          const count = badges[item.id] ?? 0;
          const open = allowed(item, rights);
          return (
            <button
              key={item.id}
              type="button"
              className={[
                'rail__item',
                tab === item.id ? 'rail__item--active' : '',
                open ? '' : 'rail__item--locked',
              ].filter((c) => c !== '').join(' ')}
              title={open ? undefined : reasonOf(item, titles)}
              aria-label={open ? undefined : `${item.label} — ${reasonOf(item, titles)}`}
              onClick={() => setTab(item.id)}
            >
              {item.label}{open && count > 0 && ` · ${count}`}
            </button>
          );
        })}
      </nav>

      <div className="app__main">
        <header className="topbar">
          <div className="topbar__title">{sectionName(tab)}</div>
          <div>
            <strong>{state.me.displayName}</strong>
            {/* Роль клиента названием, как её написал владелец (задача 0044);
                прежняя колонка у своей роли — «Просмотр», и назвала бы не то. */}
            <span className="muted"> · {state.me.role !== 'ADMIN' && state.me.roleName
              ? state.me.roleName : roleName(state.me.role)}</span>
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

      {locked && spec !== undefined && (
        <section className="card locked-section">
          <h2>{spec.section}</h2>
          <p className="note">{reasonOf(spec, titles)}.</p>
        </section>
      )}

      {!locked && tab === 'intake' &&
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

      {!locked && tab === 'donor' &&
        (status.kind === 'ready' ? (
          <DonorScreen
            reference={status.reference}
            online={connected}
            canExpect={rights.has('supply.expected')}
            onDonorParts={(donorCode) => {
              setDonorFilter(donorCode);
              setTab('catalog');
            }}
            onChanged={() => {
              // Справочники приёмки перечитаются сами: машина попадает
              // в список на экране деталей, когда её ставят в разбор.
              void refreshReference();
            }}
          />
        ) : (
          <p className="note">Справочники не загружены. Откройте вкладку «Справочники».</p>
        ))}

      {!locked && tab === 'sales' &&
        (connected ? (
          <SellerScreen
            canSell={rights.has('deal.sell')}
            rights={rights}
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

      {!locked && tab === 'deals' && (
        <DealsScreen
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {!locked && tab === 'returns' && (
        <ReturnsScreen
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {/* Касса компании — по праву «Реестр платежей»: в реестре видно всё,
          включая возвраты покупателям и выдачи с лицевых счетов. */}
      {!locked && tab === 'payments' && (
        <PaymentsScreen
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {!locked && tab === 'customers' && (
        <CustomersScreen
          rights={rights}
          company={state.me.companySchema}
          memberId={state.me.memberId}
          onOpenDeal={(dealId) => {
            setOpenDealId(dealId);
            setTab('sales');
          }}
        />
      )}

      {!locked && tab === 'catalog' && (
        <CatalogScreen rights={rights} donorFilter={donorFilter}
                       onDonorFilterUsed={() => setDonorFilter(null)} />
      )}

      {!locked && tab === 'wheels' && (
        <WheelsScreen canIntake={rights.has('wheels.set')}
                      rights={rights} />
      )}

      {!locked && tab === 'feeds' && <FeedsScreen rights={rights} />}

      {!locked && tab === 'moves' && <StockMovesScreen rights={rights} />}

      {!locked && tab === 'orders' && (
        <OrdersScreen canSell={rights.has('deal.sell')} />
      )}

      {/* Журнал пересчётов — над обходом полок и сведением расхождений:
          список документов это то, с чего теперь начинают вкладку, а не
          поиск открытой сессии по складу. По праву на журнал пересчётов или
          на сведение; сведение компонент показывает только при своём праве. */}
      {!locked && tab === 'inventory' && status.kind === 'ready'
        && rights.any('inventory.journal.view', 'inventory.reconcile') && (
          <InventoryReconcile reference={status.reference}
                              canReconcile={rights.has('inventory.reconcile')} />
        )}

      {/* Обход полок сканером — только с правом считать: экран называет
          действие, которое сервер без права отобьёт. */}
      {!locked && tab === 'inventory' && rights.has('inventory.count') &&
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
            onNote={rights.has('inventory.counter_note')
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

      {!locked && tab === 'outbox' && (
        <OutboxScreen
          records={outbox.records}
          needsSignIn={outbox.needsSignIn}
          onRetry={(id) => void outbox.retry(id)}
          onDrop={(id) => void outbox.drop(id)}
        />
      )}

      {!locked && tab === 'import' &&
        (status.kind === 'ready' ? (
          <ImportScreen
            reference={status.reference}
            canImport={rights.has('import.manage')}
          />
        ) : (
          <p className="note">Справочники не загружены — некуда класть склад.</p>
        ))}

      {!locked && tab === 'names' &&
        (connected ? (
          <UnmatchedScreen
            canManage={rights.has('names.match')}
            onTotalChanged={setUnmatched}
          />
        ) : (
          <p className="note note--error">
            Нет связи. Сопоставление меняет вид детали и категорию всех позиций под
            написанием разом — вслепую из очереди такое не отправляют.
          </p>
        ))}

      {!locked && tab === 'reports' &&
        (connected ? (
          <ReportsScreen canRead={rights.has('reports.view')} />
        ) : (
          <p className="note note--error">
            Нет связи. Отчёты считает база — закэшированная зарплата хуже
            её отсутствия.
          </p>
        ))}

      {!locked && tab === 'delivery' &&
        (connected ? (
          <DeliveryScreen
            canManage={rights.has('delivery.manage')}
            onTotalChanged={setUndelivered}
          />
        ) : (
          <p className="note note--error">
            Нет связи. Повтор отправляет данные на площадку — вслепую
            из очереди такое не отправляют.
          </p>
        ))}

      {!locked && tab === 'labels' &&
        (connected ? (
          <LabelsScreen canPrint={rights.has('part.cell')} />
        ) : (
          <p className="note note--error">
            Нет связи. Коды ячеек и деталей берутся из базы — печатать нечего.
          </p>
        ))}

      {/* Журнал действий — по праву «Журнал действий и входов»; без него
          раздел погашен выше (`locked`). */}
      {!locked && tab === 'journal' && <AuditJournalScreen />}

      {/* Кто входит в кабинет — сотрудники, их пароли и доступ администратора
          сервиса — решает только сам владелец, а не администратор, у которого
          права владельца в кабинете. Сервер держит то же правилом в
          SecurityConfig (ACCESS_CONTROL). */}
      {!locked && tab === 'members' && (
        <MembersScreen
          managesAccess={state.me.role !== 'ADMIN' && rights.any(...MEMBERS_RIGHTS)}
          ownerActs={state.me.role !== 'ADMIN' && rights.owner}
          mine={rights}
        />
      )}

      {!locked && tab === 'organization' && <OrganizationScreen />}

      {!locked && tab === 'settings' && <SettingsScreen />}

      {!locked && tab === 'reference' && <ReferencePanel />}

          <button type="button" className="button--ghost" onClick={signOut}>
            Выйти
          </button>
        </main>
      </div>
    </div>
  );
}

/** Причина погашенного раздела: какого права нет и где его дают. */
function reasonOf(spec: TabSpec, titles: ReadonlyMap<string, string>): string {
  const keys = neededFor(spec);
  return keys.length === 0 ? '' : lockedReasonAny(keys, titles);
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
