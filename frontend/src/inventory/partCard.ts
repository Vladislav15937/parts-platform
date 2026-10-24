import { CONDITION, SIDE_FR, SIDE_LR, qualityTitle, type Applicability, type CatalogRow } from './catalog';

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

  // Первым: это тот номер, которым деталь называют в разговоре друг с другом
  // («посмотри позицию 347»). Номер товара рядом — он про этикетку и сканер.
  add('№ позиции', row.number);
  add('Номер товара', row.code);
  // Та же строка, что в шапке карточки и в поле «Наименование» формы правки
  // (задача 0311): здесь стояло написание вида детали, и одним словом
  // назывались две разные строки — «фара левая» в просмотре и «фара левая
  // (б/у)» в правке.
  // Как хранится — с пометкой «(б/у)», как у Bazon (задача 0336; показ
  // без пометки, задача 0311, отменён тем же решением).
  add('Наименование', row.title);
  // Написание вида детали — не то же, что заголовок: «фара лев.» против
  // «Фара Toyota Corolla Fielder 2002 перед. лев.». По написанию разбирают
  // нераспознанные, и в кабинете это отдельное поле.
  add('Наименование в справочнике', row.partName);
  add('Состояние', row.condition === null ? null : CONDITION[row.condition] ?? row.condition);
  add('Поставка', row.supply);
  // Словом, а не кодом: в карточке стояло «NO_DEFECTS» рядом с переведённым
  // «б/у» — внутреннее представление перед человеком (задача 0033).
  add('Оценка состояния', qualityTitle(row.qualityGrade));
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
  // «Оригинал» с карточки клиента на Дроме — его слово, дословно (задача 0277).
  add('Оригинальность', row.authenticity ?? null);
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
  // Номер объявления клиента на Дроме: по нему он сверяет перенос с кабинетом.
  add('Номер объявления Дрома', row.dromAdNumber);
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
// Экспортируется ради формы правки: там та же строка стоит неправимым
// полем, и вторая копия правила «годы не печатаются дважды» разошлась бы
// с этой на первой правке.
export function generationOf(row: CatalogRow): string | null {
  const years = row.yearFrom === null ? null : `${row.yearFrom}—${row.yearTo ?? ''}`;
  if (row.generation === null || row.generation === '') return years;
  if (years === null || row.generation.includes(String(row.yearFrom))) {
    return row.generation;
  }
  return `${row.generation} ${years}`;
}

/** Имя поколения справочника, которое само есть диапазон лет: «2013—2023», «1966—н.в.». */
const YEARS_NAME = /^\d{4}—(\d{4}|н\.в\.)?$/;

/**
 * Строка применимости словами: «Toyota 4Runner · 2013—2024».
 *
 * <p>Годы строки — годы клиента из блока «Подходит» (задача 0289), и рядом
 * с поколением, названным диапазоном лет справочника, они печатали одно и то же
 * дважды и вдобавок по-разному: «2013—2023 · 2013—2024». Поэтому при годах
 * строки имя-диапазон не печатается — годы клиента точнее; поколение
 * с настоящим именем («XV40») остаётся. Тот же довод, что у {@link generationOf}.
 */
export function fitLabel(fit: Applicability): string {
  const years = fit.yearFrom === null ? null : `${fit.yearFrom}—${fit.yearTo ?? 'н.в.'}`;
  const generation = fit.generation !== null && years !== null && YEARS_NAME.test(fit.generation)
    ? null
    : fit.generation;
  const name = [fit.brand, fit.model, generation].filter((v) => v).join(' ');
  return years === null ? name : `${name} · ${years}`;
}

function day(value: string | null): string | null {
  return value === null ? null : new Date(value).toLocaleDateString('ru-RU');
}
