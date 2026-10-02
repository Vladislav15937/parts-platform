import { ApiError, request } from '../api/client';
import { imageFormatsWording, looksLikeImage } from '../photos/imageFile';
import { resizePhoto } from '../photos/resize';

/**
 * Снимки машины-донора: добавить, назначить главным, удалить.
 *
 * <p><b>Зачем они нужны.</b> Для двигателя и коробки состояние машины —
 * половина объявления: покупатель смотрит, откуда снято, какой пробег,
 * цел ли кузов. Фотографировать машину заново на каждую снятую с неё
 * запчасть никто не станет — их с одного донора выходит до полутора сотен,
 * — поэтому машина снимается один раз, а в объявление её снимки попадают
 * тем наименованиям, которые владелец отметил у выгрузки.
 *
 * <p><b>Онлайн, без очереди</b> — как досъёмка в карточке позиции
 * и по той же причине: владелец сидит за компьютером и ждёт результата
 * сейчас. Очередь откладывала бы работу, которую человек ждёт, и прятала
 * отказ хранилища за «отправим позже».
 *
 * <p><b>Три шага, как и везде:</b> ссылка, загрузка прямо в хранилище,
 * подтверждение. Снимок весит сотни килобайт, и гонять его через приложение
 * значит занимать его потоки на минуты.
 */
export interface DonorPhoto {
  photoId: number;
  main: boolean;
  url: string;
}

interface Upload {
  photoId: number;
  key: string;
  uploadUrl: string;
}

export function loadDonorPhotos(donorId: number): Promise<DonorPhoto[]> {
  return request<DonorPhoto[]>(`/api/intake/donors/${donorId}/photos`);
}

export async function uploadDonorPhoto(donorId: number, file: File): Promise<void> {
  // Отказ не-картинке стоит здесь, в слое, который знает, откуда файл:
  // сюда он приходит с диска, выбранный в диалоге, и это может быть что
  // угодно, вплоть до PDF, переименованного в .jpg. То же решение, что
  // у досъёмки позиции (задача 0234): уехавшая в объявление битая картинка
  // стоит снятого объявления.
  if (!(await looksLikeImage(file))) {
    throw new ApiError(
      'permanent',
      0,
      `«${file.name}» — это не картинка. Приложить можно фотографию: ${imageFormatsWording()}`,
    );
  }

  // Уменьшение до отправки: снимок с телефона весит пять мегабайт, а в прайсе
  // площадки от них не остаётся ничего, кроме времени загрузки.
  const resized = await resizePhoto(file);

  const upload = await request<Upload>(`/api/intake/donors/${donorId}/photos/upload-url`, {
    method: 'POST',
    // Ключ клиента: повтор после обрыва вернёт ту же запись и новую ссылку,
    // а не заведёт второй снимок и мусор в хранилище.
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

  await request(`/api/intake/donors/${donorId}/photos/${upload.photoId}/confirm`, {
    method: 'POST',
    body: { width: resized.width, height: resized.height },
  });
}

/** Главный снимок машины — тот, что уходит в объявление первым из донорских. */
export function makeMainDonorPhoto(donorId: number, photoId: number): Promise<void> {
  return request<void>(`/api/intake/donors/${donorId}/photos/${photoId}/main`, {
    method: 'POST',
  });
}

export function deleteDonorPhoto(donorId: number, photoId: number): Promise<void> {
  return request<void>(`/api/intake/donors/${donorId}/photos/${photoId}`, { method: 'DELETE' });
}
