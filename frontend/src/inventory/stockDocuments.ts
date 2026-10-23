import { request } from '../api/client';

/**
 * Удаление выполненного поступления, списания или перемещения (задача 0367).
 *
 * <p>Удаление пишет встречное движение и снимает со склада только свободное:
 * проданное и отложенное остаётся, и ответ называет, что не снято. Кнопку
 * экран рисует по признаку, который ставит сервер (`deletableDocumentId`
 * у строки «Движения товара», `deletable` у перевозки), — своего списка ролей
 * у экрана нет, право одно и лежит на сервере (`PostedDocumentDeletion`).
 */
export interface DeletionOutcome {
  documentId: number;
  number: number;
  docType: 'INTAKE' | 'WRITE_OFF' | 'MOVE' | string;
  deletedAt: string | null;
  deletedBy: string | null;
  /** Документ был удалён раньше — повтор нажатия или второй сотрудник. */
  alreadyDeleted: boolean;
  /** Что осталось на складе, потому что продано или отложено. */
  notTaken: NotTaken[];
}

export interface NotTaken {
  partId: number;
  number: number;
  publicCode: string;
  title: string;
  wanted: number;
  taken: number;
}

export function deleteStockDocument(documentId: number): Promise<DeletionOutcome> {
  return request<DeletionOutcome>(`/api/stock/documents/${documentId}`, { method: 'DELETE' });
}

const DOCUMENT_WORDS: Record<string, string> = {
  INTAKE: 'Поступление',
  WRITE_OFF: 'Списание',
  MOVE: 'Перемещение',
};

/** «Поступление №22» — как документ назван в «Движении товара». */
export function documentTitle(outcome: Pick<DeletionOutcome, 'docType' | 'number'>): string {
  return `${DOCUMENT_WORDS[outcome.docType] ?? 'Документ'} №${outcome.number}`;
}

/**
 * Итог удаления словами. Несписанное названо позицией и числом: «снято 0 из 1»
 * значит, что деталь продана или отложена и осталась на складе, — решение
 * владельца 23.10.2026 «списать только свободный остаток».
 */
export function deletionNotice(outcome: DeletionOutcome): string {
  // Все три документа среднего рода: «поступление», «списание», «перемещение».
  const head = outcome.alreadyDeleted
    ? `${documentTitle(outcome)} уже удалено${outcome.deletedBy !== null ? ` (${outcome.deletedBy})` : ''}.`
    : `${documentTitle(outcome)} удалено.`;
  if (outcome.notTaken.length === 0) {
    return head;
  }
  const items = outcome.notTaken
    .map((item) => `${item.title} (№ ${item.number}) — снято ${Number(item.taken)} из ${Number(item.wanted)}`)
    .join('; ');
  return `${head} Не всё снято со склада — остальное продано или отложено: ${items}.`;
}
