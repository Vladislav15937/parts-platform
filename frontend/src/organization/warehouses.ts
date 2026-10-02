import { request } from '../api/client';

/**
 * Склады арендатора.
 *
 * <p>Берутся с сервера, а не из справочников приёмки: приёмка живёт офлайн
 * и держит свою копию в IndexedDB, а продажа работает только при связи.
 * Возврат оформляют на тот склад, где клиент оставил деталь, и список складов
 * тут должен быть сегодняшний, а не тот, что скачали в понедельник.
 */
export interface Warehouse {
  id: number;
  branchId: number | null;
  name: string;
  branchName: string | null;
  cells: number;
  /**
   * Текст наличия этого склада, который покупатель читает в объявлении:
   * «в наличии», «под заказ». `null` — владелец его не задавал, и тогда
   * в прайс не уходит ничего: молча ничего не выдумываем.
   */
  availabilityNote: string | null;
  /** Нижняя граница вилки дней заказа. Ноль — «забрать можно сегодня». */
  orderDaysFrom: number | null;
  /** Верхняя граница вилки. */
  orderDaysTo: number | null;
}

export function listWarehouses(): Promise<Warehouse[]> {
  return request<Warehouse[]>('/api/organization/warehouses');
}

/** Филиал: физический адрес, за которым стоят склады. */
export interface Branch {
  id: number;
  name: string;
}

/** Ячейка хранения — адрес полки, который печатают на этикетке. */
export interface Cell {
  id: number;
  code: string;
  zone: string | null;
  active: boolean;
}

export function listBranches(): Promise<Branch[]> {
  return request<Branch[]>('/api/organization/branches');
}

export function createBranch(name: string): Promise<Branch> {
  return request<Branch>('/api/organization/branches', { method: 'POST', body: { name } });
}

export function createWarehouse(name: string, branchId: number | null): Promise<Warehouse> {
  return request<Warehouse>('/api/organization/warehouses', {
    method: 'POST',
    body: { name, branchId },
  });
}

/**
 * Срок словами: «2–4 дн.», «до 4 дн.», «от 2 дн.».
 *
 * <p>`null` — срок не назван: незаданная вилка и ноль дней значат для
 * покупателя одно и то же — ждать не надо, — и «0 дн.» на экране было бы
 * шумом. Это же правило держит прайс (`DromOffer.Placement.namesLeadTime`),
 * и держат его два места: сервер пишет элементы объявления, экран —
 * слова владельцу. Разойдясь, они покажут владельцу не то, что уедет
 * покупателю.
 *
 * <p>Сокращение «дн.» не склоняется, поэтому помощник склонения здесь
 * не нужен: «2–4 дня» от «4 дней» падежом не отличить.
 */
export function leadTimeWords(
  from: number | null | undefined, to: number | null | undefined,
): string | null {
  const hasFrom = from !== null && from !== undefined && from > 0;
  const hasTo = to !== null && to !== undefined && to > 0;
  if (!hasFrom && !hasTo) {
    return null;
  }
  if (hasFrom && hasTo) {
    return from === to ? `${from} дн.` : `${from}–${to} дн.`;
  }
  return hasTo ? `до ${to} дн.` : `от ${from} дн.`;
}

/**
 * Что стоит у склада, одной строкой для таблицы.
 *
 * <p>«не задано» словами, а не пустая клетка: пустая читается как «не знаем»,
 * а мы знаем — владелец этому складу ничего не задавал, и в объявление
 * не уходит ничего.
 *
 * <p><b>Отсутствие поля считается тем же «не задано», и это не перестраховка.</b>
 * Тип описывает, что обещал сервер, а не что пришло: сборка старше этой
 * задачи полей не отдаёт вовсе, и `undefined !== null` — то есть проверка
 * на `null` их пропускает, а `.trim()` на `undefined` роняет **весь экран**.
 * Поймано полным прогоном: падал не свой тест, а два чужих файла, где
 * фикстура склада заведена до этих полей. Ровно тот класс, о котором
 * `docs/frontend-rules.md` §3 и предупреждает.
 */
export function availabilitySummary(warehouse: Warehouse): string {
  const said: string[] = [];
  const note = warehouse.availabilityNote ?? null;
  if (note !== null && note.trim() !== '') {
    said.push(note);
  }
  const term = leadTimeWords(warehouse.orderDaysFrom, warehouse.orderDaysTo);
  if (term !== null) {
    said.push(term);
  }
  return said.length === 0 ? 'не задано' : said.join(' · ');
}

/**
 * Текст наличия склада и вилка дней заказа.
 *
 * <p>Склады у клиента в разных местах, и деталь с дальнего едет несколько
 * дней: в прайс уходило булево «есть», и покупатель узнавал про срок уже
 * по телефону.
 *
 * <p>Пустой текст и пустые дни уезжают `null`, а не пустой строкой и не
 * нулём: `null` означает «не задано» — склад ведёт себя как раньше, —
 * а ноль дней значит «забрать можно сегодня». Превратив одно в другое
 * здесь, экран отдал бы владельцу не то, что тот выбрал.
 */
export function setWarehouseAvailability(
  warehouseId: number,
  availabilityNote: string | null,
  orderDaysFrom: number | null,
  orderDaysTo: number | null,
): Promise<Warehouse> {
  return request<Warehouse>(`/api/organization/warehouses/${warehouseId}/availability`, {
    method: 'PUT',
    body: { availabilityNote, orderDaysFrom, orderDaysTo },
  });
}

export function listCells(warehouseId: number): Promise<Cell[]> {
  return request<Cell[]>(`/api/organization/warehouses/${warehouseId}/cells`);
}

/**
 * Заводит ячейки списком.
 *
 * <p>Стеллаж — это два-три десятка адресов подряд, и по одному их не заведёт
 * никто: коды уедут в примечание, а поиск детали на полке вернётся к памяти
 * кладовщика. Уже существующие пропускаются, а не ломают запрос целиком.
 */
export function createCells(
  warehouseId: number, codes: string[], zone: string | null,
): Promise<Cell[]> {
  return request<Cell[]>(`/api/organization/warehouses/${warehouseId}/cells`, {
    method: 'POST',
    body: { codes, zone },
  });
}

/**
 * Буквы, которых нет в Code128 и у которых нет латинского двойника.
 *
 * <p>«А», «В», «Е», «К» и прочие похожие сканер сводит к латинице, а «Б»,
 * «Г», «Д» не отсканируются никогда. Подставить похожую нельзя: «Б-02-1»,
 * напечатанная как «B-02-1», сольётся с настоящей «В-02-1», и деталь ляжет
 * на другой стеллаж. Поэтому такие адреса лучше не заводить вовсе, и экран
 * предупреждает об этом до, а не на печати этикеток.
 */
const UNPRINTABLE = /[БГДЖЗИЙЛПФЦЧШЩЪЫЬЭЮЯбгджзийлмнптфцчшщъыьэюя]/;

export function unprintableCells(codes: string[]): string[] {
  return codes.filter((code) => UNPRINTABLE.test(code));
}
