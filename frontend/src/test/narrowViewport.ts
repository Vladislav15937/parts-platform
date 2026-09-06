/**
 * Измерение раскладки на узком экране — настоящим браузером, а не jsdom.
 *
 * <p><b>Почему не jsdom.</b> Он не считает раскладку вовсе: `scrollWidth`
 * и `clientWidth` у него всегда 0, флексы и медиазапросы не применяются.
 * Тест «страница не уезжает вбок», написанный на нём, зеленел бы на любой
 * вёрстке — то же семейство, что MockMvc против настоящего контейнера
 * в бэкенде (см. корневой `CLAUDE.md`, раздел «Тесты»).
 *
 * <p><b>Почему узкий viewport, а не узкое окно.</b> Окно браузера ниже
 * некоторой ширины не сжимается, медиазапрос молча не срабатывает, и проверка
 * молчит вместе с ним. Здесь ширина задаётся странице напрямую
 * (`setViewport`), поэтому `(min-width: 900px)` честно выключен.
 *
 * <p><b>Оболочка настоящая.</b> Ширину `.screen` определяет не сам экран,
 * а `.app → .app__main → .app__content` вокруг него: `.screen` — элемент
 * flex-колонки, он не обрезается по родителю, а растёт под содержимое,
 * и уезжает вбок страница целиком, вместе с рельсом и шапкой. Измерять
 * экран без оболочки значит измерять не то, что видит человек.
 *
 * <p><b>Стилей и путей тут нет ни одного своего.</b> CSS берётся тот же,
 * что уходит в сборку (`?raw` — импорт файла текстом), а Chrome ищется
 * установленный: `puppeteer-core` браузер с собой не тащит. Типов Node
 * в сборке фронтенда нет, поэтому ни `node:fs`, ни `node:child_process`
 * здесь не используются — путь проверяется попыткой запуска.
 */
import puppeteer, { type Browser } from 'puppeteer-core';

import CSS from '../app.css?raw';

/**
 * Ширина телефона, на которой меряются экраны.
 *
 * <p>390, а не 386: 386 — это то, что намерялось на конкретном стенде
 * при разборе задачи 0027, а 390 — ширина iPhone, на который смотрит
 * приёмщик. Разница в четыре пикселя ничего не меняет ни в одной находке,
 * зато число перестаёт быть случайным.
 */
export const PHONE_WIDTH = 390;

/**
 * Где искать Chrome. На рабочей машине он в `/Applications`, на ubuntu-раннере
 * CI — в `/usr/bin`. Свой путь задаётся переменной `CHROME_PATH` (или
 * `CHROME_BIN`, которую ставит образ раннера).
 */
const KNOWN_PATHS = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
];

/**
 * Переменные окружения через приведение: `@types/node` в сборке фронтенда нет
 * и заводить их ради одного пути дороже правки — так же сделан доступ
 * к `process` в `inventoryJournal.test.tsx`.
 */
function envChrome(): string[] {
  const proc = (globalThis as unknown as {
    process?: { env?: Record<string, string | undefined> };
  }).process;
  const env = proc?.env;
  // `CHROME_BIN` ставит образ ubuntu-раннера сам — берём и его, чтобы
  // проверка не зависела от того, куда именно он положил браузер.
  return [env?.['CHROME_PATH'], env?.['CHROME_BIN']]
    .filter((p): p is string => p !== undefined && p !== '');
}

let browser: Browser | null = null;

export async function openBrowser(): Promise<void> {
  if (browser !== null) return;

  const candidates = [...envChrome(), ...KNOWN_PATHS];

  const failures: string[] = [];
  for (const executablePath of candidates) {
    try {
      browser = await puppeteer.launch({
        executablePath,
        headless: true,
        // На раннере CI процесс идёт от root в контейнере, где песочница
        // Chrome не поднимается, а /dev/shm мал для отрисовки.
        args: ['--no-sandbox', '--disable-dev-shm-usage'],
      });
      return;
    } catch (e) {
      failures.push(`${executablePath}: ${String(e)}`);
    }
  }
  // Падаем со словами, а не пропускаем тест: пропущенная проверка раскладки
  // ничем не лучше отсутствующей — она так же ничего не стережёт.
  throw new Error(
    'Chrome не найден: тесты раскладки меряют настоящим браузером.\n'
    + 'Поставьте Chrome или укажите путь переменной CHROME_PATH.\n'
    + failures.join('\n'),
  );
}

export async function closeBrowser(): Promise<void> {
  if (browser !== null) {
    await browser.close();
    browser = null;
  }
}

/** Оболочка приложения вокруг экрана — та же, что в `HomeScreen`. */
function shell(screenHtml: string): string {
  return `<div class="app">
    <nav class="rail">
      <span class="rail__brand">partsflow</span>
      <button class="rail__item">Приёмка</button>
      <button class="rail__item rail__item--active">Склад</button>
      <button class="rail__item">Продажа</button>
    </nav>
    <div class="app__main">
      <header class="topbar">
        <div class="topbar__title">Раздел</div>
        <div><strong>Хозяин</strong><span class="muted"> · владелец</span></div>
        <span class="badge badge--online">на связи</span>
      </header>
      <main class="app__content">${screenHtml}</main>
    </div>
  </div>`;
}

export interface PageWidth {
  scrollWidth: number;
  clientWidth: number;
}

/**
 * Ставит разметку экрана в оболочку приложения, открывает её при заданной
 * ширине и возвращает ширину прокрутки страницы против ширины окна.
 */
export async function measurePage(screenHtml: string, width = PHONE_WIDTH): Promise<PageWidth> {
  if (browser === null) throw new Error('openBrowser() не вызван');
  const page = await browser.newPage();
  try {
    await page.setViewport({ width, height: 800 });
    await page.setContent(
      `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${shell(screenHtml)}`,
      { waitUntil: 'load' },
    );
    return await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
  } finally {
    await page.close();
  }
}
