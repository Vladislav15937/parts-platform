import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

import { SellerScreen } from './SellerScreen';

/**
 * Печать документов сделки (задача 0051).
 *
 * <p><b>Как это выглядело для человека.</b> Продавец оформил сделку, покупатель
 * стоит перед ним и ждёт бумагу — а печати из сделки не было вовсе: ни чека,
 * ни накладной, ни счёта. По этой бумаге покупатель через две недели приходит
 * по гарантии, и в реестре возвратов ориентира 3 608 записей, то есть
 * возвращаются реально и часто.
 *
 * <p>Проверяется то, что видит и делает человек: четыре пункта меню ровно теми
 * словами и в том порядке, что названы в критерии приёмки; содержимое чека;
 * счёт на юр. лицо, берущий реквизиты <b>организации</b>, а не блок склада;
 * пометка об НДС, которой нет в документе, пока её не задали.
 */
describe('печать документов сделки', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('под кнопкой печати — четыре формы, ровно этими словами и в этом порядке', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);

    // Списком и целиком, а не поиском одного пункта: лишний или переставленный
    // пункт — это другое меню, а клиент приходит с ориентира и узнаёт его.
    expect(menuItems()).toEqual([
      'Товарный чек',
      'Товарная накладная',
      'Товарный чек и накладная',
      'Счёт на юр. лицо',
    ]);
  });

  it('товарный чек печатается с продавцом, покупателем, позициями и деньгами', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Товарный чек')!);

    await waitFor(() => expect(sheet()).toBeTruthy());
    const printed = text(sheet()!);

    expect(printed, 'в чеке нет реквизитов продавца').toContain('ИП Санин Д.В.');
    expect(printed, 'в чеке нет номера и даты').toContain('Товарный чек № 20 от');
    expect(printed, 'в чеке нет покупателя').toContain('ООО «Автосервис»');
    expect(printed, 'в чеке нет позиции').toContain('Фара левая Toyota Camry');
    expect(printed, 'в чеке нет итога').toContain('Итого: 5 000 ₽');
    expect(printed, 'в чеке нет оплаченного и долга')
      .toContain('Оплачено: 2 000 ₽ · Долг: 3 000 ₽');
    expect(printed, 'в чеке нет дополнительного текста')
      .toContain('Гарантия 14 календарных дней');
    expect(printed, 'в чеке нет поля подписи клиента').toContain('Покупатель __');
    // Диалог печати открывается сам: иначе продавцу надо догадаться нажать
    // ещё раз, стоя перед покупателем.
    expect(window.print, 'печать не отправлена').toHaveBeenCalled();
  });

  /**
   * Пункт 6 критерия приёмки, и проверяется он **отсутствием**: утверждение
   * «пометка показана, когда задана» прошло бы и на документе, который печатает
   * пустую строку про НДС всегда.
   */
  it('незаданная пометка об НДС не печатает строку вовсе', async () => {
    stubApi({ vatNote: null });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Товарный чек')!);

    await waitFor(() => expect(sheet()).toBeTruthy());
    expect(text(sheet()!)).not.toContain('НДС');
  });

  it('заданная пометка об НДС печатается', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Товарный чек')!);

    await waitFor(() => expect(sheet()).toBeTruthy());
    expect(text(sheet()!)).toContain('в том числе НДС 5% - 250 руб.');
  });

  /**
   * Пункт 5: счёт выставляет организация. Проверяется с двух сторон —
   * реквизиты организации есть, а блока склада в счёте нет: подмена одного
   * другим и есть тот дефект, от которого спасают два уровня реквизитов.
   */
  it('счёт на юр. лицо берёт реквизиты организации, а не блок склада', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Счёт на юр. лицо')!);

    await waitFor(() => expect(sheet()).toBeTruthy());
    const printed = text(sheet()!);

    expect(printed, 'в счёте нет названия организации').toContain('ООО «Разборка»');
    expect(printed, 'в счёте нет ИНН и КПП').toContain('ИНН 7701234567 · КПП 770101001');
    expect(printed, 'в счёте нет расчётного счёта').toContain('Р/с 40702810000000000001');
    expect(printed, 'в счёте нет подписи руководителя').toContain('Руководитель __');
    expect(printed, 'в счёт уехал блок склада вместо реквизитов организации')
      .not.toContain('ИП Санин');
  });

  /**
   * «Товарный чек и накладная» — две страницы, а не один лист: решение
   * исполнителя, названное в PR. Проверяется числом документов, а не текстом:
   * оба блока на одном листе дали бы тот же текст.
   */
  it('«Товарный чек и накладная» печатает два документа', async () => {
    stubApi();
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Товарный чек и накладная')!);

    await waitFor(() => expect(sheet()).toBeTruthy());
    const docs = [...sheet()!.querySelectorAll('.print-doc')];
    expect(docs.length, 'чек и накладная ушли одним листом').toBe(2);
    expect(text(docs[0]!)).toContain('Товарный чек №');
    expect(text(docs[1]!)).toContain('Товарная накладная №');
  });

  /**
   * Позиции с разных складов не дают реквизитов, и причина обязана стоять
   * в самом документе: продавец отдаёт бумагу в руки и должен увидеть, что
   * продавца в ней нет, — иначе узнает об этом от покупателя.
   */
  it('документ без реквизитов продавца говорит почему', async () => {
    stubApi({
      seller: {
        warehouseId: null,
        warehouseName: null,
        details: null,
        problem: 'Позиции сделки с разных складов — реквизиты продавца подставить нельзя.',
      },
    });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Товарный чек')!);

    await waitFor(() => expect(sheet()).toBeTruthy());
    expect(text(sheet()!)).toContain('с разных складов');
  });

  /**
   * Отказ сервера доезжает **своими словами**, а лист не открывается.
   *
   * <p>Проверяется именно текст сервера, а не наша заглушка: правило проекта —
   * «текст самого приложения сильнее нашего и уходит как есть». Пустой лист
   * без объяснения был бы хуже отказа: продавец стоит перед покупателем
   * и решит, что печать сломана.
   */
  it('отказ сервера назван его словами, а лист не открывается', async () => {
    stubApi({ failPrint: 'Сделка не найдена — обновите страницу, список устарел' });
    render(<SellerScreen canSell role="SELLER" company="t_1" memberId={7} />);
    await openDeal();

    fireEvent.click(button('Печатать')!);
    fireEvent.click(button('Товарный чек')!);

    await waitFor(() => expect(document.body.textContent)
      .toContain('Сделка не найдена — обновите страницу, список устарел'));
    expect(sheet(), 'лист открылся при отказе сервера').toBeNull();
  });
});

