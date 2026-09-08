import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  FILTER_EMPTY,
  FILTER_PRESENT,
  journalValues,
  loadJournal,
} from '../organization/auditJournal';
import type { AuditChange, AuditEntry, AuditPage, JournalQuery } from '../organization/auditJournal';
import { roleTitle } from '../organization/members';
import { dealItemStatusName, dealStatusName } from '../sales/dealStatus';
import { endOfDay, startOfDay } from '../sales/sales';
import { ColumnMenu } from './ColumnMenu';
import { count, plural } from '../ui/plural';
import { shortDate } from '../ui/shortDate';
import { useMounted } from '../ui/useMounted';

/**
 * Журнал действий организации: кто, что и когда сделал.
 *
 * <p><b>Зачем экран.</b> `audit_log` пишется с самого начала, а прочитать его
 * было нельзя ниоткуда: «кто уронил цену» и «кто отменил сделку» спрашивают
 * ровно тогда, когда клиент приехал за деталью, которой нет, — и до этого дня
 * на оба вопроса отвечал разработчик запросом в базу. Решение владельца
 * продукта от 8 сентября 2026 (задача 0043).
 *
 * <p><b>Отбор — тем же меню колонки, что на витрине склада и на колёсах.</b>
 * Свой способ отбирать разошёлся бы с первым: список отбираемых колонок
 * и значения приходят с сервера, а не повторяются здесь.
 *
 * <p><b>Слово состояния берётся из общего словаря.</b> Сервер отдаёт код
 * состояния сделки вместе с именем таблицы и колонки, а называет его
 * `dealStatus.ts` — тот самый единственный файл, в который задача 0048 свела
 * четыре разошедшиеся копии. Пятой копии на сервере поэтому нет.
 */
