import type { OutboxRecord } from '../outbox/outbox';

/**
 * Очередь отправки.
 *
 * <p>Экран обязателен, а не полезен. Молчащая очередь — это потерянная работа,
 * о которой узнают через неделю при сверке остатков. Приёмщик должен видеть,
 * сколько его труда ещё не на сервере и что именно застряло.
 */
interface Props {
  records: OutboxRecord[];
  needsSignIn: boolean;
  onRetry(id: string): void;
  onDrop(id: string): void;
}

export function OutboxScreen({ records, needsSignIn, onRetry, onDrop }: Props) {
  const pending = records.filter((r) => r.state === 'pending');
  const failed = records.filter((r) => r.state === 'failed');

  if (records.length === 0) {
    return (
      <section className="card">
        <h2>Очередь пуста</h2>
        <p className="note">Всё, что собрано, ушло на сервер.</p>
      </section>
    );
  }

  return (
    <section className="card">
      <div className="header">
        <h2>Очередь отправки</h2>
        <span className="badge badge--offline">{records.length}</span>
      </div>

      {needsSignIn && (
        <p className="error">
          Сессия кончилась. Войдите заново — очередь дождётся и уйдёт целиком.
        </p>
      )}

      {pending.length > 0 && (
        <>
          <p className="note">Ждут отправки: {pending.length}</p>
          <ul className="suggestions">
            {pending.map((record) => (
              <li key={record.id}>
                {record.title}
                {record.attempts > 0 && (
                  <span className="muted"> · попыток {record.attempts}</span>
                )}
                {/* След прошлой неудачи полезен, пока причина неизвестна.
                    Когда она названа выше — «сессия кончилась», — он ей
                    противоречит: приёмщик читает «проверьте связь» при
                    исправной связи и идёт чинить не то. */}
                {record.lastError !== undefined && !needsSignIn && (
                  <div className="muted">{record.lastError}</div>
                )}
              </li>
            ))}
          </ul>
        </>
      )}

      {failed.length > 0 && (
        <>
          <p className="error">Требуют внимания: {failed.length}</p>
          <p className="note">
            Сервер отклонил их по существу — повторять без изменений
            бессмысленно. Обычная причина: машину списали или ячейку убрали,
            пока телефон был без связи.
          </p>
          <ul className="suggestions">
            {failed.map((record) => (
              <li key={record.id}>
                <strong>{record.title}</strong>
                <div className="error">{record.lastError}</div>
                <div className="row">
                  <button type="button" onClick={() => onRetry(record.id)}>
                    Повторить
                  </button>
                  <button type="button" className="button--ghost" onClick={() => onDrop(record.id)}>
                    Убрать
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
