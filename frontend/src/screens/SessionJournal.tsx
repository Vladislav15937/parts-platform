import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { endedName, loadSessions, sessionValues, workedFor } from '../organization/sessions';
import type { SessionEntry, SessionPage, SessionQuery } from '../organization/sessions';
import { roleTitle } from '../organization/members';
import { endOfDay, startOfDay } from '../sales/sales';
import { count, plural } from '../ui/plural';
import { shortDate } from '../ui/shortDate';
import { useMounted } from '../ui/useMounted';

/**
 * Журнал сессий: входы, выходы, устройства и неудачные попытки.
 *
 * <p><b>Зачем экран.</b> «Владелец мог видеть входы, выходы, с какого
 * устройства, длительности сессий и тд и тп» — решение владельца продукта
 * от 8 сентября 2026 (задача 0043). До него о входах не оставалось ничего,
 * кроме отметки «последний вход» в карточке сотрудника, а она перезаписывается:
 * предыдущего входа не существовало нигде, и вопрос «кто заходил в кабинет
 * в субботу» отвечался разработчиком через базу — если бы данные там были.
 *
 * <p><b>Отбор списками, а не меню колонки.</b> На соседней вкладке меню
 * колонки стоит по праву: там у пустоты есть смысл (правка без вошедшего,
 * событие без полей). Здесь пусто не значит ничего — у каждой сессии есть
 * и человек, и время, — а пункт меню, не находящий ничего никогда, это отбор,
 * который врёт. Тем же списком в форме отбирается «вид вещи» на вкладке
 * изменений.
 *
 * <p><b>Отказы во входе показаны вперемешку с входами, и это не небрежность.</b>
 * «Пять отказов подряд ценнее ста успешных входов»: замеченными они бывают
 * ровно тогда, когда стоят рядом с обычными строками того же вечера.
 */
export function SessionJournal() {
  const [query, setQuery] = useState<SessionQuery>({});
  const [size, setSize] = useState(PAGE);
  const [page, setPage] = useState<SessionPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [people, setPeople] = useState<string[]>([]);
  const mounted = useMounted();

  useEffect(() => {
    let ignore = false;
    void loadSessions(query, size)
      .then((found) => {
        if (!ignore && mounted.current) {
          setPage(found);
          setError(null);
        }
      })
      .catch((cause: unknown) => {
        if (!ignore && mounted.current) {
          setError(cause instanceof ApiError ? cause.message : 'Журнал не загрузился');
        }
      });
    return () => { ignore = true; };
  }, [query, size, mounted]);

  useEffect(() => {
    let ignore = false;
    void sessionValues('member')
      // Массив, а не «что пришло»: тип описывает обещание сервера, а не ответ.
      .then((found) => {
        if (!ignore && mounted.current) setPeople(Array.isArray(found) ? found : []);
      })
      .catch(() => { if (!ignore && mounted.current) setPeople([]); });
    return () => { ignore = true; };
  }, [mounted]);

  const filtered = Object.values(query).some((value) => value !== undefined && value !== '');
  const rows = page?.items ?? [];

  function change(patch: Partial<SessionQuery>): void {
    setSize(PAGE);
    setQuery({ ...query, ...patch });
  }

  return (
    <>
      <div className="filter-row">
        <label className="field">
          Кто
          <select
            value={query.member ?? ''}
            onChange={(e) => change({ member: e.target.value })}
          >
            <option value="">Все</option>
            {people.map((name) => <option key={name} value={name}>{name}</option>)}
          </select>
        </label>
        <label className="field">
          Исход
          <select
            value={query.outcome ?? ''}
            onChange={(e) => change({ outcome: e.target.value })}
          >
            <option value="">Входы и отказы</option>
            <option value="SUCCESS">Только входы</option>
            <option value="FAILED">Только отказы</option>
          </select>
        </label>
        <label className="field">
          с
          <input
            type="date"
            onChange={(e) => change({
              from: e.target.value === '' ? '' : startOfDay(e.target.value),
            })}
          />
        </label>
        <label className="field">
          по
          <input
            type="date"
            onChange={(e) => change({
              to: e.target.value === '' ? '' : endOfDay(e.target.value),
            })}
          />
        </label>
      </div>

      {error !== null && <p className="note note--error">{error}</p>}

      {page === null ? (
        error === null && <p className="note">Загружаем…</p>
      ) : rows.length === 0 ? (
        <p className="note">
          {filtered ? 'По этому отбору входов нет' : 'Входов пока не было'}
        </p>
      ) : (
        <>
          <div className="table-scroll">
            <table className="report">
              <thead>
                <tr>
                  <th>Когда</th>
                  <th>Кто</th>
                  <th>Роль</th>
                  <th>Устройство</th>
                  <th>Чем кончилось</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((entry) => <Row key={entry.id} entry={entry} />)}
              </tbody>
            </table>
          </div>

          {page.more && (
            <button type="button" className="button--ghost" onClick={() => setSize(size + PAGE)}>
              Показать ещё
            </button>
          )}

          <p className="note">
            {`${count(rows.length)} ${plural(rows.length, 'запись', 'записи', 'записей')}`
              + ` из ${count(page.total)}`}
          </p>
        </>
      )}

      {/* Город по адресу владелец называл («IP и город из него с пометкой
          „примерно“»), и его здесь нет: он берётся из базы соответствий
          адресов и регионов, которую надо где-то взять и обновлять. Молчать
          об этом хуже, чем не сделать: адрес виден, а откуда вход — нет. */}
      <p className="note muted">
        Журнал показывает входы через приложение. Город по адресу не
        определяется — виден сам адрес.
      </p>
    </>
  );
}

const PAGE = 50;

function Row({ entry }: { entry: SessionEntry }) {
  const worked = workedFor(entry);
  return (
    <tr>
      <td>{shortDate(entry.at)}</td>
      <td>
        <strong>{entry.who}</strong>
        {/* Логин, которого у компании нет, — это не сотрудник, и называть
            его именем нельзя: подбор пароля выглядел бы своим человеком. */}
        {entry.unknown && <div className="muted">такого логина нет</div>}
      </td>
      <td>{entry.role === null ? '—' : roleTitle(entry.role)}</td>
      <td>
        {/* Разобранное — на экран, сырое — по наведению. Разбор делается
            на показе: строка браузера в базе лежит как есть и навсегда. */}
        <span title={entry.userAgent ?? undefined}>
          {entry.device ?? 'Неизвестное устройство'}
        </span>
        {entry.ip !== null && <div className="muted">{entry.ip}</div>}
      </td>
      <td>
        {entry.success ? (
          <>
            <div>{endedName(entry)}</div>
            <div className="muted">
              {[worked === null ? null : `работал ${worked}`,
                entry.endedAt === null ? null : shortDate(entry.endedAt)]
                .filter((part) => part !== null)
                .join(' · ')}
            </div>
          </>
        ) : (
          <>
            <div>Отказ во входе</div>
            <div className="muted">{failureName(entry.failureReason)}</div>
          </>
        )}
      </td>
    </tr>
  );
}

/**
 * Почему не пустили.
 *
 * <p>Внутри организации это различимо, а на форме входа — нет и не будет:
 * там ответ один на все случаи, иначе форма работает справочником
 * действующих компаний и сотрудников.
 */
function failureName(reason: string | null): string {
  if (reason === 'DISABLED') {
    return 'учётная запись выключена';
  }
  if (reason === 'BAD_CREDENTIALS') {
    return 'неверный логин или пароль';
  }
  return 'причина не записана';
}