export function AuditJournalScreen() {
  const [draft, setDraft] = useState('');
  const [query, setQuery] = useState<JournalQuery>({});
  const [size, setSize] = useState(PAGE);
  const [page, setPage] = useState<AuditPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [menuAt, setMenuAt] = useState<{ left: number; top: number } | null>(null);
  const [kinds, setKinds] = useState<string[]>([]);
  const mounted = useMounted();

  useEffect(() => {
    let ignore = false;
    void loadJournal(query, size)
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

  // Пять видов вещей — короткий список, и он приходит оттуда же, откуда
  // отбор по нему: перечисленный здесь, он разошёлся бы со словами сервера
  // и перестал бы находить.
  useEffect(() => {
    let ignore = false;
    void journalValues('kind')
      // Массив, а не «что пришло»: тип описывает обещание сервера,
      // а не ответ. Не массив — это отказ, а не пустой список видов.
      .then((found) => {
        if (!ignore && mounted.current) setKinds(Array.isArray(found) ? found : []);
      })
      .catch(() => { if (!ignore && mounted.current) setKinds([]); });
    return () => { ignore = true; };
  }, [mounted]);

  const filtered = Object.values(query).some((value) => value !== undefined && value !== '');
  // Ответ разбирается как обещание, а не как гарантия: у арендатора
  // первого дня журнал пуст, и падение экрана на отсутствующем поле
  // читается как поломка системы, а не как «ещё ничего не делали».
  const rows = page?.items ?? [];

  function change(patch: Partial<JournalQuery>): void {
    setSize(PAGE);
    setQuery({ ...query, ...patch });
  }

  return (
    <section className="screen screen--wide">
      <h2>Журнал действий</h2>

      <form
        className="filter-row filter-row--search"
        onSubmit={(e) => {
          e.preventDefault();
          change({ q: draft.trim() });
        }}
      >
        <label className="field">
          Что искали
          <input
            value={draft}
            placeholder="Название детали, её код или номер сделки"
            onChange={(e) => setDraft(e.target.value)}
          />
        </label>
        <label className="field">
          Вид
          <select
            value={query.kind ?? ''}
            onChange={(e) => change({ kind: e.target.value })}
          >
            <option value="">Все</option>
            {kinds.map((kind) => <option key={kind} value={kind}>{kind}</option>)}
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
        <button type="submit">Найти</button>
      </form>

      {error !== null && <p className="note note--error">{error}</p>}

      {page === null ? (
        error === null && <p className="note">Загружаем…</p>
      ) : rows.length === 0 ? (
        <p className="note">
          {filtered ? 'По этому отбору записей нет' : 'В журнале пока пусто'}
        </p>
      ) : (
        <>
          <div className="table-scroll">
            <table className="report">
              <thead>
                <tr>
                  <th>Когда</th>
                  {menuColumn('author', 'Кто')}
                  <th>Роль</th>
                  <th>Что</th>
                  {menuColumn('field', 'Изменение')}
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
            {page.capped
              ? `Показано ${count(rows.length)} из более чем ${count(page.total)}`
                + ' записей — уточните отбор'
              : `${count(rows.length)} ${plural(rows.length,
                  'запись', 'записи', 'записей')} из ${count(page.total)}`}
          </p>
        </>
      )}

      {/* Журнал видит не всё, и молчать об этом нельзя: правка, сделанная
          прямым SQL мимо приложения, сюда не попадает вовсе. Спрашивают
          журнал как раз тогда, когда такую правку и подозревают. */}
      <p className="note muted">
        Журнал показывает правки, прошедшие через приложение. Изменения,
        сделанные напрямую в базе, в него не попадают.
      </p>

      {menuFor !== null && menuAt !== null && (
        <ColumnMenu
          column={menuFor}
          at={menuAt}
          chosen={menuFor === 'author' ? query.author : query.field}
          filterable={page?.filterable?.includes(menuFor) ?? false}
          sortable={undefined}
          sort=""
          desc
          onSort={() => setMenuFor(null)}
          onPick={(value) => {
            change(menuFor === 'author'
              ? { author: value ?? '' }
              : { field: value ?? '' });
            setMenuFor(null);
          }}
          values={journalValues}
          empty={FILTER_EMPTY}
          present={FILTER_PRESENT}
          onClose={() => setMenuFor(null)}
        />
      )}
    </section>
  );

  function menuColumn(key: string, title: string) {
    const chosen = key === 'author' ? query.author : query.field;
    return (
      <th className={chosen !== undefined && chosen !== '' ? 'th--filtered' : undefined}>
        <span className="th__title">{title}</span>
        <button
          type="button"
          className="th__menu"
          onClick={(e) => {
            const box = e.currentTarget.getBoundingClientRect();
            setMenuAt({ left: box.left, top: box.bottom });
            setMenuFor(menuFor === key ? null : key);
          }}
        >
          ▾
        </button>
        {chosen !== undefined && chosen !== '' && (
          <div className="th__value">«{chosen}»</div>
        )}
      </th>
    );
  }
}

const PAGE = 50;

function Row({ entry }: { entry: AuditEntry }) {
  return (
    <tr>
      <td>{shortDate(entry.at)}</td>
      {/* Прочерк, а не смотрящий: автор, которого не записали, — это перенос,
          фоновая задача или правка мимо приложения, и подставленное имя
          выглядело бы ответом на вопрос «кто». */}
      <td>{entry.author ?? '—'}</td>
      {/* Роль на момент правки. Пусто у всего, что записано до 8 сентября
          2026: сегодняшняя роль соврала бы задним числом. */}
      <td>{entry.authorRole === null ? '—' : roleTitle(entry.authorRole)}</td>
      <td>
        <strong>{entry.subject ?? entry.kind}</strong>
        <div className="muted">
          {[entry.subject === null ? null : entry.kind, entry.subjectCode, entry.context]
            .filter((part) => part !== null && part !== '')
            .join(' · ')}
        </div>
      </td>
      <td>
        {entry.action !== null ? entry.action : entry.changes.map((change) => (
          <div key={change.column}>
            {change.label}: {shownValue(change, change.before)} →{' '}
            <strong>{shownValue(change, change.after)}</strong>
          </div>
        ))}
      </td>
    </tr>
  );
}

/**
 * Значение поля словом человека.
 *
 * <p>Почти всё сервер уже перевёл — там же, где живёт словарь колонок.
 * Состояние сделки и её позиции названо здесь: его словарь один на весь
 * проект и лежит во фронтенде, и копия на сервере стала бы пятой.
 */
function shownValue(change: AuditChange, raw: string | null): string {
  if (raw === null || raw === '') {
    return '—';
  }
  if (change.column !== 'status') {
    return raw;
  }
  return change.table === 'deal_item' ? dealItemStatusName(raw) : dealStatusName(raw);
}
