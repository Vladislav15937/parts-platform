import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  archiveDealSourceEntry,
  archivePaymentSource,
  createDealSourceEntry,
  createPaymentSource,
  dealSourceEntries,
  paymentSourceTypeLabel,
  paymentSources,
  unarchiveDealSourceEntry,
  unarchivePaymentSource,
  PAYMENT_SOURCE_TYPES,
} from '../sales/sales';
import type { DealSourceEntry, PaymentSourceEntry } from '../sales/sales';
import {
  companySettings,
  daysLabel,
  saveCompanySettings,
  MAX_RESERVATION_DAYS,
  MIN_RESERVATION_DAYS,
} from '../settings/company';
import { useMounted } from '../ui/useMounted';

type Section = 'payment' | 'deal' | 'reservation';

/**
 * Настройки — экран владельца.
 *
 * <p>Источники платежей и источники сделок жили в схеме и в контроллерах
 * с самого начала — продавец выбирает их при каждой продаже и оплате, —
 * а завести новый источник или снять лишний с работы было нечем, кроме SQL.
 * Ровно та ловушка из корневого {@code CLAUDE.md}: поле есть, человеку
 * недоступно.
 *
 * <p>Форма заведения источника описана нашими средствами, а не порядком
 * шагов ориентира: его форму не видел никто, права на редактирование там
 * закрыты, и подражать порядку шагов, которого не видел, значит подражать
 * догадке. Подзаголовки, заголовки колонок и подписи типа взяты из критерия
 * приёмки задачи 0024 дословно.
 */
export function SettingsScreen() {
  const [section, setSection] = useState<Section>('payment');

  return (
    <section className="screen">
      <h2>Настройки</h2>
      <div className="settings-layout">
        <nav className="settings-nav">
          <button
            type="button"
            className={section === 'payment' ? 'tab tab--active' : 'tab'}
            onClick={() => setSection('payment')}
          >
            Источники платежей
          </button>
          <button
            type="button"
            className={section === 'deal' ? 'tab tab--active' : 'tab'}
            onClick={() => setSection('deal')}
          >
            Источники сделок
          </button>
          <button
            type="button"
            className={section === 'reservation' ? 'tab tab--active' : 'tab'}
            onClick={() => setSection('reservation')}
          >
            Срок резервирования
          </button>
        </nav>
        <div className="settings-content">
          {section === 'payment' && <PaymentSourcesPanel />}
          {section === 'deal' && <DealSourcesPanel />}
          {section === 'reservation' && <ReservationTermPanel />}
        </div>
      </div>
    </section>
  );
}

