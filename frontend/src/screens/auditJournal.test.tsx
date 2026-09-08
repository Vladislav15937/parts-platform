import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { AuditJournalScreen } from './AuditJournalScreen';

/**
 * Журнал действий организации (задача 0043).
 *
 * <p>Проверяется то, что видит владелец: словами человека, а не полями
 * таблицы, — и, отдельно, что журнал не выдумывает того, чего не знает.
 *
 * <p><b>Отбор проверяется по отправленному запросу, а не по показанным
 * строкам:</b> заглушка отвечает одним и тем же, и утверждение «после выбора
 * видно то, что выбрали» прошло бы и на экране, который отбор не отправляет
 * вовсе. Настоящий отбор стерегут тесты сервера.
 */
describe('журнал действий организации', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('говорит, кто, что и с чего на что поменял — словами, а не полями', async () => {
    stubApi(page());
    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText('Владимир Петров')).toBeTruthy());

    // Роль словом и та, что была на момент правки: сервер отдаёт код,
    // а называет его общий словарь ролей.
    expect(screen.getByText('Менеджер')).toBeTruthy();
    expect(screen.getByText('Фара Toyota Camry 2006 перед. лев. (б/у)')).toBeTruthy();
    expect(screen.getByText(/A7K3M2/)).toBeTruthy();
    expect(screen.getByText(/Цена: 5000 →/)).toBeTruthy();
    expect(screen.getByText('4500')).toBeTruthy();
  });

  /**
   * Доказательство откатом, названное задачей дословно: подставленный автор
   * хуже отсутствующего — он выглядит ответом на вопрос «кто», будучи
   * догадкой.
   */
  it('правку без записанного автора показывает прочерком, а не чьим-то именем', async () => {
    stubApi(page());
    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText('Мару Групп Владивосток')).toBeTruthy());

    const row = screen.getByText('Мару Групп Владивосток').closest('tr');
    expect(row).toBeTruthy();
    // Два прочерка в строке: неизвестный автор и неизвестная его роль.
    expect(row?.textContent).toContain('—');
    expect(row?.textContent).not.toContain('Владимир Петров');
  });

  /** Слово состояния берётся из общего словаря, а не показывается кодом. */
  it('состояние сделки называет словом, а не кодом', async () => {
    stubApi(page());
    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText(/Состояние: Отложена →/)).toBeTruthy());
    expect(screen.getByText('Отменена')).toBeTruthy();
    expect(screen.queryByText(/RESERVED/)).toBeNull();
    expect(screen.queryByText(/CANCELLED/)).toBeNull();
  });

  it('событие без полей называет себя, а не перечисляет пустые поля', async () => {
    stubApi(page());
    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText('Платёж записан')).toBeTruthy());
    expect(screen.getByText(/Сделка №1274/)).toBeTruthy();
  });

  it('отбор по человеку уходит в запрос', async () => {
    const fetch = stubApi(page());
    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText('Владимир Петров')).toBeTruthy());

    const menu = document.querySelectorAll('.th__menu')[0];
    expect(menu).toBeTruthy();
    fireEvent.click(menu as Element);

    await waitFor(() => expect(document.querySelector('.value-picker')).toBeTruthy());
    const choice = [...document.querySelectorAll('.value-picker button')]
      .find((button) => button.textContent === 'Владимир Петров');
    expect(choice).toBeTruthy();
    fireEvent.click(choice as Element);

    await waitFor(() => {
      const asked = journalCalls(fetch).at(-1);
      expect(asked?.searchParams.get('author')).toBe('Владимир Петров');
    });
  });

  it('период уходит в запрос границами дня, а не голой датой', async () => {
    const fetch = stubApi(page());
    render(<AuditJournalScreen />);
    await waitFor(() => expect(screen.getByText('Владимир Петров')).toBeTruthy());

    fireEvent.change(document.querySelectorAll('input[type="date"]')[0] as Element,
      { target: { value: '2026-09-01' } });

    await waitFor(() => {
      const from = journalCalls(fetch).at(-1)?.searchParams.get('from');
      expect(from).toBeTruthy();
      // Полночь местного дня, а не «2026-09-01»: восточнее Гринвича голая
      // дата — это уже второе сентября.
      expect(new Date(String(from)).getDate()).toBe(1);
    });
  });

  /**
   * Отказ сервера не выдаётся за пустой журнал.
   *
   * <p>Сначала дождаться причины, потом проверять отсутствие пустоты:
   * ожидание отсутствия текста проходит само собой на первом же кадре,
   * пока экран показывает «Загружаем…».
   */
  it('отказ сервера не выдаётся за пустой журнал', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ message: 'Журнал недоступен' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } },
    )));

    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText(/Журнал недоступен/)).toBeTruthy());
    expect(screen.queryByText('В журнале пока пусто')).toBeNull();
    expect(screen.queryByText('Загружаем…')).toBeNull();
  });

  /**
   * Журнал видит не всё, и молчать об этом нельзя: спрашивают его как раз
   * тогда, когда подозревают правку мимо приложения.
   */
  it('говорит, что правки мимо приложения в него не попадают', async () => {
    stubApi(page());
    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText('Владимир Петров')).toBeTruthy());
    expect(screen.getByText(/сделанные напрямую в базе, в него не попадают/)).toBeTruthy();
  });

  it('обрезанный счёт называет себя обрезанным, а не выдаёт за точный', async () => {
    stubApi({ ...page(), total: 2000, capped: true, more: true });
    render(<AuditJournalScreen />);

    await waitFor(() => expect(screen.getByText(/более чем 2 000/)).toBeTruthy());
    expect(screen.getByText(/уточните отбор/)).toBeTruthy();
  });
});

