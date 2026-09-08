import { request } from '../api/client';

/**
 * Журнал действий организации: кто, что и когда поправил.
 *
 * <p>`audit_log` пишется с самого начала слушателем Hibernate, а наружу
 * его не отдавал ни один эндпоинт — то есть журнал вели затем, чтобы
 * однажды предъявить, и предъявить его было нельзя. Проверено 8 сентября
 * 2026, задача 0043.
 */
export interface AuditChange {
  /** Имя таблицы и колонки — не для показа, а для выбора словаря. */
  table: string;
  column: string;
  label: string;
  before: string | null;
  after: string | null;
}

export interface AuditEntry {
  id: number;
  at: string;
  /**
   * `null` — автора не записано: правка приехала переносом, сделана фоновой
   * задачей или прямым SQL мимо приложения. Экран показывает прочерк
   * и не подставляет вместо него смотрящего: догадка, выглядящая как ответ
   * на вопрос «кто», хуже честной пустоты.
   */
  author: string | null;
  /** Роль **на момент правки**, кодом. `null` у записей до 8 сентября 2026. */
  authorRole: string | null;
  kind: string;
  subject: string | null;
  subjectCode: string | null;
  /** Где это случилось: «Сделка №123», «Машина A7K3M2». */
  context: string | null;
  /** Заполнен у событий без полей: «Товар заведён». */
  action: string | null;
  changes: AuditChange[];
}

export interface AuditPage {
  items: AuditEntry[];
  total: number;
  /** Счёт упёрся в потолок: записей больше, чем сказано в `total`. */
  capped: boolean;
  more: boolean;
  /** По каким колонкам сервер делает отбор. Локальной копии нет намеренно. */
  filterable: string[];
}

export interface JournalQuery {
  author?: string;
  kind?: string;
  field?: string;
  q?: string;
  from?: string;
  to?: string;
}

/** Те же слова пустоты, что у меню колонки на витрине склада. */
export const FILTER_EMPTY = '—пусто—';
export const FILTER_PRESENT = '—не пусто—';

export function loadJournal(query: JournalQuery, size: number): Promise<AuditPage> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') {
      params.set(key, value);
    }
  }
  params.set('size', String(size));
  return request<AuditPage>(`/api/organization/audit?${params.toString()}`);
}

/**
 * Значения для меню колонки.
 *
 * <p>С сервера, а не из списка здесь: два списка разошлись бы на первой же
 * новой колонке, и меню предлагало бы отбор, которого сервер не делает.
 */
export function journalValues(column: string): Promise<string[]> {
  return request<string[]>(
    `/api/organization/audit/values?column=${encodeURIComponent(column)}`,
  );
}
