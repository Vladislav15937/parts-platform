import { useEffect, useState } from 'react';

import { ApiError } from '../api/client';
import { supplySummary, type ReceiptCount, type SupplySummary } from '../intake/donors';
import { count } from '../ui/plural';

/**
 * Итоги поставки под её строкой (задача 0375): сколько поступлений создано
 * и проведено, сколько товара осталось, продано и списано, сколько машин
 * и во что они обошлись.
 *
 * <p><b>Слова — с окна поставки ориентира</b>: «Поступления», «Черновики —
 * 1 (на 0 руб.)», «Товары», «Остаток — 120 шт.», «Авто-доноры», «Всего — 3 шт.»
 * — переходящий клиент ищет ответ там, где привык. Одно отступление названо
 * задачей: «с/с» расшифрована словом «себестоимость».
 *
 * <p>Три состояния различимы: грузим, не смогли узнать, ответ. «Пусто» здесь
 * не отдельное состояние — у пустой поставки итоги нулевые, и нули это правда.
 */
export function SupplySummaryBlock({ supplyId }: { supplyId: number }) {
  const [summary, setSummary] = useState<SupplySummary | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    // Обычная отмена эффекта: блок сворачивают и раскрывают у другой
    // поставки, и поздний ответ прежней не должен лечь в новый блок.
    let alive = true;
    setSummary(null);
    setFailure(null);
    supplySummary(supplyId).then(
      (loaded) => { if (alive) setSummary(loaded); },
      (cause: unknown) => {
        if (alive) {
          setFailure(cause instanceof ApiError ? cause.message : 'Итоги поставки не загрузились');
        }
      });
    return () => { alive = false; };
  }, [supplyId]);

  if (failure !== null) {
    return <p className="note note--error">{failure}</p>;
  }
  if (summary === null) {
    return <p className="note">Загружаем…</p>;
  }

  const { receipts, goods, donors } = summary;
  return (
    <div>
      <h4>Поступления</h4>
      <ul>
        <li>{receiptLine('Черновики', receipts.drafts)}</li>
        <li>{receiptLine('Выполнены', receipts.done)}</li>
        <li>{receiptLine('Отменены', receipts.cancelled)}</li>
        <li>{receiptLine('Всего', receipts.total)}</li>
      </ul>
      <h4>Товары</h4>
      <ul>
        <li>{`Остаток — ${count(goods.remaining)} шт.`}</li>
        <li>{`Продано — ${count(goods.sold)} шт.`}</li>
        <li>{`Списано — ${count(goods.writtenOff)} шт.`}</li>
        <li>{`Всего — ${count(goods.total)} шт.`}</li>
      </ul>
      <h4>Авто-доноры</h4>
      <ul>
        <li>{`Всего — ${count(donors.count)} шт. (себестоимость ${count(donors.cost)} руб.)`}</li>
      </ul>
    </div>
  );
}

function receiptLine(title: string, line: ReceiptCount): string {
  return `${title} — ${count(line.count)} (на ${count(line.amount)} руб.)`;
}
