import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  matchName,
  searchKinds,
  suggestionsFor,
  unmatchedNames,
} from '../catalog/partNames';
import type { PartKind, UnmatchedName } from '../catalog/partNames';

/**
 * Разбор нераспознанных наименований.
 *
 * <p>Приёмщик пишет «фара лев.», импорт приносит «Фара левая перед» из чужой
 * таблицы — и пока написание не сведено к эталону, это три разных товара:
 * искать по складу нечем, категории у карточки нет, а в прайс уезжает
 * написание того, кто заносил.
 *
 * <p><b>Сопоставление правит и прошлое.</b> Разгребают список после импорта,
 * когда все карточки уже заведены; поэтому экран показывает, сколько позиций
 * исправлено — сопоставление, не тронувшее ни одной, владельцу незаметно.
 *
 * <p>Сначала самые ходовые написания: под одним двести карточек, под другим
 * одна, заведённая вчера по ошибке, — и порядок «свежие сверху» после импорта
 * означает «в случайном порядке».
 */
interface Props {
  canManage: boolean;
  onTotalChanged: (total: number) => void;
}

const PAGE = 20;

export function UnmatchedScreen({ canManage, onTotalChanged }: Props) {
  const [names, setNames] = useState<UnmatchedName[]>([]);
  const [total, setTotal] = useState(0);
  const [size, setSize] = useState(PAGE);
  const [openId, setOpenId] = useState<number | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void load(size);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [size]);

  if (!canManage) {
    return (
      <section className="card">
        <h2>Наименования</h2>
        <p className="note">
          Сводить написания к эталону может владелец или менеджер: одно
          сопоставление переписывает заголовки всех позиций под этим написанием.
        </p>
      </section>
    );
  }

  return (
    <section className="card">
      <h2>Нераспознанные наименования</h2>

      {error !== null && <p className="note note--error">{error}</p>}
      {done !== null && <p className="note">{done}</p>}

      {loading && names.length === 0 && <p className="note">Загружаем…</p>}

      {!loading && total === 0 && (
        <p className="note">
          Все написания сведены к эталону. Новые появятся здесь сами — приёмку
          несопоставленное название не останавливает.
        </p>
      )}

      {total > 0 && (
        <p className="note">
          Всего {total}. Сначала те, под которыми больше позиций: с них разбор
          и окупается.
        </p>
      )}

      <ul className="suggestions">
        {names.map((name) => (
          <li key={name.id}>
            <div className="stock-row">
              <div className="stock-info">
                <strong>{name.name}</strong>
                <div className="muted">
                  {name.usageCount === 0
                    ? 'позиций пока нет'
                    : `позиций под этим написанием: ${name.usageCount}`}
                </div>
              </div>
              <div className="stock-action">
                <button
                  type="button"
                  className="button--ghost"
                  onClick={() => setOpenId(openId === name.id ? null : name.id)}
                >
                  {openId === name.id ? 'свернуть' : 'сопоставить'}
                </button>
              </div>
            </div>

            {openId === name.id && (
              <KindPicker
                partName={name}
                onPick={(kind) => void apply(name, kind)}
                onError={setError}
              />
            )}
          </li>
        ))}
      </ul>

      {names.length < total && (
        <button type="button" className="button--ghost" onClick={() => setSize(size + PAGE)}>
          Показать ещё
        </button>
      )}
    </section>
  );

  async function load(pageSize: number): Promise<void> {
    setLoading(true);
    try {
      const page = await unmatchedNames(0, pageSize);
      setNames(page.items);
      setTotal(page.total);
      onTotalChanged(page.total);
      setError(null);
    } catch (cause) {
      setError(describe(cause, 'Список не загрузился'));
    } finally {
      setLoading(false);
    }
  }

  async function apply(name: UnmatchedName, kind: PartKind): Promise<void> {
    setError(null);
    try {
      const result = await matchName(name.id, kind.id);
      setOpenId(null);
      // Число исправленных карточек — это и есть работа экрана. Без него
      // владелец не отличит «сопоставил» от «ничего не произошло».
      setDone(
        `«${name.name}» → «${kind.name}». ${
          result.updated === 0
            ? 'Позиций под этим написанием не было.'
            : `Исправлено карточек: ${result.updated}.`
        }`,
      );
      await load(size);
    } catch (cause) {
      setError(describe(cause, 'Сопоставить не удалось'));
    }
  }
}

/**
 * Выбор эталона: сначала подсказки, потом поиск.
 *
 * <p>Подсказки идут по похожести строк и молчат ровно там, где написание
 * дальше всего от эталона: «запаска» не похожа на «Колесо запасное» ничем.
 * Поэтому поиск здесь же, а не отдельной кнопкой.
 */
function KindPicker({
  partName,
  onPick,
  onError,
}: {
  partName: UnmatchedName;
  onPick: (kind: PartKind) => void;
  onError: (message: string) => void;
}) {
  const [suggested, setSuggested] = useState<PartKind[]>([]);
  const [query, setQuery] = useState('');
  const [found, setFound] = useState<PartKind[]>([]);
  const [asked, setAsked] = useState(false);

  useEffect(() => {
    void suggestionsFor(partName.id)
      .then(setSuggested)
      .catch(() => setSuggested([]))
      .finally(() => setAsked(true));
  }, [partName.id]);

  return (
    <div className="picker">
      {suggested.length > 0 && (
        <>
          <div className="muted">Похожие эталоны</div>
          <div className="chips">
            {suggested.map((kind) => (
              <button key={kind.id} type="button" className="chip" onClick={() => onPick(kind)}>
                {kind.name}
              </button>
            ))}
          </div>
        </>
      )}

      {asked && suggested.length === 0 && (
        <p className="note">
          Похожего в справочнике нет — найдите по смыслу. «Запаска» называется
          «Запасное колесо», и по буквам это не ищется.
        </p>
      )}

      <label>
        Найти эталон
        <input
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            void lookup(e.target.value);
          }}
          placeholder="фара, бампер, стойка"
          autoCapitalize="none"
        />
      </label>

      {found.length > 0 && (
        <div className="chips">
          {found.map((kind) => (
            <button key={kind.id} type="button" className="chip" onClick={() => onPick(kind)}>
              {kind.name}
            </button>
          ))}
        </div>
      )}

      {query.trim().length >= 2 && found.length === 0 && (
        <p className="note">
          Ничего не нашлось. Справочник видов деталей общий и пополняется
          с релизом — своих эталонов в нём не завести.
        </p>
      )}
    </div>
  );

  async function lookup(term: string): Promise<void> {
    if (term.trim().length < 2) {
      setFound([]);
      return;
    }
    try {
      setFound(await searchKinds(term.trim()));
    } catch (cause) {
      onError(describe(cause, 'Поиск эталона не работает'));
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. Ничего не изменилось — повторите.';
    }
    if (cause.status === 403) {
      return 'Сводить написания к эталону может владелец или менеджер';
    }
    return cause.message;
  }
  return fallback;
}