function PaymentSourcesPanel() {
  const [sources, setSources] = useState<PaymentSourceEntry[] | null>(null);
  const [error, setError] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState('');
  const [busy, setBusy] = useState(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void load();
  }, []);

  return (
    <div className="card">
      <h3>Источники платежей</h3>
      <p className="note">Способы приёма оплаты: используются в сделках и в отчётах</p>

      {error !== '' && <p className="note note--error">{error}</p>}

      {sources === null ? (
        <p className="note">Загружаем…</p>
      ) : sources.length === 0 ? (
        <p className="note">
          Источники платежей не заведены. Пока их нет, оплата записывается без способа.
        </p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Источник</th>
              <th>Тип источника</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {sources.map((source) => (
              <tr key={source.id} className={source.archived ? 'row--archived' : undefined}>
                <td>
                  {source.name}
                  {source.archived && <span className="badge badge--muted"> Архивный</span>}
                </td>
                <td>{paymentSourceTypeLabel(source.sourceType)}</td>
                <td>
                  <button type="button" className="button--ghost" onClick={() => void toggle(source)}>
                    {source.archived ? 'Вернуть из архива' : 'В архив'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="row">
        <label className="field">
          Название
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="ККМ"
          />
        </label>
        <label className="field">
          Тип источника
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {PAYMENT_SOURCE_TYPES.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      <button type="button" disabled={busy || name.trim() === ''} onClick={() => void add()}>
        Добавить источник
      </button>
    </div>
  );

  async function load(): Promise<void> {
    try {
      const found = await paymentSources();
      if (mounted.current) {
        setSources(found);
        setError('');
      }
    } catch (cause) {
      if (mounted.current) {
        setSources([]);
        setError(describe(cause, 'Источники платежей не загрузились'));
      }
    }
  }

  async function add(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await createPaymentSource(name.trim(), type === '' ? null : type);
      if (!mounted.current) return;
      setName('');
      setType('');
      await load();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Источник не заведён'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function toggle(source: PaymentSourceEntry): Promise<void> {
    setError('');
    try {
      if (source.archived) {
        await unarchivePaymentSource(source.id);
      } else {
        await archivePaymentSource(source.id);
      }
      if (mounted.current) await load();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Не удалось изменить источник'));
    }
  }
}

/**
 * Источники сделок — то же самое, что источники платежей, но без типа:
 * откуда пришла продажа, а не чем за неё заплатили.
 */
function DealSourcesPanel() {
  const [sources, setSources] = useState<DealSourceEntry[] | null>(null);
  const [error, setError] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void load();
  }, []);

  return (
    <div className="card">
      <h3>Источники сделок</h3>

      {error !== '' && <p className="note note--error">{error}</p>}

      {sources === null ? (
        <p className="note">Загружаем…</p>
      ) : sources.length === 0 ? (
        <p className="note">Источники сделок не заведены.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Источник</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {sources.map((source) => (
              <tr key={source.id} className={source.archived ? 'row--archived' : undefined}>
                <td>
                  {source.name}
                  {source.archived && <span className="badge badge--muted"> Архивный</span>}
                </td>
                <td>
                  <button type="button" className="button--ghost" onClick={() => void toggle(source)}>
                    {source.archived ? 'Вернуть из архива' : 'В архив'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="row">
        <label className="field">
          Название
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Авито" />
        </label>
      </div>
      <button type="button" disabled={busy || name.trim() === ''} onClick={() => void add()}>
        Добавить источник
      </button>
    </div>
  );

  async function load(): Promise<void> {
    try {
      const found = await dealSourceEntries();
      if (mounted.current) {
        setSources(found);
        setError('');
      }
    } catch (cause) {
      if (mounted.current) {
        setSources([]);
        setError(describe(cause, 'Источники сделок не загрузились'));
      }
    }
  }

  async function add(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await createDealSourceEntry(name.trim());
      if (!mounted.current) return;
      setName('');
      await load();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Источник не заведён'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function toggle(source: DealSourceEntry): Promise<void> {
    setError('');
    try {
      if (source.archived) {
        await unarchiveDealSourceEntry(source.id);
      } else {
        await archiveDealSourceEntry(source.id);
      }
      if (mounted.current) await load();
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Не удалось изменить источник'));
    }
  }
}

/**
 * Срок резервирования сделок — задача 0049.
 *
 * <p>Слова заголовка, подписи и обоих пояснений перенесены из ориентира
 * дословно: клиент приходит оттуда и узнаёт их. Форма правки своя — у них
 * над разделом стоит «Только просмотр», ни одного элемента управления
 * в панели нет, и подражать порядку шагов, которого никто не видел, значит
 * подражать догадке.
 *
 * <p>Третья строка пояснения — наша, и она отвечает на вопрос, который
 * задаёт каждый, кто трогает такую настройку: **что будет с уже открытыми
 * сделками**. Ничего: срок у них проставлен, и настройка его не переписывает.
 * Не сказать этого значит оставить владельца гадать, не сдвинулись ли
 * полсотни отложенных сделок разом.
 */
function ReservationTermPanel() {
  const [saved, setSaved] = useState<number | null>(null);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void load();
  }, []);

  const obstacle = obstacleOf();

  return (
    <div className="card">
      <h3>Срок резервирования сделок</h3>
      <p className="note">Используется как параметр по умолчанию для всех новых сделок</p>

      {error !== '' && <p className="note note--error">{error}</p>}

      {saved === null ? (
        !loadFailed && <p className="note">Загружаем…</p>
      ) : (
        <>
          <p className="settings-value">{daysLabel(saved)}</p>
          <div className="row">
            <label className="field">
              Дней
              <input
                type="number"
                min={MIN_RESERVATION_DAYS}
                max={MAX_RESERVATION_DAYS}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
              />
            </label>
          </div>
          <button type="button" disabled={busy || obstacle !== null} onClick={() => void save()}>
            Сохранить
          </button>
          {obstacle !== null && <p className="note">{obstacle}</p>}
        </>
      )}

      <p className="note">Срок резерва всегда можно изменить в самой сделке</p>
      <p className="note">
        По окончании срока резерва, менеджер увидит пометку «Истёк срок»,
        чтобы принять дальнейшие действия
      </p>
      <p className="note">
        Уже оформленные сделки свой срок не меняют — он у них проставлен.
        Новый срок действует со следующей сделки.
      </p>
    </div>
  );

  /**
   * Одно выражение на кнопку и на подпись под ней — тот же приём, что
   * у оплаты и оформления у продавца: разойдись они, кнопка снова начнёт
   * молчать или назовёт не ту причину.
   */
  function obstacleOf(): string | null {
    const value = Number(draft);
    if (draft.trim() === '') return 'Укажите, на сколько дней откладывать товар';
    if (!Number.isInteger(value)) return 'Срок задаётся целым числом дней';
    if (value < MIN_RESERVATION_DAYS || value > MAX_RESERVATION_DAYS) {
      return `Срок резервирования — от ${MIN_RESERVATION_DAYS} до ${MAX_RESERVATION_DAYS} дней`;
    }
    if (value === saved) return 'Срок не изменён';
    return null;
  }

  async function load(): Promise<void> {
    try {
      const found = await companySettings();
      if (mounted.current) {
        setSaved(found.reservationDays);
        setDraft(String(found.reservationDays));
        setError('');
        setLoadFailed(false);
      }
    } catch (cause) {
      if (mounted.current) {
        setLoadFailed(true);
        setError(describe(cause, 'Срок резервирования не загрузился'));
      }
    }
  }

  async function save(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      const written = await saveCompanySettings(Number(draft));
      if (!mounted.current) return;
      setSaved(written.reservationDays);
      setDraft(String(written.reservationDays));
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Срок не сохранён'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
