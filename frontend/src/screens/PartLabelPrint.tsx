import { useEffect, useState } from 'react';
import { LabelSheet } from '../labels/LabelSheet';
import { LABEL_SIZE_NOTE, partLabel } from '../labels/labels';

/**
 * Печать этикетки открытой позиции — одной, а не выдачи поиска.
 *
 * <p>Этикетку перепечатывают поштучно и постоянно: отклеилась, порвалась,
 * залили маслом, деталь переупаковали. Пачкой их печатают один раз — при
 * приёмке партии. А поштучно было нельзя вовсе: экран «Этикетки» отправляет
 * в печать всю выдачу поиска, выбрать из неё одну нечем, и точный код
 * в запросе не помогает — он неугадываемый, и на руках его нет как раз
 * потому, что этикетка отклеилась.
 *
 * <p>Этикетка рисуется тем же {@link LabelSheet} и тем же {@link partLabel},
 * что и на экране «Этикетки»: второй способ рисовать её разошёлся бы
 * с первым на первой же правке ширины модуля штрихкода — и разошёлся бы
 * незаметно, потому что сравнивать наклейки из двух мест никто не станет.
 */
export function PartLabelPrint({ code, title, price }: {
  /** Публичный код позиции: он и кодируется штрихкодом. */
  code: string | null;
  title: string;
  price: number | null;
}) {
  const [sheet, setSheet] = useState(false);

  // Печать после отрисовки листа, а не в обработчике нажатия: window.print()
  // останавливает страницу на диалоге, и вызванный до отрисовки, он снял бы
  // изображение с ещё не нарисованной этикетки.
  useEffect(() => {
    if (sheet) {
      window.print();
    }
  }, [sheet]);

  if (code === null || code === '') {
    return (
      <>
        <button type="button" className="button--ghost" disabled>
          Печать штрих-кода
        </button>
        <p className="note">
          Печатать нечего: у позиции нет номера товара, а именно он кодируется
          штрихкодом.
        </p>
      </>
    );
  }

  if (!sheet) {
    return (
      <button type="button" className="button--ghost" onClick={() => setSheet(true)}>
        Печать штрих-кода
      </button>
    );
  }

  return (
    <div className="card-view__print">
      {/* Предпросмотр он же то, что уйдёт в печать, — как и на экране
          «Этикетки»: отдельная вёрстка для принтера разошлась бы
          с увиденным незаметно. */}
      <LabelSheet labels={[partLabel(code, title, price)]} />
      <p className="note no-print">{LABEL_SIZE_NOTE}</p>
      <div className="filter-row no-print">
        {/* Диалог печати закрывают и по ошибке, и чтобы поправить размер:
            повторить надо оттуда же, где этикетка перед глазами. */}
        <button type="button" onClick={() => window.print()}>
          Печать
        </button>
        <button type="button" className="button--ghost" onClick={() => setSheet(false)}>
          Закрыть
        </button>
      </div>
    </div>
  );
}
