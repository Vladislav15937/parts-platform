import { StrictMode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { InventoryReconcile } from './InventoryReconcile';

/**
 * Журнал пересчётов (задача 0020): список всех документов над сведением
 * расхождений, а не поиск открытой сессии по складу.
 *
 * <p>Проверяются слова и умолчания, которые задача называет дословно:
 * воронка «В работе / Выполненные / Отменённые / Все пересчёты» в этом
 * порядке и открытая по умолчанию «В работе», колонки «Номер/дата · Выборка ·
 * Статус · Посчитано · Комментарий», подвал «Пересчётов: N» и «Пересчётов
 * пока не было», и кнопки сведения расхождений, скрытые у закрытого документа
 * и у роли «Просмотр».
 *
 * <p><b>Поддельный сервер отбирает так же, как настоящий</b> — по статусам
 * из запроса. Это не украшение фикстуры: пока воронка «Выполненные» слала
 * один `status=COUNTED`, проведённый пересчёт не попадал в неё вовсе,
 * а тест, отдающий заранее подготовленный список независимо от запроса,
 * этого не увидел бы никогда.
 */
describe('журнал пересчётов', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('воронка в заданном порядке, открыта «В работе» по умолчанию', async () => {
    const requested = stubApi({ sessions: [] });

    render(<InventoryReconcile reference={reference()} />);

    await waitFor(() => expect(screen.getByText('Пересчётов пока не было')).toBeTruthy());

    const buttons = screen.getAllByRole('button', {
      name: /В работе|Выполненные|Отменённые|Все пересчёты/,
    });
    expect(buttons.map((b) => b.textContent)).toEqual([
      'В работе', 'Выполненные', 'Отменённые', 'Все пересчёты',
    ]);
    expect(requested).toEqual(['status=OPEN']);
  });

  it('колонки в заданном порядке и подвал считает по воронке', async () => {
    stubApi({
      sessions: [
        row({ id: 4, status: 'OPEN', selection: 'Ткацкая · весь склад',
              lines: 30, counted: 12 }),
      ],
    });

    render(<InventoryReconcile reference={reference()} />);

    const headers = await screen.findAllByRole('columnheader');
    expect(headers.map((h) => h.textContent)).toEqual([
      'Номер/дата', 'Выборка', 'Статус', 'Посчитано', 'Комментарий',
    ]);

    expect(screen.getByText('Ткацкая · весь склад')).toBeTruthy();
    expect(screen.getByText('Идёт подсчёт')).toBeTruthy();
    expect(screen.getByText('12 из 30')).toBeTruthy();
    expect(screen.getByText('Пересчётов: 1')).toBeTruthy();
  });

  /**
   * Пункт приёмки 1: проведённый пересчёт лежит в «Выполненные»
   * и в «Все пересчёты», а в «В работе» его нет.
   *
   * <p>Ради этого журнал и заводили. Нормально закрытый пересчёт всегда
   * `APPLIED` — «подсчёт завершён» это промежуточный шаг, — и воронка,
   * накрывающая один `COUNTED`, оставляла проведённый документ вне всех
   * трёх воронок: на вопрос «когда эту полку считали в последний раз»
   * экран отвечал пустотой.
   */
  it('«Выполненные» показывает и завершённый подсчёт, и проведённый', async () => {
    const requested = stubApi({
      sessions: [
        row({ id: 11, status: 'APPLIED', note: 'Все на месте' }),
        row({ id: 10, status: 'COUNTED' }),
        row({ id: 9, status: 'OPEN' }),
      ],
    });

    render(<InventoryReconcile reference={reference()} />);

    // «В работе» — только открытый, проведённого тут быть не должно.
    await waitFor(() => expect(screen.getByText('Идёт подсчёт')).toBeTruthy());
    expect(screen.queryByText('Проведён')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Выполненные' }));

    await waitFor(() => expect(screen.getByText('Проведён')).toBeTruthy());
    expect(screen.getByText('Подсчёт завершён')).toBeTruthy();
    expect(screen.queryByText('Идёт подсчёт')).toBeNull();
    expect(screen.getByText('Пересчётов: 2')).toBeTruthy();
    // Оба статуса перечислены в запросе: группировку задаёт экран,
    // сервер отбирает по набору.
    expect(requested[1]).toBe('status=COUNTED&status=APPLIED');

    fireEvent.click(screen.getByRole('button', { name: 'Все пересчёты' }));
    await waitFor(() => expect(screen.getByText('Пересчётов: 3')).toBeTruthy());
    expect(screen.getByText('Проведён')).toBeTruthy();
  });

  it('«Все пересчёты» шлёт запрос без фильтра', async () => {
    const requested = stubApi({ sessions: [] });

    render(<InventoryReconcile reference={reference()} />);
    await waitFor(() => expect(screen.getByText('Пересчётов пока не было')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Все пересчёты' }));
    await waitFor(() => expect(requested).toHaveLength(2));
    expect(requested[1]).toBe('');
  });

  /**
   * Журнал отдаётся страницей, и подвал говорит про воронку целиком,
   * а не про показанное: счётчик по длине списка врал бы ровно на то,
   * чего не видно, — и узнать об этом по экрану было бы нельзя.
   */
  it('обрезанный список говорит, что он обрезан, а подвал считает всю воронку', async () => {
    stubApi({
      sessions: [row({ id: 4, status: 'OPEN' }), row({ id: 3, status: 'OPEN' })],
      total: 1924,
    });

    render(<InventoryReconcile reference={reference()} />);

    await waitFor(() => expect(screen.getByText('Пересчётов: 1 924')).toBeTruthy());
    expect(screen.getByText(/Показаны первые 2 пересчёта из 1 924/)).toBeTruthy();
  });

  it('нажатие на строку открывает сведения без кнопок у отменённого пересчёта', async () => {
    stubApi({
      sessions: [row({ id: 9, status: 'CANCELLED' })],
      onDiscrepancies: () => [],
    });

    render(<InventoryReconcile reference={reference()} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Отменённые' }));
    fireEvent.click(await screen.findByText('Отменён'));

    await waitFor(() => expect(screen.getByText(/Сессия 9/)).toBeTruthy());
    expect(screen.queryByRole('button', { name: 'Завершить подсчёт' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Провести' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Отменить пересчёт' })).toBeNull();
  });

  /**
   * Пункт приёмки 6: комментарий, написанный тем, кто ходил по складу,
   * виден владельцу в колонке списка. Ради него в журнал и заходят.
   */
  it('комментарий виден в колонке списка и сохраняется у живого пересчёта', async () => {
    const posted: string[] = [];
    stubApi({
      sessions: [row({ id: 7, status: 'OPEN', note: '83619 не найден' })],
      onNote: (body) => {
        posted.push(body);
        return row({ id: 7, status: 'OPEN', note: 'Катушки не считали' });
      },
    });

    render(<InventoryReconcile reference={reference()} />);

    // В колонке списка — то, что написал человек.
    expect(await screen.findByText('83619 не найден')).toBeTruthy();

    fireEvent.click(screen.getByText('Идёт подсчёт'));
    await waitFor(() => expect(screen.getByText(/Сессия 7/)).toBeTruthy());

    // У живого пересчёта это поле ввода, а не текст.
    const field = screen.getByLabelText('Комментарий') as HTMLTextAreaElement;
    expect(field.value).toBe('83619 не найден');

    fireEvent.change(field, { target: { value: 'Катушки не считали' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить комментарий' }));

    await waitFor(() => expect(posted).toEqual(['{"note":"Катушки не считали"}']));
    await waitFor(() => expect(screen.getByText('Комментарий сохранён')).toBeTruthy());
  });

  /**
   * Пункт приёмки 7: у проведённого пересчёта комментарий показан текстом,
   * а не полем ввода. Приписка задним числом объясняла бы уже случившееся
   * не тем, что видел писавший, — и сервер её отобьёт: кнопка, которую
   * отобьют, хуже отсутствующей.
   */
  it('у проведённого пересчёта комментарий показан текстом, а не полем', async () => {
    stubApi({
      sessions: [row({ id: 8, status: 'APPLIED', note: 'Все на месте' })],
      onDiscrepancies: () => [],
    });

    render(<InventoryReconcile reference={reference()} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Все пересчёты' }));
    fireEvent.click(await screen.findByText('Проведён'));
    await waitFor(() => expect(screen.getByText(/Сессия 8/)).toBeTruthy());

    expect(screen.getByText('Комментарий: Все на месте')).toBeTruthy();
    expect(screen.queryByLabelText('Комментарий')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Сохранить комментарий' })).toBeNull();
  });

  /**
   * Пункт приёмки 8: «Просмотр» список видит, комментарий не правит.
   *
   * <p>Комментарий у сессии непустой намеренно: с пустым отсутствие поля
   * ввода доказывало бы только то, что писать нечего, и переключение прав
   * на «можно» не уронило бы ни одной проверки. Проверяется именно пара —
   * текст показан, поля нет.
   */
  it('«Просмотр» читает комментарий текстом и не сводит расхождения', async () => {
    stubApi({ sessions: [row({ id: 3, status: 'OPEN', note: 'Катушки не считали' })] });

    render(<InventoryReconcile reference={reference()} role="VIEWER" />);

    fireEvent.click(await screen.findByText('Идёт подсчёт'));

    await waitFor(() => expect(screen.getByText(/Сессия 3/)).toBeTruthy());
    expect(screen.getByText('Комментарий: Катушки не считали')).toBeTruthy();
    expect(screen.queryByLabelText('Комментарий')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Сохранить комментарий' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Завершить подсчёт' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Отменить пересчёт' })).toBeNull();
    // И поиска по складу «Просмотру» тоже нет: findOpenSession требует роль,
    // которой у него нет, и кнопка без дела читалась бы как рабочая.
    expect(screen.queryByRole('button', { name: 'Найти пересчёт' })).toBeNull();
  });

  /**
   * Ушли с вкладки, пока сервер отвечал, — экран не правит своё состояние.
   *
   * <p>`main` краснела на этом классе дважды (#47 и #56), и оба раза падал
   * не тест, а прогон: необработанный отказ «window is not defined» из
   * `setState` в размонтированном компоненте. Поэтому здесь окружение сносится
   * так же, как между файлами прогона, — `globalThis.window` убирается сразу
   * после размонтирования, — и проверяется, что необработанных отказов нет.
   */
  it('ответ сервера после ухода с вкладки не правит состояние', async () => {
    let release!: () => void;
    // Ответ на карточку задерживается до нашего разрешения: окно между
    // нажатием и ответом надо открыть руками, иначе оно закроется раньше,
    // чем мы успеем размонтировать экран.
    const hold = new Promise<void>((resolve) => { release = resolve; });
    stubApi({ sessions: [row({ id: 5, status: 'OPEN' })], hold });

    render(<InventoryReconcile reference={reference()} />);
    fireEvent.click(await screen.findByText('Идёт подсчёт'));

    // `process` берётся через приведение: типов Node в сборке фронтенда нет
    // (и заводить их ради одного теста дороже правки — на этом уже
    // спотыкалась сверка слов, уехавшая на сторону сервера).
    const proc = (globalThis as unknown as {
      process: {
        on(event: string, listener: (reason: unknown) => void): void;
        off(event: string, listener: (reason: unknown) => void): void;
      };
    }).process;

    const rejections: unknown[] = [];
    const listener = (reason: unknown) => { rejections.push(reason); };
    proc.on('unhandledRejection', listener);

    const savedWindow = globalThis.window;
    try {
      cleanup();
      // @ts-expect-error окружение теста сносится нарочно — так же, как это
      // делает прогон между файлами.
      delete globalThis.window;
      release();
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
    } finally {
      globalThis.window = savedWindow;
      proc.off('unhandledRejection', listener);
    }

    expect(rejections).toEqual([]);
  });

  /**
   * Сторож возвращается в `true`, а не только гаснет, — и это проверяется
   * в `StrictMode`, потому что иначе не видно.
   *
   * <p>`main.tsx` оборачивает приложение в `StrictMode`, а тот в разработке
   * прогоняет эффекты дважды: setup → cleanup → setup. Сторож, который
   * только гасится в уборке, после этого остаётся `false` на всю жизнь
   * компонента — экран мертвее, чем был с гонкой: строка открывается
   * в никуда, кнопки погашены навсегда, отказ проглочен. Полный прогон
   * об этом молчит: у соседнего экрана сломанный сторож прошёл все 369
   * остальных проверок.
   */
  it('в StrictMode экран остаётся живым: сторож возвращается в «смонтирован»', async () => {
    stubApi({ sessions: [row({ id: 5, status: 'OPEN', note: 'Катушки не считали' })] });

    render(
      <StrictMode>
        <InventoryReconcile reference={reference()} />
      </StrictMode>,
    );

    fireEvent.click(await screen.findByText('Идёт подсчёт'));

    // Сведения открылись — значит `openRow` дошёл до `setSession`,
    // то есть сторож после двойного прогона эффектов снова «смонтирован».
    await waitFor(() => expect(screen.getByText(/Сессия 5/)).toBeTruthy());
    expect(screen.getByLabelText('Комментарий')).toBeTruthy();
  });
});

interface Row {
  id: number;
  warehouseId: number;
  warehouseName: string;
  selection: string;
  status: 'OPEN' | 'COUNTED' | 'APPLIED' | 'CANCELLED';
  startedAt: string;
  appliedAt: string | null;
  lines: number;
  counted: number;
  /** Комментарий человека или `null` — пустой строки сервер не отдаёт. */
  note: string | null;
}

function row(overrides: Partial<Row> = {}): Row {
  return {
    id: 1, warehouseId: 2, warehouseName: 'Ткацкая', selection: 'Ткацкая · весь склад',
    status: 'OPEN', startedAt: '2026-09-05T10:00:00Z', appliedAt: null,
    lines: 1, counted: 0, note: null,
    ...overrides,
  };
}

function reference(): never {
  return {
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [], donors: [], cells: [], partNames: [],
  } as never;
}

/**
 * Поддельный сервер журнала. Отбирает по статусам из запроса — как настоящий,
 * — и отдаёт страницу `{rows, total}`.
 *
 * @returns список пришедших строк запроса, в порядке отправки
 */
function stubApi(handlers: {
  sessions: Row[];
  /** Всего в воронке, если журнал длиннее страницы. По умолчанию — сколько отдали. */
  total?: number;
  onDiscrepancies?: () => unknown[];
  /** Тело запроса приходит как есть — проверяем, что именно уехало на сервер. */
  onNote?: (body: string) => Row;
  /** Задержка ответа на карточку одной сессии — для проверки размонтирования. */
  hold?: Promise<void>;
}): string[] {
  const requested: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/note') && handlers.onNote) {
      return json(handlers.onNote(String(init?.body ?? '')));
    }
    if (url.includes('/discrepancies')) {
      return json(handlers.onDiscrepancies?.() ?? []);
    }
    const oneMatch = url.match(/\/api\/inventory\/sessions\/(\d+)$/);
    if (oneMatch) {
      if (handlers.hold) {
        await handlers.hold;
      }
      const id = Number(oneMatch[1]);
      return json(handlers.sessions.find((s) => s.id === id) ?? row({ id }));
    }
    // Список — «/sessions» без хвоста, с фильтром или без («Все пересчёты»
    // шлёт запрос совсем без query, а не с пустым «?»).
    const listMatch = url.match(/\/api\/inventory\/sessions(?:\?(.*))?$/);
    if (listMatch) {
      const query = listMatch[1] ?? '';
      requested.push(query);
      const wanted = query === ''
        ? null
        : new URLSearchParams(query).getAll('status');
      const rows = wanted === null
        ? handlers.sessions
        : handlers.sessions.filter((s) => wanted.includes(s.status));
      return json({ rows, total: handlers.total ?? rows.length });
    }
    return json([]);
  }));
  return requested;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
