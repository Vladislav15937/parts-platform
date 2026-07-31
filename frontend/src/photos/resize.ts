/**
 * Уменьшение снимка перед постановкой в очередь.
 *
 * <p><b>Обязательно, а не желательно.</b> Снимок с телефона — 3–5 МБ,
 * за смену их сотни. IndexedDB даёт порядка сотен мегабайт, дальше браузер
 * начинает вытеснять данные <b>молча</b>: приёмщик получит карточки без
 * фотографий и ни одного сообщения об ошибке.
 *
 * <p>1600 px по длинной стороне и качество 0.8 — примерно 300 КБ. Для площадок
 * этого достаточно: Дром пишет, что фотографии поднимают просмотры в 4–5 раз,
 * но речь о наличии фотографий, а не о разрешении.
 *
 * <p>Оригинал не храним. Он нужен был бы для повторной обработки, а её нет:
 * снимок уходит в хранилище как есть.
 */

const MAX_SIDE = 1600;
const QUALITY = 0.8;
export const RESIZED_CONTENT_TYPE = 'image/jpeg';

export interface ResizedPhoto {
  blob: Blob;
  contentType: string;
  width: number;
  height: number;
}

/**
 * @param file снимок с камеры или из галереи
 * @returns уменьшенный JPEG; при неудаче — исходный файл, потому что
 *          фотография без уменьшения лучше, чем её отсутствие
 */
export async function resizePhoto(file: File | Blob): Promise<ResizedPhoto> {
  try {
    const bitmap = await createImageBitmap(file);
    const { width, height } = fit(bitmap.width, bitmap.height);

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;

    const context = canvas.getContext('2d');
    if (context === null) {
      throw new Error('Холст недоступен');
    }
    context.drawImage(bitmap, 0, 0, width, height);
    bitmap.close();

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, RESIZED_CONTENT_TYPE, QUALITY),
    );
    if (blob === null) {
      throw new Error('Не удалось получить уменьшенный снимок');
    }
    return { blob, contentType: RESIZED_CONTENT_TYPE, width, height };
  } catch {
    // Старый браузер, необычный формат, нехватка памяти — не повод терять
    // работу приёмщика. Отправим как есть и займём больше места.
    return {
      blob: file,
      contentType: file instanceof File ? file.type || RESIZED_CONTENT_TYPE : RESIZED_CONTENT_TYPE,
      width: 0,
      height: 0,
    };
  }
}

/** Вписывает в квадрат {@link MAX_SIDE}, сохраняя пропорции. Мелкое не растягиваем. */
export function fit(
  width: number,
  height: number,
  maxSide = MAX_SIDE,
): { width: number; height: number } {
  const longest = Math.max(width, height);
  if (longest <= maxSide || longest === 0) {
    return { width, height };
  }
  const scale = maxSide / longest;
  return {
    width: Math.round(width * scale),
    height: Math.round(height * scale),
  };
}
