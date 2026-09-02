import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  deadLetters,
  discardDeadLetter,
  eventName,
  retryDeadLetter,
  targetName,
} from '../events/deadLetters';
import type { DeadLetter } from '../events/deadLetters';

/**
 * Что не доехало до площадок.
 *
 * <p>Экран отвечает на вопрос, который иначе задаёт клиент по телефону:
 * почему объявление на Дроме висит доступным, если деталь продана. Раньше
 * ответ лежал в таблице, куда можно было попасть только запросом в базу.
 *
 * <p><b>Разделено на «требует внимания» и «повторяется само».</b> Временные
 * отказы разбирает робот, и показывать их наравне с настоящими значит
 * заставить владельца каждый раз выяснять, надо ли ему что-то делать.
 */
interface Props {
  canManage: boolean;
  onTotalChanged: (total: number) => void;
}

export function DeliveryScreen({ canManage, onTotalChanged }: Props) {
  const [items, setItems] = useState<DeadLetter[]>([]);
  const [busy, setBusy] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!canManage) {
    return (
      <section className="card">
        <h2>Доставка</h2>
        <p className="note">
          Разбирать недоставленное может владелец или менеджер: повтор
          отправляет данные на площадку.
        </p>
      </section>
    );
  }

  const attention = items.filter((item) => item.needsAttention);
  const waiting = items.filter((item) => !item.needsAttention);

  return (
    <section className="card">
      <h2>Не доехало до площадок</h2>

      {error !== null && <p className="note note--error">{error}</p>}
      {notice !== null && <p className="note">{notice}</p>}

      {loading && items.length === 0 && <p className="note">Загружаем…</p>}

      {/* «Всё доставлено» — утверждение, а не отсутствие строк: список пуст
          и тогда, когда узнать не удалось. Живой прогон показал их рядом —
          красное «Запрос отклонён (401)» и успокаивающее «Всё доставлено», —
          а экран этот про то, дошли ли дельты до площадки: ложное спокойствие
          тут стоит непринятых событий, за которыми никто не пойдёт. */}
      {!loading && error === null && items.length === 0 && (
        <p className="note">
          Всё доставлено. Здесь появляется то, что площадка не приняла:
          выдача сделки, возврат, смена цены.
        </p>
      )}

      {attention.length > 0 && (
        <>
          <h3>Требует внимания</h3>
          <p className="note">
            Повторы не помогли. Обычно причина одна: не принят ключ кабинета
            площадки — его меняют в настройках, после чего можно повторить.
          </p>
          <ul className="suggestions">
            {attention.map((item) => (
              <Row
                key={item.id}
                item={item}
                busy={busy === item.id}
                onRetry={() => void retry(item)}
                onDiscard={() => void discard(item)}
              />
            ))}
          </ul>
        </>
      )}

      {waiting.length > 0 && (
        <>
          <h3>Повторяется само</h3>
          <p className="note">
            Похоже на временный отказ площадки. Робот повторит сам, вмешиваться
            не нужно — кнопка на случай, если ждать некогда.
          </p>
          <ul className="suggestions">
            {waiting.map((item) => (
              <Row
                key={item.id}
                item={item}
                busy={busy === item.id}
                onRetry={() => void retry(item)}
                onDiscard={() => void discard(item)}
              />
            ))}
          </ul>
        </>
      )}
    </section>
  );

  async function load(): Promise<void> {
    setLoading(true);
    try {
      const page = await deadLetters();
      setItems(page.items);
      onTotalChanged(page.total);
      setError(null);
    } catch (cause) {
      setError(describe(cause, 'Список не загрузился'));
    } finally {
      setLoading(false);
    }
  }

  async function retry(item: DeadLetter): Promise<void> {
    setBusy(item.id);
    setError(null);
    setNotice(null);
    try {
      await retryDeadLetter(item.id);
      setNotice(`${eventName(item.eventType)} ушла на ${targetName(item.handler)}.`);
      await load();
    } catch (cause) {
      // 409 — это «причина не устранена», а не поломка сервера. Показать
      // «отправлено» там, где ничего не отправилось, хуже, чем не показать
      // ничего: владелец пойдёт дальше с неверной картиной.
      setError(describe(cause, 'Повторить не вышло'));
      await load();
    } finally {
      setBusy(null);
    }
  }

  async function discard(item: DeadLetter): Promise<void> {
    setBusy(item.id);
    try {
      await discardDeadLetter(item.id);
      setNotice(`${eventName(item.eventType)} снята с разбора без отправки.`);
      await load();
    } catch (cause) {
      setError(describe(cause, 'Снять не вышло'));
    } finally {
      setBusy(null);
    }
  }
}

/**
 * Строка разбора.
 *
 * <p>Текст отказа показывается как есть: это единственное, по чему владелец
 * поймёт, чинить ему ключ кабинета или ждать площадку. Пересказывать его
 * своими словами значит однажды пересказать неверно.
 */
function Row({
  item,
  busy,
  onRetry,
  onDiscard,
}: {
  item: DeadLetter;
  busy: boolean;
  onRetry: () => void;
  onDiscard: () => void;
}) {
  return (
    <li>
      <div className="stock-row">
        <div className="stock-info">
          <strong>
            {eventName(item.eventType)} → {targetName(item.handler)}
          </strong>
          <div className="muted">
            {item.aggregateType === 'deal' ? 'сделка' : (item.aggregateType ?? 'запись')}{' '}
            {item.aggregateId} · попыток: {item.attempts} ·{' '}
            {new Date(item.createdAt).toLocaleString('ru-RU')}
          </div>
          <div className="muted">{item.error}</div>
        </div>
        <div className="stock-action">
          <button type="button" disabled={busy} onClick={onRetry}>
            {busy ? '…' : 'Повторить'}
          </button>
          <button type="button" className="button--ghost" disabled={busy} onClick={onDiscard}>
            снять
          </button>
        </div>
      </div>
    </li>
  );
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. Ничего не отправлено — повторите.';
    }
    if (cause.status === 403) {
      return 'Разбирать недоставленное может владелец или менеджер';
    }
    if (cause.status === 409) {
      return `Площадка снова не приняла: ${cause.message}`;
    }
    return cause.message;
  }
  return fallback;
}
