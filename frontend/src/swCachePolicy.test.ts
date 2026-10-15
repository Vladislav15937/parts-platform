/**
 * Service Worker кэширует только оболочку и файлы сборки с хешем (задача 0305).
 *
 * sw.js лежит в public/ и не модуль: читаем исходник и исполняем его с
 * поддельным `self`, как это делает браузер. Тест зовёт настоящий обработчик
 * `fetch`, а не копию правила, — иначе откат sw.js его не краснил бы.
 */
import { describe, expect, it } from 'vitest';
import source from '../public/sw.js?raw';

type Handler = (event: unknown) => void;

function loadWorker() {
  const handlers: Record<string, Handler> = {};
  const stored = new Map<string, unknown>();
  const cacheNames = ['partsflow-shell-v1', 'partsflow-shell-v2', 'other'];
  const deleted: string[] = [];
  const cache = {
    addAll: async () => undefined,
    put: async (req: { url: string }, res: unknown) => {
      stored.set(req.url, res);
    },
  };
  const fakeCaches = {
    open: async () => cache,
    match: async (req: { url: string } | string) => stored.get(typeof req === 'string' ? req : req.url),
    keys: async () => cacheNames,
    delete: async (name: string) => {
      deleted.push(name);
      return true;
    },
  };
  const self = {
    location: { origin: 'https://parts.example' },
    addEventListener: (type: string, h: Handler) => {
      handlers[type] = h;
    },
    skipWaiting: async () => undefined,
    clients: { claim: async () => undefined },
  };
  new Function('self', 'caches', 'fetch', 'Response', source)(
    self,
    fakeCaches,
    async () => ({ ok: true, type: 'basic', clone: () => ({}) }),
    { error: () => ({}) },
  );
  return { handlers, deleted };
}

function fetchEvent(path: string, mode = 'cors') {
  let responded = false;
  const event = {
    request: { url: `https://parts.example${path}`, method: 'GET', mode },
    respondWith: () => {
      responded = true;
    },
  };
  return { event, wasHandled: () => responded };
}

describe('Service Worker: что идёт через кэш', () => {
  const bypass = [
    '/feeds/drom/smoke/tok.xml',
    '/feeds/drom/smoke/tok/low.xml',
    '/feeds/drom/smoke/tok/photo/1.jpg',
    '/feeds/drom/smoke/tok/donor-photo/1.jpg',
    '/s/smoke/tok',
    '/api/parts',
    '/something/new/outside/api.csv',
  ];
  it.each(bypass)('%s идёт мимо Service Worker', (path) => {
    const { handlers } = loadWorker();
    const { event, wasHandled } = fetchEvent(path);
    handlers.fetch!(event);
    expect(wasHandled()).toBe(false);
  });

  it.each(['/assets/index-abc123.js', '/assets/style-9f8e7d.css', '/manifest.webmanifest', '/index.html'])(
    '%s обслуживает Service Worker',
    (path) => {
      const { handlers } = loadWorker();
      const { event, wasHandled } = fetchEvent(path);
      handlers.fetch!(event);
      expect(wasHandled()).toBe(true);
    },
  );

  it('при активации удаляет все кэши, кроме текущего (v1 с копиями прайса уходит)', async () => {
    const { handlers, deleted } = loadWorker();
    let work: Promise<unknown> = Promise.resolve();
    handlers.activate!({ waitUntil: (p: Promise<unknown>) => (work = p) });
    await work;
    expect(deleted).toContain('partsflow-shell-v1');
    expect(deleted).not.toContain('partsflow-shell-v2');
  });
});
