/**
 * Локальное хранилище.
 *
 * <p>IndexedDB, а не localStorage: справочник наименований у клиента — тысячи
 * записей, а фотографии в очереди отправки — мегабайты. localStorage даёт
 * 5 МБ, синхронный и хранит только строки; blob туда не положить вовсе.
 *
 * <p>Обёртка своя, без библиотеки. Причина не в экономии зависимости,
 * а в объёме: нужны две операции над двумя хранилищами, и код обёртки короче,
 * чем настройка чужой.
 *
 * <p>Хранилища заводятся все сразу, а не по мере надобности: версия схемы
 * IndexedDB поднимается только в {@code onupgradeneeded}, и добавить хранилище
 * позже значит поднять версию и написать миграцию. Очередь отправки появится
 * на шаге 5, а место под неё готово уже здесь.
 */

const DB_NAME = 'partsflow';
const DB_VERSION = 1;

/** Справочники: одна запись под фиксированным ключом. */
export const STORE_REFERENCE = 'reference';

/** Очередь отправки: по записи на операцию. Заполняется на шаге 5. */
export const STORE_OUTBOX = 'outbox';

let connection: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  if (connection !== null) {
    return connection;
  }
  connection = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_REFERENCE)) {
        db.createObjectStore(STORE_REFERENCE);
      }
      if (!db.objectStoreNames.contains(STORE_OUTBOX)) {
        const outbox = db.createObjectStore(STORE_OUTBOX, { keyPath: 'id' });
        // Очередь разгребается по порядку постановки и фильтруется по состоянию.
        outbox.createIndex('by-state', 'state');
        outbox.createIndex('by-created', 'createdAt');
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error('IndexedDB недоступна'));
    // Браузер в приватном режиме умеет отказывать в доступе молча — тогда
    // приложение обязано работать онлайн, а не падать.
    request.onblocked = () => reject(new Error('IndexedDB заблокирована другой вкладкой'));
  });
  return connection;
}

function run<T>(
  store: string,
  mode: IDBTransactionMode,
  body: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return open().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const transaction = db.transaction(store, mode);
        const request = body(transaction.objectStore(store));

        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error ?? new Error('Ошибка IndexedDB'));
        transaction.onabort = () => reject(transaction.error ?? new Error('Транзакция отменена'));
      }),
  );
}

export function put<T>(store: string, value: T, key?: IDBValidKey): Promise<IDBValidKey> {
  return run(store, 'readwrite', (s) => (key === undefined ? s.put(value) : s.put(value, key)));
}

export function get<T>(store: string, key: IDBValidKey): Promise<T | undefined> {
  return run<T | undefined>(store, 'readonly', (s) => s.get(key) as IDBRequest<T | undefined>);
}

export function getAll<T>(store: string): Promise<T[]> {
  return run<T[]>(store, 'readonly', (s) => s.getAll() as IDBRequest<T[]>);
}

export function remove(store: string, key: IDBValidKey): Promise<undefined> {
  return run<undefined>(store, 'readwrite', (s) => s.delete(key) as IDBRequest<undefined>);
}

/**
 * Сколько места осталось.
 *
 * <p>Проверять обязательно: при исчерпании квоты браузер начинает вытеснять
 * данные молча, и приёмщик получит карточки без фотографий без единого
 * сообщения об ошибке.
 */
export async function storageEstimate(): Promise<{ usedMb: number; quotaMb: number } | null> {
  if (navigator.storage?.estimate === undefined) {
    return null;
  }
  const { usage = 0, quota = 0 } = await navigator.storage.estimate();
  return { usedMb: usage / 1024 / 1024, quotaMb: quota / 1024 / 1024 };
}
