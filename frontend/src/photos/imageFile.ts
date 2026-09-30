/**
 * «Это вообще картинка?» — по первым байтам файла, а не по его имени.
 *
 * <p><b>Зачем не по типу.</b> Браузер берёт `file.type` из **расширения**:
 * PDF, переименованный в `.jpg`, приезжает с честным `image/jpeg`. Такой файл
 * до 30 сентября 2026 уходил в хранилище и дальше в объявление — уменьшение
 * на нём падает и отправляет файл как есть, сервер тип не проверял вовсе,
 * а хранилище звало объект `.jpg`. Площадка за битую картинку снимает
 * объявление.
 *
 * <p><b>И почему не «попробуем раскодировать».</b> Это разные вопросы, и путать
 * их дорого: `createImageBitmap` падает на **HEIC** в части браузеров, а HEIC —
 * то, чем снимает айфон, то есть ровно тот снимок от поставщика, ради которого
 * загрузка с компьютера и делается. «Не смогли уменьшить» не означает «не
 * картинка»: первое отправляет файл как есть, второе отбивается словами.
 *
 * <p>Поэтому смотрим подпись формата — её HEIC несёт, не раскодировавшись.
 * Список форматов сверяется с серверным (`PhotoStorage.IMAGE_TYPES`)
 * проверкой `PhotoTypeListTest`: разойдясь, они пропустят или отвергнут
 * не то.
 */

const ascii = (text: string): number[] => Array.from(text, (char) => char.charCodeAt(0));

interface Rule {
  /** Название формата для человека: оно же попадает в отказ. */
  readonly name: string;
  /** Все куски обязаны совпасть: у WebP подпись в двух местах. */
  readonly parts: readonly { readonly at: number; readonly bytes: readonly number[] }[];
}

const RULES: readonly Rule[] = [
  { name: 'JPEG', parts: [{ at: 0, bytes: [0xff, 0xd8, 0xff] }] },
  { name: 'PNG', parts: [{ at: 0, bytes: [0x89, ...ascii('PNG'), 0x0d, 0x0a, 0x1a, 0x0a] }] },
  { name: 'GIF', parts: [{ at: 0, bytes: ascii('GIF8') }] },
  {
    name: 'WebP',
    parts: [
      { at: 0, bytes: ascii('RIFF') },
      { at: 8, bytes: ascii('WEBP') },
    ],
  },
  // HEIC, HEIF и AVIF — контейнер ISO-BMFF: размер блока, потом «ftyp».
  // Марку (heic/heif/mif1/avif) не проверяем: их много, и новая марка
  // означала бы отвергнутый законный снимок.
  { name: 'HEIC', parts: [{ at: 4, bytes: ascii('ftyp') }] },
  { name: 'BMP', parts: [{ at: 0, bytes: ascii('BM') }] },
  { name: 'TIFF', parts: [{ at: 0, bytes: [0x49, 0x49, 0x2a, 0x00] }] },
  { name: 'TIFF', parts: [{ at: 0, bytes: [0x4d, 0x4d, 0x00, 0x2a] }] },
];

/** Сколько байт читаем: дальше 12-го ни одна подпись не заходит. */
const HEAD_BYTES = 16;

/**
 * Похож ли файл на картинку по своей подписи.
 *
 * <p>Читается только начало файла, а не весь: снимок весит мегабайты, и тянуть
 * их в память ради восьми байт незачем.
 */
export async function looksLikeImage(file: Blob): Promise<boolean> {
  let head: Uint8Array;
  try {
    head = new Uint8Array(await file.slice(0, HEAD_BYTES).arrayBuffer());
  } catch {
    // Файл не прочитался (сменный диск вынули, права отобрали). Утверждать
    // «не картинка» тут нельзя — мы просто не посмотрели; пусть решает
    // сервер по заявленному типу.
    return true;
  }
  return RULES.some((rule) =>
    rule.parts.every((part) =>
      part.bytes.every((byte, shift) => head[part.at + shift] === byte),
    ),
  );
}

/** Форматы словами — для отказа человеку. */
export function imageFormatsWording(): string {
  return [...new Set(RULES.map((rule) => rule.name))].join(', ');
}
