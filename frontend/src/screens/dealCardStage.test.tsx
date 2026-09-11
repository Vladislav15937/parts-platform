import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, waitFor, render } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Оплаченная сделка не подписана «отложена» — ни в карточке, ни в поиске
 * сделок клиента (задача 0058).
 *
 * <p><b>Как это выглядело для продавца.</b> Полностью оплаченная и ещё
 * не выданная сделка остаётся документом `RESERVED` со сроком резерва:
 * состояние `READY` не ставит ни один путь системы, «готова к выдаче» —
 * стадия, а не статус. Заголовок карточки читал сырой статус и говорил
 * «Сделка №20 · отложена», а строкой ниже стояло «Отложено до 15 сентября»,
 * то есть «ещё не оплачена, ждём до этой даты» — ровно противоположное
 * тому, что сделка значит. Цифр рядом с этими словами нет вовсе: сумма
 * и оплата ниже по карточке, и проверить слово было нечем.
 *
 * <p>Хуже всего это именно здесь: нажатие на карточку «Готов к выдаче»
 * на доске ведёт в эту самую карточку — продавец читал исправленное задачей
 * 0052 слово и тут же, одним движением, прежний обман снова.
 *
 * <p><b>Почему утверждения списками, а не поиском слова.</b> Проверка
 * «„готова к выдаче“ где-то есть» прошла бы и рядом с оставшимся
 * «Отложено до 15 сентября» — а именно лишняя подпись и врёт. Поэтому
 * сравнивается **вся** подпись целиком, как в `dealsBoard.test.tsx`.
 *
 * <p>Списки «Списком» и вкладку «Сделки» карточки клиента задача трогать
 * запретила: там рядом стоят колонки «Сумма» и «Оплачено», человек видит
 * данные для собственного вывода, и показывать ли там стадию — вопрос
 * владельцу продукта. Здесь их поэтому нет.
 */
describe('оплаченная сделка не подписана отложенной', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('заголовок карточки называет готовность, и срока резерва под ним нет', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal('№20');

    // Оплачена целиком и не выдана — документ при этом `RESERVED`
    // со сроком резерва, и подпись обязана говорить про стадию.
    expect(cardStates()).toEqual(['Сделка №20 · готова к выдаче']);
  });

  /** Прежнее поведение обязано остаться: чинилась поправка, а не срок. */
  it('неоплаченная по-прежнему отложена и с числом', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal('№11');

    expect(cardStates()).toEqual(['Сделка №11 · отложена', `Отложено до ${DAY}`]);
  });

  /**
   * Резерв на документе никуда не делся, и продлевают его оттуда же.
   * Убрать кнопку вместе со словом значило бы отнять возможность, о которой
   * задача не говорит ничего.
   */
  it('у готовой к выдаче резерв всё ещё продлевается', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal('№20');

    expect(document.querySelector('input[type="date"]')).toBeTruthy();
  });

  /**
   * Поиск сделок клиента — второе место с тем же приёмом. Проверяются все
   * три строки разом: у оплаченной слова и срока быть не должно, у ждущей
   * оплаты — должны остаться, у выданной стадии нет вовсе, и слово берётся
   * из состояния документа.
   */
  it('строки поиска сделок клиента подписаны стадией, а закрытая — статусом',
    async () => {
      stubApi();
      render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
      await openCustomer();

      await waitFor(() => expect(finderLines().length).toBe(3));
      expect(finderLines()).toEqual([
        `№20 · готова к выдаче · 5 000 ₽ · ${CREATED}`,
        `№11 · отложена · до ${DAY} · 5 000 ₽ · ${CREATED}`,
        `№9 · выдана · 5 000 ₽ · ${CREATED}`,
      ]);
    });
});

const RESERVED_UNTIL = '2026-09-15T12:00:00Z';
const CREATED_AT = '2026-09-05T12:00:00Z';
const DAY = new Date(RESERVED_UNTIL)
  .toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
