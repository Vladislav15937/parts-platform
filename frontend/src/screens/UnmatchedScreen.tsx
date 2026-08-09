import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { positions } from '../ui/plural';
import {
  matchName,
  rematchNames,
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
  const [busy, setBusy] = useState(false);

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

      {/*
        * Пересопоставление по нынешнему справочнику.
        *
        * Справочник видов деталей растёт с релизом, а написания клиента
        * заведены раньше: пополнение само по себе не меняет ничего, и
        * владелец продолжает видеть ту же стену нераспознанных. Эндпоинт
        * для этого написан давно, но звать его было некому — дотянуться
        * можно было только повторным импортом, то есть перезалив выгрузку
        * целиком ради пересчёта.
        *
        * Безопасно и потому без подтверждения: сопоставляется только точное
        * совпадение с эталоном или синонимом, а сделанное человеком руками
        * не трогается вовсе.
        */}
      {canManage && total > 0 && (
        <p>
          <button type="button" className="button--ghost" disabled={busy}
                  onClick={() => void rematchAll()}>
            Пересопоставить по справочнику
          </button>
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

  async function rematchAll(): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      const result = await rematchNames();
      // Числом, а не «готово»: «сопоставил» и «ничего не изменилось» —
      // разные новости, и по экрану их иначе не различить.
      setDone(result.matched === 0
        ? 'Ни одно написание не совпало с эталоном — эти разбираются руками'
        : `Сопоставлено написаний: ${result.matched}, исправлено карточек: ${result.updated}`);
      await load(size);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Пересопоставить не удалось');
    } finally {
      setBusy(false);
    }
  }

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
  // Сколько подошло всего: список обрезан, и молчать нельзя —
  // не найдя эталона, разбирающий решит, что его нет вовсе.
  const [foundTotal, setFoundTotal] = useState(0);
  const [asked, setAsked] = useState(false);
  /**
   * На какой эталон навели: строка «Станет» показывает именно его.
   *
   * <p>Заголовок после сопоставления — единственное, чем верное решение
   * отличается от ложного: обе кнопки выглядят одинаково, а нажатие правит
   * сотни карточек и назад не откатывается. Пока «Станет» показывал только
   * первый эталон, у остальных — а среди них соседи вроде «Ключ зажигания»
   * и «Замок зажигания» — сравнить было нечего.
   */
  const [hovered, setHovered] = useState<string | null>(null);

  /**
   * Какой эталон ждёт подтверждения.
   *
   * <p>Сопоставление правит все позиции под написанием разом — у живого
   * клиента это до трёхсот карточек, — и назад не откатывается ничем,
   * кроме восстановления из бэкапа. Фишки эталонов стоят в ряд и похожи
   * друг на друга («Ключ зажигания» рядом с «Замком зажигания»), а
   * разбирают их сотнями подряд: промах мышью стоит трёхсот карточек,
   * утверждающих, что они другая деталь. Поэтому второе нажатие — так же,
   * как у правки списком и у отказа по заказу.
   */
  const [confirming, setConfirming] = useState<number | null>(null);

  useEffect(() => {
    void suggestionsFor(partName.id)
      .then(setSuggested)
      .catch(() => setSuggested([]))
      .finally(() => setAsked(true));
  }, [partName.id]);

  return (
    <div className="picker">
      {partName.sampleTitle !== null && (
        <p className="note">
          Сейчас: <b>{partName.sampleTitle}</b>
        </p>
      )}

      {suggested.length > 0 && (
        <>
          <div className="muted">Похожие эталоны</div>
          <div className="chips">
            {suggested.map((kind) => (
              <button
                key={kind.id}
                type="button"
                className="chip"
                title={preview(partName, kind.name)}
                // Наведение и фокус меняют строку «Станет» ниже. Нативной
                // подсказки для этого мало: она всплывает через секунду,
                // и разбирающий шестьсот написаний подряд её не дожидается —
                // он выбирает из того, что видно, а видно было только
                // первый эталон.
                onMouseEnter={() => setHovered(kind.name)}
                onMouseLeave={() => setHovered(null)}
                onFocus={() => setHovered(kind.name)}
                onBlur={() => setHovered(null)}
                onClick={() => pick(kind)}
              >
                {label(kind)}
              </button>
            ))}
          </div>
          {/* Заголовок после сопоставления — до нажатия, а не после.
              Оно правит сотни карточек разом и назад не откатывается,
              а разница между «тросик ручного тормоза» → «Трос ручника»
              и «Знак аварийной остановки» → «Набор инструментов» видна
              только в получившемся заголовке. */}
          {suggested[0] !== undefined && partName.sampleTitle !== null && (
            <p className="note">
              Станет: <b>{preview(partName, hovered ?? suggested[0].name)}</b>
              {suggested.length > 1 && ' — и так для каждого эталона, наведите'}
            </p>
          )}
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
        <>
          <div className="chips">
            {found.map((kind) => (
              <button
                key={kind.id}
                type="button"
                className="chip"
                title={preview(partName, kind.name)}
                onMouseEnter={() => setHovered(kind.name)}
                onMouseLeave={() => setHovered(null)}
                onClick={() => pick(kind)}
              >
                {label(kind)}
              </button>
            ))}
          </div>
          {/* Список обрезан — говорим об этом: не найдя нужного среди
              двух десятков, разбирающий решит, что эталона нет вовсе,
              и оставит написание неразобранным либо возьмёт похожий,
              а одно сопоставление правит сотни карточек. */}
          {foundTotal > found.length && (
            <p className="note note--error">
              Подошло {foundTotal}, показаны первые {found.length} — уточните запрос.
            </p>
          )}
          {found[0] !== undefined && partName.sampleTitle !== null && (
            <p className="note">
              Станет: <b>{preview(partName, found[0].name)}</b>
            </p>
          )}
        </>
      )}

      {query.trim().length >= 2 && found.length === 0 && (
        <p className="note">
          Ничего не нашлось. Справочник видов деталей общий и пополняется
          с релизом — своих эталонов в нём не завести.
        </p>
      )}
    </div>
  );

  /**
   * Первое нажатие спрашивает, второе применяет.
   *
   * <p>Подтверждение живёт на самой фишке, а не в отдельном окне: рядом
   * стоит строка «Станет», и человеку надо видеть будущий заголовок в тот
   * момент, когда он подтверждает. Наведение её и меняет, поэтому
   * подтверждаемый эталон подсвечивается там же.
   */
  function pick(kind: PartKind): void {
    if (confirming !== kind.id) {
      setConfirming(kind.id);
      setHovered(kind.name);
      return;
    }
    setConfirming(null);
    onPick(kind);
  }

  function label(kind: PartKind): string {
    if (confirming !== kind.id) {
      return kind.name;
    }
    return partName.usageCount > 0
      ? `Точно? ${positions(partName.usageCount)}`
      : 'Точно?';
  }

  async function lookup(term: string): Promise<void> {
    if (term.trim().length < 2) {
      setFound([]);
      return;
    }
    try {
      const page = await searchKinds(term.trim());
      setFound(page.items);
      setFoundTotal(page.total);
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

/**
 * Каким станет заголовок карточки после сопоставления.
 *
 * <p>Повторяет правило сервера: начало заголовка подменяется эталоном,
 * только если оно совпадает с написанием и в заголовке есть что-то ещё.
 * Позиция, у которой заголовок и есть само написание, не меняется —
 * подмена стёрла бы сторону, и левая фара слилась бы с правой.
 */
export function preview(name: { name: string; sampleTitle: string | null }, kind: string): string {
  const title = name.sampleTitle;
  if (title === null) {
    return kind;
  }
  if (!title.startsWith(name.name) || title.length <= name.name.length) {
    return title;
  }
  return kind + title.slice(name.name.length);
}
