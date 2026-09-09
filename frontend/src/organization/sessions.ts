import { request } from '../api/client';

/**
 * Журнал сессий: кто заходил, откуда, сколько работал и чем всё кончилось.
 *
 * <p>Вторая половина задачи 0043. До неё о входах не оставалось ничего,
 * кроме отметки «последний вход» в карточке сотрудника, а она
 * перезаписывается: предыдущий вход не хранился нигде.
 */
export interface SessionEntry {
  id: number;
  /** Имя сотрудника либо введённый логин, если такого сотрудника нет. */
  who: string;
  /** Логин из формы входа, а не свой человек: экран обязан назвать это иначе. */
  unknown: boolean;
  /** Роль **на момент входа**, кодом. `null` у отказов и у старых записей. */
  role: string | null;
  at: string;
  /**
   * Последняя активность. Длительность считается по ней, а не по `endedAt`:
   * закрыл ноутбук в 18:00, сессия истекла в 18:30 — «работал полчаса лишних»
   * было бы ложью.
   */
  lastSeenAt: string | null;
  endedAt: string | null;
  /** `LOGOUT` · `EXPIRED` · `REVOKED`; `null` — сессия ещё действует. */
  endReason: string | null;
  /** Чем отозвана: «смена пароля», «сотрудник выключен». */
  endDetail: string | null;
  success: boolean;
  /** `BAD_CREDENTIALS` · `DISABLED` у неудачной попытки. */
  failureReason: string | null;
  /** Разобранная строка браузера, `null` — не разобрали. */
  device: string | null;
  /** Сырая строка браузера: то, что видно по наведению. */
  userAgent: string | null;
  ip: string | null;
}

export interface SessionPage {
  items: SessionEntry[];
  total: number;
  more: boolean;
}

export interface SessionQuery {
  member?: string;
  outcome?: string;
  from?: string;
  to?: string;
}

export function loadSessions(query: SessionQuery, size: number): Promise<SessionPage> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') {
      params.set(key, value);
    }
  }
  params.set('size', String(size));
  return request<SessionPage>(`/api/organization/sessions?${params.toString()}`);
}

/**
 * Кто вообще заходил — для отбора.
 *
 * <p>С сервера, а не списком сотрудников отсюда: в журнале есть и логины,
 * которых у компании нет вовсе (их-то и вводят, когда подбирают), а список
 * своих людей их не покажет.
 */
export function sessionValues(column: string): Promise<string[]> {
  return request<string[]>(
    `/api/organization/sessions/values?column=${encodeURIComponent(column)}`,
  );
}

/**
 * Чем кончилась сессия — словами.
 *
 * <p>Три исхода различаются, и это решение владельца продукта от 9 сентября
 * 2026: «Вышел», «Истекла» и «Завершена» — три разных ответа на вопрос
 * «почему его больше не было». Свести их в «закрыта» значит потерять
 * ровно то, ради чего смотрят.
 */
export function endedName(entry: SessionEntry): string {
  if (entry.endReason === null) {
    return 'Работает';
  }
  if (entry.endReason === 'LOGOUT') {
    return 'Вышел';
  }
  if (entry.endReason === 'EXPIRED') {
    return 'Истекла';
  }
  return entry.endDetail === null ? 'Завершена' : `Завершена: ${entry.endDetail}`;
}

/**
 * Сколько работал.
 *
 * <p>От входа до последней активности — не до конца сессии. Пока активности
 * не было ни одной, длительности нет вовсе: единственный запрос, которым
 * человек вошёл, ещё ничего не говорит о работе.
 */
export function workedFor(entry: SessionEntry): string | null {
  if (!entry.success || entry.lastSeenAt === null) {
    return null;
  }
  const minutes = Math.round(
    (new Date(entry.lastSeenAt).getTime() - new Date(entry.at).getTime()) / 60000,
  );
  if (minutes < 1) {
    return 'меньше минуты';
  }
  if (minutes < 60) {
    return `${minutes} мин`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} ч` : `${hours} ч ${rest} мин`;
}
