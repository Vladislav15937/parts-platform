import { code128 } from './code128';
import { LABEL_HEIGHT_MM, LABEL_PADDING_MM, LABEL_WIDTH_MM, scannable } from './labels';
import type { Label } from './labels';

/**
 * Лист этикеток: по одной на страницу, размером с рулон.
 *
 * <p>Штрихкод рисуется в SVG, а не картинкой: браузер печатает вектор
 * в разрешении принтера, а растр, растянутый с экранных 96 точек на дюйм
 * до принтерных 203, даёт размытые границы полос — то есть этикетку,
 * которая читается через раз.
 */
export function LabelSheet({ labels }: { labels: Label[] }) {
  return (
    <div className="label-sheet">
      {labels.map((label, at) => (
        <LabelCard key={`${label.kind}-${label.code}-${at}`} label={label} />
      ))}
    </div>
  );
}

function LabelCard({ label }: { label: Label }) {
  return (
    <article className="label">
      <div className={label.kind === 'cell' ? 'label-title label-title--big' : 'label-title'}>
        {label.title}
      </div>
      {label.note !== null && <div className="label-note">{label.note}</div>}
      {/* Непечатаемый код не роняет лист: раньше исключение из кодировщика
          прилетало прямо в render, и экран целиком уходил в белый лист —
          на первой же ячейке с буквой «Б». */}
      {scannable(label.code) ? (
        <Barcode value={label.code} />
      ) : (
        <div className="label-note">адрес не кодируется, переименуйте латиницей</div>
      )}
      {/* Код под штрихкодом — не украшение: когда сканер не берёт
          затёртую этикетку, кладовщик набирает его руками. */}
      <div className="label-code">{label.code}</div>
    </article>
  );
}

/**
 * Штрихкод во всю ширину этикетки за вычетом полей.
 *
 * <p>viewBox в модулях, а ширина в миллиметрах: браузер сам растянет модули
 * на доступное поле, и ширина модуля получится максимальной из возможных —
 * чем она больше, тем увереннее читает сканер.
 */
function Barcode({ value }: { value: string }) {
  const drawn = code128(value);
  const width = LABEL_WIDTH_MM - LABEL_PADDING_MM * 2;

  return (
    <svg
      className="label-barcode"
      viewBox={`0 0 ${drawn.width} 100`}
      width={`${width}mm`}
      height="12mm"
      preserveAspectRatio="none"
      role="img"
      aria-label={`Штрихкод ${value}`}
    >
      {drawn.bars.map((bar) => (
        <rect key={bar.x} x={bar.x} y="0" width={bar.width} height="100" fill="#000" />
      ))}
    </svg>
  );
}

/** Размеры листа нужны и печати, и предпросмотру — держим их в одном месте. */
export const LABEL_STYLE = `${LABEL_WIDTH_MM}mm × ${LABEL_HEIGHT_MM}mm`;
