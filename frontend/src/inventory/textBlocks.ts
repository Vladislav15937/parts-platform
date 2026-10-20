import { request } from '../api/client';

/**
 * Именованный текстовый блок описания объявления (задача 0337): позиция
 * выбирает блок по имени, текст уезжает в описание дословно, последним блоком.
 */
export interface TextBlock {
  id: number;
  name: string;
  body: string;
}

export function listTextBlocks(): Promise<TextBlock[]> {
  return request<TextBlock[]>('/api/text-blocks');
}

export function createTextBlock(name: string, body: string): Promise<TextBlock> {
  return request<TextBlock>('/api/text-blocks', { method: 'POST', body: { name, body } });
}

export function updateTextBlock(id: number, name: string, body: string): Promise<TextBlock> {
  return request<TextBlock>(`/api/text-blocks/${id}`, { method: 'PUT', body: { name, body } });
}

/** Блок, выбранный хоть у одной позиции, сервер не удаляет и объясняет словами. */
export function deleteTextBlock(id: number): Promise<void> {
  return request<void>(`/api/text-blocks/${id}`, { method: 'DELETE' });
}
