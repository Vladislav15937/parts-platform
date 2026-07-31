/*
 * Service Worker: только офлайн-старт оболочки.
 *
 * Написан руками, а не сгенерирован плагином, из-за одного запрета, который
 * должен быть виден: API не кэшируется никогда. Закэшированный ответ склада
 * означает, что продавец видит деталь, которой нет, а приёмщик — остаток,
 * который уже изменился. Это дороже любого удобства офлайн-чтения.
 *
 * Зачем офлайн-старт вообще: без него приложение без связи не откроется,
 * и станет недоступна очередь отправки — то есть работа приёмщика, уже
 * сделанная, но не ушедшая на сервер.
 */

const CACHE = 'partsflow-shell-v1';

/**
 * Точки входа. Хеши файлов сборки заранее неизвестны, поэтому они попадают
 * в кэш при первой загрузке, а не предзагружаются списком. Первый запуск
 * обязан быть онлайн — на складе это так и есть: приложение открывают
 * за столом, а в ангар уходят уже с ним.
 */
const SHELL = ['/', '/index.html', '/manifest.webmanifest'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      // Оболочка не закачалась — это не повод не устанавливаться: онлайн
      // приложение будет работать, а кэш наполнится при первой загрузке.
      .catch(() => undefined)
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) => Promise.all(names.filter((name) => name !== CACHE).map((n) => caches.delete(n))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);

  // Первое и главное правило: API мимо кэша целиком, включая ошибки.
  if (url.pathname.startsWith('/api/')) {
    return;
  }
  // Чужие источники нас не касаются: подписанные ссылки на фотографии ведут
  // в хранилище, и кэшировать их нельзя — они истекают.
  if (url.origin !== self.location.origin) {
    return;
  }
  if (request.method !== 'GET') {
    return;
  }

  // Навигация: сеть, а при её отсутствии — оболочка из кэша. Так обновления
  // приезжают сразу, а без связи приложение всё равно открывается.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).catch(() =>
        caches.match('/index.html').then((cached) => cached ?? Response.error()),
      ),
    );
    return;
  }

  // Статика: из кэша, если есть, иначе из сети с сохранением. Файлы сборки
  // содержат хеш в имени, поэтому устаревшими они не бывают.
  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached !== undefined) {
        return cached;
      }
      return fetch(request).then((response) => {
        if (response.ok && response.type === 'basic') {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      });
    }),
  );
});
