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

type Section = 'payment' | 'deal';

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
        </nav>
        <div className="settings-content">
          {section === 'payment' ? <PaymentSourcesPanel /> : <DealSourcesPanel />}
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
      setSources(await paymentSources());
      setError('');
    } catch (cause) {
      setSources([]);
      setError(describe(cause, 'Источники платежей не загрузились'));
    }
  }

  async function add(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await createPaymentSource(name.trim(), type === '' ? null : type);
      setName('');
      setType('');
      await load();
    } catch (cause) {
      setError(describe(cause, 'Источник не заведён'));
    } finally {
      setBusy(false);
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
      await load();
    } catch (cause) {
      setError(describe(cause, 'Не удалось изменить источник'));
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
      setSources(await dealSourceEntries());
      setError('');
    } catch (cause) {
      setSources([]);
      setError(describe(cause, 'Источники сделок не загрузились'));
    }
  }

  async function add(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      await createDealSourceEntry(name.trim());
      setName('');
      await load();
    } catch (cause) {
      setError(describe(cause, 'Источник не заведён'));
    } finally {
      setBusy(false);
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
      await load();
    } catch (cause) {
      setError(describe(cause, 'Не удалось изменить источник'));
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
