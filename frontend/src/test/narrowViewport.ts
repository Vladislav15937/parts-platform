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
 * Ширины отдельных элементов, а не страницы целиком.
 *
 * <p><b>Зачем отдельно от `measurePage`.</b> Ширина страницы отвечает
 * на вопрос «уезжает ли вбок», и только на него. Обратная сторона той же
 * правки — «поле не сжато до нечитаемого» — ей не видна вовсе: ряд, в котором
 * двенадцать полей сели по 84 пикселя, помещается в телефон идеально
 * (замерено на пробном варианте правки 0041). Проверять это надо тем же
 * настоящим браузером и на той же разметке: jsdom здесь снова отдал бы нули.
 */
export async function measureWidths(
  screenHtml: string,
  selector: string,
  width = PHONE_WIDTH,
): Promise<number[]> {
  if (browser === null) throw new Error('openBrowser() не вызван');
  const page = await browser.newPage();
  try {
    await page.setViewport({ width, height: 2000 });
    await page.setContent(
      `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${shell(screenHtml)}`,
      { waitUntil: 'load' },
    );
    return await page.evaluate(
      (css: string) => [...document.querySelectorAll(css)]
        .map((element) => Math.round(element.getBoundingClientRect().width)),
      selector,
    );
  } finally {
    await page.close();
  }
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

/**
 * Элементы, чей текст шире их собственной коробки: `scrollWidth` больше
 * `clientWidth`.
 *
 * <p>Ни ширина страницы, ни прямоугольник элемента этого не видят: flex-элемент
 * с `min-width: 0` сжимается по месту, а неразрывный текст из него вылезает —
 * внутри окна с `overflow: hidden` его просто не видно, и страница не уезжает
 * (задача 0044, причина погашенной кнопки в карточке позиции).
 */
export async function measureOverflow(
  screenHtml: string,
  selector: string,
  width = PHONE_WIDTH,
): Promise<Array<{ text: string; scrollWidth: number; clientWidth: number }>> {
  if (browser === null) throw new Error('openBrowser() не вызван');
  const page = await browser.newPage();
  try {
    await page.setViewport({ width, height: 800 });
    await page.setContent(
      `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${shell(screenHtml)}`,
      { waitUntil: 'load' },
    );
    return await page.evaluate(
      (css: string) => [...document.querySelectorAll(css)]
        .filter((element) => element.scrollWidth > element.clientWidth + 1)
        .map((element) => ({
          text: (element.textContent ?? '').slice(0, 40),
          scrollWidth: element.scrollWidth,
          clientWidth: element.clientWidth,
        })),
      selector,
    );
  } finally {
    await page.close();
  }
}

export interface Box {
  left: number;
  right: number;
  width: number;
  height: number;
}

export interface Layout extends PageWidth {
  /** Прямоугольники элементов по каждому селектору, в порядке документа. */
  boxes: Record<string, Box[]>;
}

/**
 * Ширина страницы и прямоугольники элементов — одной загрузкой.
 *
 * <p><b>Зачем высота.</b> Ширина страницы отвечает «уезжает ли вбок»,
 * ширина колонки — «не сжата ли до нечитаемого», но ни то, ни другое не видит
 * строку, выросшую в полтора экрана: строка выдачи на 360 px не шире экрана
 * и всё равно занимала 420 px высоты (задача 0328). Высота строки — то, что
 * продавец листает пальцем, и мерить её надо тем же браузером.
 */
export async function measureLayout(
  screenHtml: string,
  selectors: string[],
  width = PHONE_WIDTH,
): Promise<Layout> {
  if (browser === null) throw new Error('openBrowser() не вызван');
  const page = await browser.newPage();
  try {
    await page.setViewport({ width, height: 800 });
    await page.setContent(
      `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${shell(screenHtml)}`,
      { waitUntil: 'load' },
    );
    return await page.evaluate((list: string[]) => {
      const boxes: Record<string, Box[]> = {};
      for (const css of list) {
        boxes[css] = [...document.querySelectorAll(css)].map((element) => {
          const rect = element.getBoundingClientRect();
          return {
            left: Math.round(rect.left),
            right: Math.round(rect.right),
            width: Math.round(rect.width),
            height: Math.round(rect.height),
          };
        });
      }
      return {
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        boxes,
      };
    }, selectors);
  } finally {
    await page.close();
  }
}

/** Прямоугольник элемента с вертикалью и началом текста — видно, что с чем на одной строке. */
export interface Rect {
  text: string;
  top: number;
  bottom: number;
}

/**
 * Прямоугольники элементов с вертикалью (задача 0044, живой прогон волны 24.10c):
 * две подписи под кнопками роли склеивались в одно слово — ширина этого не видит,
 * видно только, что вторая начинается на той же строке, где кончилась первая.
 */
export async function measureRects(
  screenHtml: string,
  selector: string,
  width = PHONE_WIDTH,
): Promise<Rect[]> {
  if (browser === null) throw new Error('openBrowser() не вызван');
  const page = await browser.newPage();
  try {
    await page.setViewport({ width, height: 800 });
    await page.setContent(
      `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${shell(screenHtml)}`,
      { waitUntil: 'load' },
    );
    return await page.evaluate((css: string) => [...document.querySelectorAll(css)].map((element) => {
      const rects = [...element.getClientRects()];
      const first = rects[0];
      const last = rects[rects.length - 1];
      return {
        text: (element.textContent ?? '').slice(0, 40),
        top: Math.round(first?.top ?? 0),
        bottom: Math.round(last?.bottom ?? 0),
      };
    }), selector);
  } finally {
    await page.close();
  }
}

/**
 * Слова, разорванные переносом посреди (задача 0044, живой прогон волны 24.10c:
 * «недоставленно/е», «администратор/а» в «Правах» на 390). Каждое слово текста
 * под селектором меряется своим диапазоном: слово, чьи куски легли на разные
 * строки, — разорвано. Ширина страницы и выход за край этого не видят вовсе.
 */
export async function measureBrokenWords(
  screenHtml: string,
  selector: string,
  width = PHONE_WIDTH,
): Promise<string[]> {
  if (browser === null) throw new Error('openBrowser() не вызван');
  const page = await browser.newPage();
  try {
    await page.setViewport({ width, height: 800 });
    await page.setContent(
      `<!doctype html><meta charset="utf-8"><style>${CSS}</style>${shell(screenHtml)}`,
      { waitUntil: 'load' },
    );
    return await page.evaluate((css: string) => {
      const broken: string[] = [];
      for (const root of document.querySelectorAll(css)) {
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
          const text = node.textContent ?? '';
          for (const match of text.matchAll(/[\p{L}\p{N}]+/gu)) {
            const range = document.createRange();
            range.setStart(node, match.index ?? 0);
            range.setEnd(node, (match.index ?? 0) + match[0].length);
            const tops = new Set([...range.getClientRects()]
              .filter((r) => r.width > 0).map((r) => Math.round(r.top)));
            if (tops.size > 1) broken.push(match[0]);
          }
        }
      }
      return broken;
    }, selector);
  } finally {
    await page.close();
  }
}
