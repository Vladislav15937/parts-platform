import { CONDITION, type CatalogRow } from './catalog';

const SIDE_LR: Record<string, string> = { LEFT: 'лев.', RIGHT: 'прав.' };
const SIDE_FR: Record<string, string> = { FRONT: 'перед.', REAR: 'задн.' };

/**
 * Поля карточки товара — списком, а не разметкой.
 *
 * Вынесено из экрана ради одного: паритет с карточкой прежней системы
 * должен быть виден тесту. Пока сборка полей жила в JSX, единственным
 * способом узнать, что поле перестало показываться, был взгляд на экран —
 * а полей тут сорок, и пропажу одного глазами не ловят.
 */
export function cardFields(row: CatalogRow): Array<[string, string]> {
  // Только там, где что-то лежит: строка «Кузов —» не сообщает ничего,
  // а таких строк набирается больше, чем заполненных.
  const rows: Array<[string, string]> = [];
  const add = (title: string, value: string | number | null) => {
    if (value !== null && value !== undefined && String(value) !== '') {
      rows.push([title, String(value)]);
    }
  };

  add('Номер товара', row.code);
  // Написание вида детали — не то же, что заголовок: «фара лев.» против
  // «Фара Toyota Corolla Fielder 2002 перед. лев. (б/у)». По написанию
  // разбирают нераспознанные, и в кабинете это отдельное поле.
  add('Наименование', row.partName);
  add('Состояние', row.condition === null ? null : CONDITION[row.condition] ?? row.condition);
  add('Поставка', row.supply);
  add('Оценка состояния', row.qualityGrade);
  add('Марка', row.brand);
  add('Модель', row.model);
  add('Модель кузова', row.body);
  add('Модель двигателя', row.engine);
  add('Год выпуска', row.year);
  add('Комплектация', row.equipment);
  // Поколение и его годы — одной строкой, как в кабинете: «1 пок. 2000—2002».
  // Годы отдельным полем сообщают меньше, чем занимают: без поколения они
  // повторяют год выпуска, а с ним читаются только вместе.
  add('Поколение', generationOf(row));
  add('Номер донора', row.donorCode);
  add('Передний / Задний', row.sideFr === null ? null : SIDE_FR[row.sideFr] ?? row.sideFr);
  add('Левый / Правый', row.sideLr === null ? null : SIDE_LR[row.sideLr] ?? row.sideLr);
  add('Цвет', row.color);
  add('Секция', row.section);
  add('Производитель', row.manufacturer);
  add('Номер производителя', row.oem);
  add('Кросс-номера', row.crosses);
  // Установка на месте — отдельная услуга и отдельные деньги: она есть
  // в таблице товаров, и карточка обязана её называть.
  add('Цена установки',
      row.installationPrice === null ? null : `${row.installationPrice.toLocaleString('ru-RU')} ₽`);
  add('Маркировка', row.marking);
  add('Заметка', row.note);
  // Текстовый блок уезжает покупателю, в отличие от заметки и комментария:
  // те пишут для себя.
  add('Текстовый блок', row.textBlock);
  add('Видео', row.videoUrl);
  add('Вес товара', row.weightKg === null ? null : `${row.weightKg} кг`);
  add('Габариты товара', row.dimensions);
  add('Габариты в упаковке', row.packageDimensions);
  add('Вес в упаковке', row.packageWeightKg === null ? null : `${row.packageWeightKg} кг`);
  add('Ст. баркод', row.barcode);
  // Номер, по которому деталь помнит переехавший клиент.
  add('Старые данные', row.legacyCode);
  add('Выгружать', row.published === null ? null : row.published ? 'Везде' : 'Нет');
  add('Количество фото', row.photoCount === 0 ? null : row.photoCount);
  // Кто и когда трогал карточку — внизу, а не среди свойств детали:
  // это про документ, а не про запчасть.
  add('Создан', day(row.createdAt));
  add('Изменён', day(row.updatedAt));
  add('Кто изменил', row.updatedByName);
  add('Цена изменена в', day(row.priceChangedAt));
  add('Кто изменил цену', row.priceChangedByName);

  return rows;
}

/**
 * Поколение и его годы — одной строкой, но без повтора.
 *
 * <p>В поставляемом справочнике поколение **называется** диапазоном лет:
 * все 12 430 записей — «1986—1990», «1966—н.в.». Приписывая к имени те же
 * годы из соседних колонок, карточка печатала «2006—2008 2006—2008» — одно
 * и то же дважды, будто это две разные величины. Годы дописываются, только
 * если имя их не содержит: у поколения с настоящим именем («XV40») они
 * по-прежнему нужны.
 */
function generationOf(row: CatalogRow): string | null {
  const years = row.yearFrom === null ? null : `${row.yearFrom}—${row.yearTo ?? ''}`;
  if (row.generation === null || row.generation === '') return years;
  if (years === null || row.generation.includes(String(row.yearFrom))) {
    return row.generation;
  }
  return `${row.generation} ${years}`;
}

function day(value: string | null): string | null {
  return value === null ? null : new Date(value).toLocaleDateString('ru-RU');
}