const CREATED_AT = '2026-09-05T12:00:00Z';
const RESERVED_UNTIL = new Date(Date.now() + 3 * 86_400_000).toISOString();

function sheet(): HTMLElement | null {
  return document.querySelector('.print-sheet');
}

function menuItems(): string[] {
  return [...document.querySelectorAll('.print-menu__list button')]
    .map((node) => clean(node.textContent));
}

function button(label: string): HTMLButtonElement | undefined {
  return [...document.querySelectorAll('button')]
    .find((b) => clean(b.textContent) === label) as HTMLButtonElement | undefined;
}

function text(node: Element): string {
  return clean(node.textContent);
}

/** Неразрывный пробел разделителя тысяч — обычным, чтобы сравнивать читаемое. */
function clean(value: string | null | undefined): string {
  return (value ?? '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim();
}

/** Доходит до карточки сделки, как продавец: через поиск клиента. */
async function openDeal(): Promise<void> {
  fireEvent.click(button('Найти сделку клиента')!);

  const input = [...document.querySelectorAll('input')]
    .find((i) => i.placeholder === 'имя или телефон')!;
  setNative(input, 'Автосервис');

  await waitFor(() => expect(findBy((t) => t.includes('Автосервис'))).toBeTruthy());
  fireEvent.click(findBy((t) => t.includes('Автосервис'))!);
  await waitFor(() => expect(findBy((t) => t.startsWith('№20'))).toBeTruthy());
  fireEvent.click(findBy((t) => t.startsWith('№20'))!);
  await waitFor(() => expect(document.querySelector('h3')).toBeTruthy());
}

function findBy(match: (text: string) => boolean): HTMLButtonElement | undefined {
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
 * Заглушка сервера. Документ она отдаёт в той же форме, в какой его собирает
 * `DealPrintService`: реквизиты склада отдельно от реквизитов организации —
 * иначе проверка «счёт не берёт блок склада» проверяла бы фикстуру.
 */
function stubApi(over: Record<string, unknown> = {}) {
  vi.stubGlobal('print', vi.fn());

  const doc = {
    dealId: 20,
    number: 20,
    createdAt: CREATED_AT,
    issuedAt: null,
    seller: {
      warehouseId: 2,
      warehouseName: 'Ткацкая',
      details: 'ИП Санин Д.В.\nБарнаул, Ткацкая 626',
      problem: null,
    },
    buyer: {
      name: 'Автосервис на Русской',
      phone: '+79990001122',
      inn: '2222333344',
      companyName: 'ООО «Автосервис»',
      note: null,
    },
    legal: {
      name: 'ООО «Разборка»',
      address: '656000, Барнаул, Ткацкая 626',
      inn: '7701234567',
      kpp: '770101001',
      bankName: 'Сбербанк',
      bankBic: '044525225',
      bankAccount: '40702810000000000001',
      bankCorrAccount: '30101810400000000225',
      director: 'Петров П.П.',
      chiefAccountant: 'Сидорова А.А.',
    },
    extraText: 'Гарантия 14 календарных дней',
    vatNote: 'в том числе НДС 5% - 250 руб.',
    clientSignature: true,
    issuerSignature: false,
    lines: [{
      title: 'Фара левая Toyota Camry', quantity: '1', price: '5000.00', amount: '5000.00',
    }],
    total: '5000.00',
    paid: '2000.00',
    debt: '3000.00',
    ...over,
  };

  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/print')) {
      // Отказ по существу (409), а не 5xx: тот слой запросов подменяет своим
      // «Сервер не отвечает», и проверка перестала бы говорить про печать.
      return typeof over.failPrint === 'string'
        ? json({ message: over.failPrint }, 409)
        : json(doc);
    }
    if (url.includes('/api/customers/1/account')) {
      return json({ customerId: 1, balance: 0, entries: [] });
    }
    if (url.includes('/api/customers?')) {
      return json([{
        id: 1, name: 'Автосервис на Русской', phone: '+79990001122',
        email: null, customerType: 'COMPANY',
      }]);
    }
    if (url.includes('/api/deals?customerId')) {
      return json([deal()]);
    }
    return json([]);
  }));
}

function deal() {
  return {
    id: 20,
    number: 20,
    customerId: 1,
    customerName: 'Автосервис на Русской',
    managerId: null,
    managerName: null,
    stage: 'PARTLY_PAID',
    status: 'RESERVED',
    reservedUntil: RESERVED_UNTIL,
    totalAmount: '5000.00',
    paidAmount: '2000.00',
    debt: '3000.00',
    createdAt: CREATED_AT,
    issuedAt: null,
    warehouseId: null,
    marketplace: null,
    externalOrderNo: null,
    replyDeadline: null,
    orderAcceptedAt: null,
    deliveryNote: null,
    items: [{
      id: 1, partId: 1, title: 'Фара левая Toyota Camry', quantity: '1',
      price: '5000.00', discount: null, warehouseId: 2, status: 'RESERVED',
    }],
    services: [],
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
