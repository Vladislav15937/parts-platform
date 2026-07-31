import { request } from '../api/client';

/**
 * Разбор нераспознанных наименований.
 *
 * <p>Написание, не совпавшее с эталоном, приёмку не останавливает — карточка
 * заводится с написанием как есть и без категории. Разгребают такие вечером,
 * а после импорта чужого склада их набирается сразу сотня.
 *
 * <p>Ни кэша, ни очереди, как и у продажи: справочник правят за столом,
 * и сопоставление, отложенное в телефоне, ничего не чинит. Вдобавок оно
 * переписывает заголовки всех позиций под написанием разом — такое не должно
 * происходить вслепую из очереди.
 */

export interface UnmatchedName {
  id: number;
  name: string;
  matchStatus: string;
  partKindId: number | null;
  categoryId: number | null;
  /** Сколько позиций заведено под этим написанием: что чинить раньше. */
  usageCount: number;
  createdAt: string;
}

/** Эталонный вид детали из общего каталога. */
export interface PartKind {
  id: number;
  categoryId: number | null;
  name: string;
}

export interface UnmatchedPage {
  items: UnmatchedName[];
  total: number;
}

export function unmatchedNames(page = 0, size = 20): Promise<UnmatchedPage> {
  return request<UnmatchedPage>(`/api/part-names/unmatched?page=${page}&size=${size}`);
}

/** Похожие эталоны. Решает человек — алгоритм только предлагает. */
export function suggestionsFor(partNameId: number): Promise<PartKind[]> {
  return request<PartKind[]>(`/api/part-names/${partNameId}/suggestions`);
}

/**
 * Поиск эталона руками.
 *
 * <p>Подсказки идут по похожести строк, а «запаска» не похожа на «Колесо
 * запасное» ничем. Без поиска разбор встанет на первом же таком написании.
 */
/**
 * Весь справочник видов деталей.
 *
 * <p>Отдельно от поиска: экрану отбора выгрузки нужны названия уже выбранных
 * видов, а поиск по идентификатору не ищет. Строк сто семьдесят восемь,
 * справочник меняется с релизом — брать его целиком дешевле, чем гонять
 * запрос на каждую букву.
 */
export function allKinds(): Promise<PartKind[]> {
  return request<PartKind[]>('/api/part-names/kinds/all');
}

export function searchKinds(query: string): Promise<PartKind[]> {
  return request<PartKind[]>(`/api/part-names/kinds?q=${encodeURIComponent(query)}`);
}

export interface MatchResult {
  partName: UnmatchedName;
  /** Сколько карточек получили категорию и эталонный заголовок. */
  updated: number;
}

export function matchName(partNameId: number, partKindId: number): Promise<MatchResult> {
  return request<MatchResult>(`/api/part-names/${partNameId}/match`, {
    method: 'POST',
    body: { partKindId },
  });
}

export function unmatchName(partNameId: number): Promise<UnmatchedName> {
  return request<UnmatchedName>(`/api/part-names/${partNameId}/unmatch`, { method: 'POST' });
}
