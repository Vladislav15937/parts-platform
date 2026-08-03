import type { PartDonor } from '../inventory/catalog';

/**
 * Машина, с которой снята позиция.
 *
 * <p>До этого карточка сообщала «Донор задан» — отметку о том, что данные
 * есть, вместо самих данных. А по телефону спрашивают именно их: подойдёт ли
 * деталь, решает не марка, а руль, коробка и привод машины, с которой её
 * сняли.
 *
 * <p>Пустые поля не показываются — по той же причине, что и в описании
 * позиции: двадцать строк, из которых заполнены шесть, это шесть строк,
 * потерянных среди прочерков.
 */
export function PartDonorView({ donor, onParts }: {
  donor: PartDonor | null;
  /** Показать остальные детали с этой машины; без обработчика — не предлагаем. */
  onParts: ((donorCode: string) => void) | undefined;
}) {
  if (donor === null) {
    return <p className="muted">Загружаем…</p>;
  }

  const rows: Array<[string, string]> = [];
  const add = (label: string, value: string | number | null) => {
    if (value !== null && String(value).trim() !== '') {
      rows.push([label, String(value)]);
    }
  };

  add('Номер машины', donor.code);
  add('Состояние', donor.status);
  add('Поставка', donor.supply);
  add('Марка', donor.brand);
  add('Модель', donor.model);
  add('Поколение', donor.generation);
  add('Кузов', donor.bodyCode);
  add('Двигатель', donor.engineCode);
  add('Год выпуска', donor.year);
  add('Расположение руля', donor.steering);
  add('КПП', donor.transmission);
  add('Привод', donor.driveType);
  add('Цвет', donor.color);
  add('Комплектация', donor.equipmentCode);
  // Пробег с разделителями разрядов: «158475» глазами не читается.
  add('Пробег, км', donor.mileageKm === null ? null : donor.mileageKm.toLocaleString('ru-RU'));
  add('VIN', donor.vin);
  add('Где стоит', donor.location);
  add('Примечание', donor.note);

  return (
    <div className="donor-view">
      {/* Число снятых деталей отвечает на вопрос, который задают первым:
          разобрана машина или с неё сняли одну эту деталь. */}
      <div className="donor-view__parts">
        <b>{donor.partsCount}</b>
        <span> {plural(donor.partsCount)} с этой машины </span>
        {onParts !== undefined && donor.partsCount > 1 && (
          <button type="button" className="mark__link" onClick={() => onParts(donor.code)}>
            Посмотреть
          </button>
        )}
      </div>

      <dl className="card-view__fields">
        {rows.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function plural(count: number): string {
  const tail = count % 100;
  if (tail >= 11 && tail <= 14) {
    return 'деталей';
  }
  switch (count % 10) {
    case 1: return 'деталь';
    case 2: case 3: case 4: return 'детали';
    default: return 'деталей';
  }
}