const CREATED = new Date(CREATED_AT).toLocaleDateString('ru-RU');

/**
 * Всё, чем подписана открытая карточка: заголовок и строка срока. Списком,
 * а не поиском отдельного слова, — иначе утверждение проходило бы рядом
 * с лишней подписью.
 */
function cardStates(): string[] {
  const head = clean(document.querySelector('h3')?.textContent);
  const term = [...document.querySelectorAll('p.note')]
    .map((node) => clean(node.textContent))
    .filter((text) => text.startsWith('Отложено'));
  return [head, ...term];
}

/** Строки списка сделок клиента целиком — со сроком, если он показан. */
function finderLines(): string[] {
  return [...document.querySelectorAll('ul.suggestions li button')]
    .map((node) => clean(node.textContent))
    .filter((text) => text.startsWith('№'));
}

/** Неразрывный пробел разделителя тысяч — такой же пробел для сравнения. */
function clean(text: string | null | undefined): string {
  return (text ?? '').replace(/\s+/g, ' ').trim();
}

/** Доходит до списка сделок клиента, как продавец: через поиск клиента. */
async function openCustomer(): Promise<void> {
  fireEvent.click(findButtonBy((t) => t === 'Найти сделку клиента')!);

  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  setNative(input, 'Иванов');

  await waitFor(() => expect(findButtonBy((t) => t.includes('Иванов'))).toBeTruthy());
  fireEvent.click(findButtonBy((t) => t.includes('Иванов'))!);
  await waitFor(() => expect(findButtonBy((t) => t.startsWith('№'))).toBeTruthy());
}

async function openDeal(number: string): Promise<void> {
  await openCustomer();
  fireEvent.click(findButtonBy((t) => t.startsWith(`${number} `))!);
  await waitFor(() => expect(document.querySelector('h3')).toBeTruthy());
}

function findButtonBy(match: (text: string) => boolean): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => match(clean(b.textContent))) as HTMLButtonElement | undefined;
}

function setNative(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, 'value')!.set!;
  setter.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/**
 * Заглушка сервера. Стадию она **не выдумывает** — отдаёт ту, что считает
 * `SalesService.stagesOf` тем же выражением, что раскладывает доску:
 * оплаченная целиком и не выданная — `READY`, ждущая оплаты —
 * `AWAITING_PAYMENT`, у выданной стадии нет вовсе. Что сервер их правда
 * так и считает, стережёт `DealBoardTest` — там сделка заводится
 * по-настоящему, и стадия карточки сверяется с колонкой доски.
 */
function stubApi() {
  const deals = [
    deal(20, { paidAmount: '5000.00', debt: '0.00', stage: 'READY' }),
    deal(11, { paidAmount: '0.00', debt: '5000.00', stage: 'AWAITING_PAYMENT' }),
    deal(9, {
      paidAmount: '5000.00', debt: '0.00', stage: null,
      status: 'ISSUED', reservedUntil: null, issuedAt: CREATED_AT,
    }),
  ];

  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{ id: 1, name: 'Иванов Пётр', phone: '+79990001122',
        email: null, customerType: 'PERSON' }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json(deals);
    }
    return json([]);
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

function deal(id: number, over: Record<string, unknown>) {
  return {
    id,
    number: id,
    customerId: 1,
    managerId: null,
    managerName: null,
    stage: null,
    status: 'RESERVED',
    reservedUntil: RESERVED_UNTIL,
    totalAmount: '5000.00',
    paidAmount: '0.00',
    debt: '5000.00',
    createdAt: CREATED_AT,
    issuedAt: null,
    warehouseId: null,
    marketplace: null,
    externalOrderNo: null,
    replyDeadline: null,
    orderAcceptedAt: null,
    deliveryNote: null,
    items: [{ id, partId: id, title: 'Фара', quantity: '1', price: '5000.00',
      discount: null, warehouseId: 2, status: 'RESERVED' }],
    services: [],
    ...over,
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
