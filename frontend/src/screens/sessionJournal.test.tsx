import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { AuditJournalScreen } from './AuditJournalScreen';
import { SessionJournal } from './SessionJournal';

/**
 * Журнал сессий (вторая половина задачи 0043).
 *
 * <p>Проверяется то, что видит владелец: кто заходил, с какого устройства,
 * сколько работал и чем всё кончилось, — и, отдельно, что журнал не выдаёт
 * догадку за ответ.
 */
describe('журнал сессий', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('называет человека, устройство и то, сколько он работал', async () => {
    stubApi(page());
    render(<SessionJournal />);

    await waitFor(() => expect(rows().getByText('Сидоров')).toBeTruthy());

    // Устройство разобранное, а не строкой браузера: сырое лежит в базе
    // и видно по наведению.
    expect(screen.getByText('Chrome · Windows')).toBeTruthy();
    expect(screen.getByText('192.168.1.5')).toBeTruthy();
    expect(screen.getByText('Вышел')).toBeTruthy();
    expect(screen.getByText(/работал 1 ч 30 мин/)).toBeTruthy();
  });

  /**
   * Главное решение владельца продукта от 9 сентября 2026, и проверять его
   * надо там, где две метки расходятся.
   *
   * <p>«Длительность считается по последней активности, а не по времени
   * истечения: закрыл ноутбук в 18:00, сессия истекла в 6:00 — „работал
   * двенадцать часов“ было бы ложью». У вышедшего по кнопке обе метки
   * совпадают (нажатие «Выйти» и есть последнее действие), поэтому на его
   * строке подмена одной метки другой не видна вовсе — проверено откатом:
   * `endedAt` вместо `lastSeenAt` оставлял весь файл зелёным.
   *
   * <p>Здесь взята <b>истёкшая</b> сессия: вошёл в 18:00, последний раз был
   * активен в 18:47, недействительной стала в 19:17. Работал 47 минут,
   * а не час семнадцать.
   */
  it('считает длительность по последней активности, а не по концу сессии', async () => {
    stubApi(page());
    render(<SessionJournal />);

    await waitFor(() => expect(rows().getByText('Пётр Владельцев')).toBeTruthy());

    const row = rows().getByText('Пётр Владельцев').closest('tr') as HTMLElement;
    expect(within(row).getByText('Истекла')).toBeTruthy();
    expect(
      within(row).getByText(/работал 47 мин/),
      'длительность истёкшей сессии считается до последней активности',
    ).toBeTruthy();
    expect(
      row.textContent,
      'час семнадцать — это время до истечения, а не время работы: сессия '
      + 'догорала полчаса после того, как человек закрыл ноутбук',
    ).not.toContain('1 ч 17 мин');
  });

  /**
   * Три исхода различаются — решение владельца продукта от 9 сентября 2026.
   *
   * <p>«Вышел», «Истекла» и «Завершена: смена пароля» — три разных ответа
   * на вопрос «почему его больше не было». Сведи их в «закрыта» — и пропадёт
   * ровно то, ради чего смотрят.
   */
  it('различает выход, истечение и отзыв, а не пишет «закрыта»', async () => {
    stubApi(page());
    render(<SessionJournal />);

    await waitFor(() => expect(screen.getByText('Вышел')).toBeTruthy());
    expect(screen.getByText('Истекла')).toBeTruthy();
    expect(screen.getByText('Завершена: смена пароля')).toBeTruthy();
    expect(screen.getByText('Работает')).toBeTruthy();
  });

  /**
   * Неудачная попытка — то, ради чего журнал смотрят чаще всего.
   *
   * <p>Логин, которого у компании нет, обязан быть назван чужим: показанный
   * как имя сотрудника, подбор пароля выглядел бы своим человеком.
   */
  it('показывает отказ во входе и не выдаёт чужой логин за сотрудника', async () => {
    stubApi(page());
    render(<SessionJournal />);

    await waitFor(() => expect(rows().getByText('vzlomshchik')).toBeTruthy());
    expect(screen.getByText('Отказ во входе')).toBeTruthy();
    expect(screen.getByText('такого логина нет')).toBeTruthy();
    expect(screen.getByText('неверный логин или пароль')).toBeTruthy();
  });

  /** Незнакомая строка браузера не выдаётся за известный браузер. */
  it('неразобранное устройство называет неизвестным, а не Chrome', async () => {
    stubApi(page());
    render(<SessionJournal />);

    // Две таких строки: отказ через curl и вход без записанной строки браузера.
    await waitFor(() => expect(rows().getAllByText('Неизвестное устройство')).toHaveLength(2));
  });

  it('отбор по человеку и по исходу уходит в запрос', async () => {
    const fetch = stubApi(page());
    render(<SessionJournal />);
    await waitFor(() => expect(rows().getByText('Сидоров')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Исход'), { target: { value: 'FAILED' } });

    await waitFor(() => {
      expect(journalCalls(fetch).at(-1)?.searchParams.get('outcome')).toBe('FAILED');
    });
  });

  /**
   * Отказ сервера не выдаётся за пустой журнал.
   *
   * <p>Сначала дождаться причины, потом проверять отсутствие пустоты:
   * ожидание отсутствия текста проходит само собой на первом же кадре,
   * пока экран показывает «Загружаем…».
   */
  it('отказ сервера не выдаётся за «входов не было»', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ message: 'Журнал недоступен' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } },
    )));

    render(<SessionJournal />);

    await waitFor(() => expect(screen.getByText(/Журнал недоступен/)).toBeTruthy());
    expect(screen.queryByText('Входов пока не было')).toBeNull();
    expect(screen.queryByText('Загружаем…')).toBeNull();
  });

  /**
   * Эндпоинт без экрана — отсутствующая возможность, и вкладка тут
   * единственная дорога: журнал сессий открывается оттуда же, откуда
   * журнал изменений.
   */
  it('открывается вкладкой «Входы» на экране журнала', async () => {
    stubApi(page());
    render(<AuditJournalScreen />);

    fireEvent.click(screen.getByText('Входы'));

    await waitFor(() => expect(rows().getByText('Сидоров')).toBeTruthy());
    expect(rows().getByText('Chrome · Windows')).toBeTruthy();
  });
});