// ------------------------------------------------------------------ фикстуры

function journalCalls(fetch: ReturnType<typeof vi.fn>): URL[] {
  return fetch.mock.calls
    .map((call) => new URL(String(call[0]), 'http://x'))
    .filter((url) => !url.pathname.endsWith('/values'));
}

/**
 * Значения меню и страница журнала едут разными адресами под одним префиксом,
 * а у каждой колонки меню — свой список: заглушка, отвечающая на все три
 * одинаково, пропустила бы экран, который спрашивает не ту колонку.
 */
const VALUES: Record<string, string[]> = {
  author: ['Владимир Петров', 'Екатерина Александрова'],
  field: ['Заметка', 'Состояние', 'Цена'],
  kind: ['Товар', 'Сделка', 'Позиция сделки', 'Платёж', 'Затрата по машине'],
};

function stubApi(body: unknown) {
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://x');
    const answer = url.pathname.endsWith('/values')
      ? VALUES[url.searchParams.get('column') ?? ''] ?? []
      : body;
    return new Response(JSON.stringify(answer), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

function page() {
  return {
    total: 3,
    capped: false,
    more: false,
    filterable: ['author', 'field'],
    items: [
      {
        id: 3,
        at: '2026-09-08T17:42:00Z',
        author: 'Владимир Петров',
        authorRole: 'MANAGER',
        kind: 'Товар',
        subject: 'Фара Toyota Camry 2006 перед. лев. (б/у)',
        subjectCode: 'A7K3M2',
        context: null,
        action: null,
        changes: [
          { table: 'part', column: 'price', label: 'Цена', before: '5000', after: '4500' },
        ],
      },
      {
        id: 2,
        at: '2026-09-08T16:10:00Z',
        author: null,
        authorRole: null,
        kind: 'Сделка',
        subject: 'Мару Групп Владивосток',
        subjectCode: '№1274',
        context: null,
        action: null,
        changes: [
          {
            table: 'deal', column: 'status', label: 'Состояние',
            before: 'RESERVED', after: 'CANCELLED',
          },
        ],
      },
      {
        id: 1,
        at: '2026-09-08T09:05:00Z',
        author: 'Екатерина Александрова',
        authorRole: 'STOREKEEPER',
        kind: 'Платёж',
        subject: null,
        subjectCode: 'запись №88',
        context: 'Сделка №1274',
        action: 'Платёж записан',
        changes: [],
      },
    ],
  };
}
