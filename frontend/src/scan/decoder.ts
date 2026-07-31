/**
 * Распознавание штрихкода в кадре.
 *
 * <p><b>Почему здесь живой поток, а не снимок.</b> Фотографии приёмки идут
 * через штатную камеру (`<input capture>`) намеренно, но штрихкод так не взять:
 * его ловят наведением, подстраивая расстояние по обратной связи, и попыток
 * нужны десятки в секунду. Снимок дал бы один кадр и «не распозналось» без
 * подсказки, что делать дальше.
 *
 * <p><b>Два движка, и это не перестраховка.</b> `BarcodeDetector` — системный,
 * быстрый и бесплатный по весу, но его нет в Safari, а значит нет на айфонах.
 * ZXing работает везде и весит около мегабайта, поэтому подгружается отдельным
 * куском и только когда системного детектора не нашлось.
 */

/** Что удалось прочитать. `null` — в кадре ничего нет, это норма. */
export interface FrameDecoder {
  decode(canvas: HTMLCanvasElement): Promise<string | null>;
  /** Освобождает движок: ZXing держит воркеры. */
  dispose(): void;
}

/**
 * Форматы, которые встречаются на разборке.
 *
 * <p>Список ограничен намеренно: каждый лишний формат — это работа движка
 * на каждом кадре, а на телефоне это заметно. Code128 — коды ячеек, Code39 —
 * VIN в документах на машину, EAN — заводские штрихкоды на деталях, QR —
 * на случай своих этикеток, когда до них дойдёт.
 */
const NATIVE_FORMATS = ['code_128', 'code_39', 'ean_13', 'ean_8', 'qr_code'];

interface BarcodeDetectorLike {
  detect(source: CanvasImageSource): Promise<{ rawValue: string }[]>;
}

interface BarcodeDetectorConstructor {
  new (options?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats?(): Promise<string[]>;
}

function nativeDetector(): BarcodeDetectorConstructor | undefined {
  return (globalThis as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector;
}

/** Есть ли системный детектор. Влияет только на то, что скажем приёмщику. */
export function hasNativeDecoder(): boolean {
  return nativeDetector() !== undefined;
}

export async function createDecoder(): Promise<FrameDecoder> {
  const Native = nativeDetector();
  if (Native !== undefined) {
    const detector = new Native({ formats: NATIVE_FORMATS });
    return {
      async decode(canvas) {
        try {
          const found = await detector.detect(canvas);
          return found[0]?.rawValue ?? null;
        } catch {
          // Детектор иногда падает на кадре с неготовым видео. Кадр
          // пропускаем: следующий придёт через 200 мс.
          return null;
        }
      },
      dispose() {},
    };
  }
  return await zxingDecoder();
}

async function zxingDecoder(): Promise<FrameDecoder> {
  const [{ BrowserMultiFormatReader }, { BarcodeFormat, DecodeHintType }] = await Promise.all([
    import('@zxing/browser'),
    import('@zxing/library'),
  ]);

  const hints = new Map<number, unknown>([
    [
      DecodeHintType.POSSIBLE_FORMATS,
      [
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.QR_CODE,
      ],
    ],
  ]);
  const reader = new BrowserMultiFormatReader(hints);

  return {
    async decode(canvas) {
      try {
        return reader.decodeFromCanvas(canvas).getText();
      } catch {
        // ZXing бросает NotFoundException на каждом кадре без кода —
        // то есть почти на всех. Это не ошибка.
        return null;
      }
    },
    dispose() {},
  };
}

/**
 * Подтягивает запасной движок заранее.
 *
 * <p>Иначе он скачается в момент первого сканирования — а первое сканирование
 * случится в ангаре, где связи нет. Service Worker кэширует файлы сборки при
 * первом обращении, и это обращение должно случиться, пока связь есть.
 */
export function warmUpDecoder(): void {
  if (hasNativeDecoder() || !navigator.onLine) {
    return;
  }
  void import('@zxing/browser').catch(() => undefined);
}
