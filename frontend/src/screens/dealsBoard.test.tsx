import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { DealsScreen } from './DealsScreen';

/**
 * Доска сделок по состояниям (задача 0052).
 *
 * <p>Проверяется то, что видит продавец: какой вид открыт при входе, что
 * стоит над колонками, что несёт карточка и что уходит в запрос при отборе.
 *
 * <p><b>Раскладку по колонкам сюда не тащим.</b> Заглушка отвечает готовой
 * доской — значит утверждение «просроченная лежит в „Истек срок“» проверяло
 * бы фикстуру, а не код. Настоящее место этой логики на сервере, и стережёт
 * её {@code DealBoardTest}: там сделка заводится по-настоящему и ищется
 * по всем пяти колонкам сразу. Здесь — экран: слова, числа, отбор и переход
 * в сделку.
 */
describe('доска сделок по состояниям', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('раздел открывается списком, а «По статусам» показывает пять колонок со счётчиками',
    async () => {
      const fetch = stubApi();
      render(<DealsScreen onOpenDeal={() => {}} />);

      // Прежний экран не изменился: тот же запрос, что и до доски.
      await waitFor(() => expect(String(fetch.mock.calls[0]?.[0]))
        .toContain('/api/deals/registry'));

      fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

      await waitFor(() => expect(screen.getByText('Истек срок')).toBeTruthy());
      // Слова и порядок — те, по которым переходящий клиент узнаёт экран.
      expect(headings()).toEqual([
        'Новая сделка 1', 'Истек срок 2', 'Ждет оплаты 1',
        'Частично оплачен 1', 'Готов к выдаче 1',
      ]);
      expect(String(fetch.mock.calls.at(-1)?.[0])).toContain('/api/deals/board');
    });

  it('карточка несёт номер, дату, состояние со сроком и сумму, а внесённое — только у частичной',
    async () => {
      stubApi();
      render(<DealsScreen onOpenDeal={() => {}} />);
      fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

      const partly = await card('Частично оплачен', '№12');
      expect(within(partly).getByText('05 сен 26')).toBeTruthy();
      expect(within(partly).getByText('5 000 ₽')).toBeTruthy();
      expect(within(partly).getByText('2 500 ₽')).toBeTruthy();
      expect(within(partly).getByText('Отложена')).toBeTruthy();
      expect(within(partly).getByText('Автосервис на Русской')).toBeTruthy();

      // Ждущая оплаты внесённого не показывает: ноль рядом с суммой
      // не говорит ничего, кроме того, что уже сказано колонкой.
      const waiting = await card('Ждет оплаты', '№11');
      expect(within(waiting).queryByText('0 ₽')).toBeNull();
    });

  /**
   * <b>Здесь проверяется смысл подписи, а не наличие вёрстки.</b> Стадия
   * «Готов к выдаче» вычисляется (оплачено полностью и не выдано), а документ
   * у такой сделки так и остаётся `RESERVED` со сроком резерва — и карточка,
   * подписанная сырым статусом, говорила «Отложена · до 15 сентября», то есть
   * «ещё не оплачена, ждём до этой даты». Ровно противоположное тому, что она
   * значит, и продавец читает именно так.
   *
   * <p>Поэтому утверждение — про **весь** список подписей карточки: проверка
   * «слово „Готова к выдаче“ где-то есть» прошла бы и рядом с оставшимся
   * сроком резерва.
   */
  it('готовая к выдаче подписана стадией, а не «Отложена» со сроком резерва',
    async () => {
      stubApi();
      render(<DealsScreen onOpenDeal={() => {}} />);
      fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

      const ready = await card('Готов к выдаче', '№20');
      expect(states(ready)).toEqual(['Готова к выдаче']);
      // Внесённое у неё не показывается: это та же сумма, что строкой выше,
      // и второй раз она не несёт ничего.
      expect(within(ready).getAllByText('5 000 ₽')).toHaveLength(1);
    });

  it('просроченная говорит «срок истёк», а не вчерашним числом', async () => {
    const past = new Date(Date.now() - 3 * 24 * 3600 * 1000);
    stubApi({ expiredUntil: past.toISOString() });
    render(<DealsScreen onOpenDeal={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

    const expired = await card('Истек срок', '№9');
    expect(within(expired).getByText('срок истёк')).toBeTruthy();
    const day = past.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
    expect(within(expired).queryByText(`до ${day}`)).toBeNull();
  });

  /** У заказа с площадки покупателя нет вовсе — обещать его нечем. */
  it('карточка без клиента не выдумывает покупателя', async () => {
    stubApi();
    render(<DealsScreen onOpenDeal={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

    const draft = await card('Новая сделка', '№14');
    expect(within(draft).queryByText(/клиент/i)).toBeNull();
    expect(draft.textContent).not.toContain('Автосервис');
  });

  it('нажатие на карточку открывает эту сделку', async () => {
    stubApi();
    const onOpenDeal = vi.fn();
    render(<DealsScreen onOpenDeal={onOpenDeal} />);
    fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

    fireEvent.click(await card('Ждет оплаты', '№11'));
    expect(onOpenDeal).toHaveBeenCalledWith(11);
  });

  it('три отбора уходят в запрос, а умолчание у всех — «Все»', async () => {
    const fetch = stubApi();
    render(<DealsScreen onOpenDeal={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));
    await waitFor(() => expect(screen.getByText('Истек срок')).toBeTruthy());

    // Умолчание не сужает ничего: параметров в запросе нет вовсе.
    const first = new URL(String(fetch.mock.calls.at(-1)?.[0]), 'http://x');
    expect(first.searchParams.get('warehouseId')).toBeNull();
    expect(first.searchParams.get('sourceId')).toBeNull();
    expect(first.searchParams.get('managerId')).toBeNull();
    expect((screen.getByLabelText('Склад выдачи') as HTMLSelectElement).value).toBe('');

    fireEvent.change(screen.getByLabelText('Склад выдачи'), { target: { value: '2' } });
    await waitFor(() => {
      const asked = new URL(String(fetch.mock.calls.at(-1)?.[0]), 'http://x');
      expect(asked.searchParams.get('warehouseId')).toBe('2');
    });

    fireEvent.change(screen.getByLabelText('Источник'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Ответственный'), { target: { value: '7' } });
    await waitFor(() => {
      const asked = new URL(String(fetch.mock.calls.at(-1)?.[0]), 'http://x');
      // Отборы действуют вместе, а не отменяют друг друга.
      expect(asked.searchParams.get('warehouseId')).toBe('2');
      expect(asked.searchParams.get('sourceId')).toBe('3');
      expect(asked.searchParams.get('managerId')).toBe('7');
    });
  });

  /**
   * Счётчик над колонкой считает всё, что в неё попало, а карточек приезжает
   * не больше сотни: «Истек срок 143» над сотней карточек читается как
   * полнота, и продавец не пойдёт искать остальные сорок три.
   */
  it('обрезанная колонка говорит, сколько показано из скольких', async () => {
    stubApi({ expiredCount: 143 });
    render(<DealsScreen onOpenDeal={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

    await waitFor(() => expect(headings()).toContain('Истек срок 143'));
    expect(screen.getByText(/Показаны первые 2 сделки из 143/)).toBeTruthy();
  });

  it('отказ сервера не выдаётся за пустую доску', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/deals/board')) {
        return new Response('{"message":"Нет связи"}', {
          status: 503, headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(JSON.stringify({ items: [], total: 0 }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      });
    }));

    render(<DealsScreen onOpenDeal={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'По статусам' }));

    await waitFor(() => expect(screen.getByText('Нет связи')).toBeTruthy());
    expect(screen.queryByText('Загружаем…')).toBeNull();
    expect(screen.queryByText('Истек срок')).toBeNull();
    // Отбор остаётся на экране: спрятанный вместе с колонками, он запирает
    // продавца — сужено, ничего не видно, а снять нечем.
    expect(screen.getByLabelText('Склад выдачи')).toBeTruthy();
  });
});

/** Заголовки колонок со счётчиками — то, что продавец читает за секунду. */
function headings(): string[] {
  return screen.getAllByRole('heading', { level: 3 })
    .map((node) => (node.textContent ?? '').replace(/\s+/g, ' ').trim());
}

/**
 * Всё, чем карточка подписана: состояние и срок. Списком, а не поиском
 * отдельного слова, — иначе утверждение проходило бы рядом с лишней
 * подписью, а именно лишняя подпись и врёт.
 */
function states(card: HTMLElement): string[] {
  return Array.from(card.querySelectorAll('.deal-card__state'))
    .map((node) => (node.textContent ?? '').trim());
}

/** Карточка с таким номером внутри названной колонки. */
async function card(column: string, number: string): Promise<HTMLElement> {
  const head = await screen.findByText(new RegExp(`^${column}`));
  const section = head.closest('section') as HTMLElement;
  return within(section).getByText(number).closest('button') as HTMLElement;
}

function stubApi(options: { expiredUntil?: string; expiredCount?: number } = {}) {
  const soon = new Date(Date.now() + 5 * 24 * 3600 * 1000).toISOString();
  const past = options.expiredUntil
    ?? new Date(Date.now() - 24 * 3600 * 1000).toISOString();
  const board = {
    columns: [
      column('NEW', 'Новая сделка', [
        // Заказ с площадки: ни клиента, ни срока резерва.
        { ...row(14), customerName: null, status: 'DRAFT', reservedUntil: null },
      ]),
      column('EXPIRED', 'Истек срок', [
        { ...row(9), reservedUntil: past },
        { ...row(10), reservedUntil: past },
      ], options.expiredCount),
      column('AWAITING_PAYMENT', 'Ждет оплаты', [
        { ...row(11), paidAmount: '0.00', reservedUntil: soon },
      ]),
      column('PARTLY_PAID', 'Частично оплачен', [
        { ...row(12), paidAmount: '2500.00', reservedUntil: soon },
      ]),
      // Готовая к выдаче — оплаченная целиком и не выданная, и документ
      // у неё по-прежнему `RESERVED` со сроком резерва: пустая колонка
      // здесь означала бы, что подпись такой карточки не проверяет никто.
      column('READY', 'Готов к выдаче', [
        { ...row(20), paidAmount: '5000.00', reservedUntil: soon },
      ]),
    ],
    warehouses: [{ id: 2, name: 'Ткацкая' }],
    sources: [{ id: 3, name: 'Дром' }],
    managers: [{ id: 7, name: 'Владимир Петров' }],
  };
  const fetch = vi.fn(async (input: RequestInfo | URL) => new Response(
    JSON.stringify(String(input).includes('/api/deals/board')
      ? board
      : { items: [], total: 0 }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  ));
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

/**
 * Стадию карточке ставит колонка — как и на сервере, где и то и другое
 * приходит одной строкой `CASE`. Проставь её фикстура отдельно, и карточка
 * могла бы объявить себя не тем, в чём лежит, — состояние, которого
 * не бывает.
 */
function column(key: string, title: string, cards: Card[], total?: number) {
  return {
    key,
    title,
    count: total ?? cards.length,
    cards: cards.map((card) => ({ ...card, stage: key })),
  };
}

interface Card {
  id: number;
  number: number | null;
  createdAt: string;
  customerName: string | null;
  totalAmount: string;
  paidAmount: string;
  status: string;
  reservedUntil: string | null;
}

function row(id: number): Card {
  return {
    id,
    number: id,
    createdAt: '2026-09-05T12:00:00Z',
    customerName: 'Автосервис на Русской',
    totalAmount: '5000.00',
    paidAmount: '0.00',
    status: 'RESERVED',
    reservedUntil: null,
  };
}
