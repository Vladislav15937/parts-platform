import { ApiError, request } from '../api/client';
import { resizePhoto } from '../photos/resize';

/**
 * Снимки уже заведённой карточки: добавить, назначить главным, удалить.
 *
 * <p><b>Зачем это отдельно от приёмки.</b> Там снимок делают телефоном
 * в момент, когда деталь заводят, и он уходит через офлайн-очередь: в ангаре
 * связи нет. Здесь всё наоборот — владелец за компьютером доснимает то,
 * что приехало переносом без картинок или было принято второпях. Очередь
 * тут не нужна и вредна: она откладывает работу, которую человек ждёт
 * сейчас, и прячет отказ хранилища за «отправим позже». Та же причина,
 * по которой продажа не идёт через очередь.
 *
 * <p><b>Три шага, как и в приёмке.</b> Ссылка, загрузка прямо в хранилище,
 * подтверждение. Снимок весит сотни килобайт, и гонять его через приложение
 * значит занимать его потоки на минуты — при десятке фотографий подряд это
 * заметно.
 */
export interface Upload {
  photoId: number;
  key: string;
  uploadUrl: string;
}

export async function uploadPhoto(partId: number, file: File): Promise<void> {
  // Уменьшение до отправки: снимок с телефона весит пять мегабайт, а в
  // карточке и в прайсе площадки от них не остаётся ничего, кроме времени
  // загрузки.
  const resized = await resizePhoto(file);

  const upload = await request<Upload>(`/api/parts/${partId}/photos/upload-url`, {
    method: 'POST',
    // Ключ клиента: повтор после обрыва вернёт ту же запись и новую ссылку,
    // а не заведёт второй снимок.
    body: { contentType: resized.contentType, requestId: crypto.randomUUID() },
  });

  const put = await fetch(upload.uploadUrl, {
    method: 'PUT',
    // Content-Type входит в подпись: другой здесь — отказ хранилища.
    headers: { 'Content-Type': resized.contentType },
    body: resized.blob,
  }).catch(() => null);

  if (put === null || !put.ok) {
    throw new ApiError('transient', put?.status ?? 0, 'Снимок не загрузился в хранилище');
  }

  await request(`/api/parts/${partId}/photos/${upload.photoId}/confirm`, {
    method: 'POST',
    body: { width: resized.width, height: resized.height },
  });
}

/** Главный снимок — тот, что уезжает на площадку и стоит в списке склада. */
export function makeMainPhoto(partId: number, photoId: number): Promise<void> {
  return request<void>(`/api/parts/${partId}/photos/${photoId}/main`, { method: 'POST' });
}

export function deletePhoto(partId: number, photoId: number): Promise<void> {
  return request<void>(`/api/parts/${partId}/photos/${photoId}`, { method: 'DELETE' });
}