// ------------------------------------------------------------------ фикстуры

/**
 * Строки таблицы, а не вся страница: те же имена стоят в списке отбора,
 * и `getByText` находил бы по два совпадения на каждое.
 */
function rows() {
  const table = document.querySelector('table');
  expect(table).toBeTruthy();
  return within(table as HTMLElement);
}

function journalCalls(fetch: ReturnType<typeof vi.fn>): URL[] {
  return fetch.mock.calls
    .map((call) => new URL(String(call[0]), 'http://x'))
    .filter((url) => !url.pathname.endsWith('/values'));
}

function stubApi(body: unknown) {
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://x');
    const answer = url.pathname.endsWith('/values')
      ? ['Сидоров', 'Пётр Владельцев', 'vzlomshchik']
      : body;
    return new Response(JSON.stringify(answer), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

const CHROME = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
  + ' (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36';

function page() {
  return {
    total: 5,
    more: false,
    items: [
      {
        id: 5,
        who: 'Сидоров',
        unknown: false,
        role: 'SELLER',
        at: '2026-09-08T09:00:00Z',
        lastSeenAt: '2026-09-08T10:30:00Z',
        endedAt: '2026-09-08T10:30:00Z',
        endReason: 'LOGOUT',
        endDetail: null,
        success: true,
        failureReason: null,
        device: 'Chrome · Windows',
        userAgent: CHROME,
        ip: '192.168.1.5',
      },
      {
        id: 4,
        who: 'vzlomshchik',
        unknown: true,
        role: null,
        at: '2026-09-08T08:20:00Z',
        lastSeenAt: null,
        endedAt: null,
        endReason: null,
        endDetail: null,
        success: false,
        failureReason: 'BAD_CREDENTIALS',
        device: null,
        userAgent: 'curl/8.4.0',
        ip: '10.0.0.9',
      },
      {
        id: 3,
        who: 'Пётр Владельцев',
        unknown: false,
        role: 'OWNER',
        at: '2026-09-07T18:00:00Z',
        lastSeenAt: '2026-09-07T18:47:00Z',
        endedAt: '2026-09-07T19:17:00Z',
        endReason: 'EXPIRED',
        endDetail: null,
        success: true,
        failureReason: null,
        device: 'Safari · iOS',
        userAgent: 'Mozilla/5.0 (iPhone)',
        ip: '95.24.1.7',
      },
      {
        id: 2,
        who: 'Иванов',
        unknown: false,
        role: 'MANAGER',
        at: '2026-09-07T10:00:00Z',
        lastSeenAt: '2026-09-07T13:00:00Z',
        endedAt: '2026-09-07T14:20:00Z',
        endReason: 'REVOKED',
        endDetail: 'смена пароля',
        success: true,
        failureReason: null,
        device: 'Яндекс.Браузер · Windows',
        userAgent: 'Mozilla/5.0 YaBrowser',
        ip: '95.24.1.8',
      },
      {
        id: 1,
        who: 'Ревизоров',
        unknown: false,
        role: 'AUDITOR',
        at: '2026-09-07T09:00:00Z',
        lastSeenAt: '2026-09-07T09:20:00Z',
        endedAt: null,
        endReason: null,
        endDetail: null,
        success: true,
        failureReason: null,
        device: null,
        userAgent: null,
        ip: null,
      },
    ],
  };
}
