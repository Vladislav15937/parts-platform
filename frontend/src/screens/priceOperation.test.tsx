import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm } from './PartEditForm';
import { BulkEditForm } from './BulkEditForm';
import { PartHistoryView } from './PartHistoryView';
import { CatalogScreen } from './CatalogScreen';

/**
 * Цена двигается процентом, суммой и округлением, а не только новым числом.
 *
 * <p><b>Зачем.</b> Торг на разборке идёт словами «отдам за минус десять»
 * и «скидка пятьсот, забирай сегодня». Пока поле принимало только готовое
 * число, владелец считал 27 000 − 10 % в уме или в калькуляторе телефона —
 * а ошибка в разряде здесь стоит детали, отданной за 2 700.
 *
 * <p><b>Проверяется, что уехало на сервер, а не что посчитал браузер.</b>
 * Считает сервер: тот же расчёт нужен правке списком, и две копии
 * разошлись бы на первом округлении. Экран обязан отправить операцию
 * и значение, а не подставить готовую цену.
 */
describe('операция с ценой', () => {
  let sent: Array<{ url: string; method: string; body: string }>;

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ url, method, body: String(init?.body ?? '') });
        return json({ changed: 3, skipped: 0, price: 24300 });
      }
      return json({
        price: 27000, minPrice: null, costPrice: null, installationPrice: null,
        qualityGrade: null, description: null, note: 'скол', textBlock: null,
        videoUrl: null, marking: null, manufacturer: null, color: null,
        section: null, barcode: null, weightKg: null, lengthMm: null,
        widthMm: null, heightMm: null, packageLengthMm: null, packageWidthMm: null,
        packageHeightMm: null, packageWeightKg: null, storageCellId: null,
        published: true,
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('в карточке шесть операций, умолчание — «Изменить»', async () => {
    render(<PartEditForm partId={7} onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByDisplayValue('27000')).toBeTruthy());

    const op = screen.getByLabelText('Операция с ценой') as HTMLSelectElement;
    expect([...op.options].map((o) => o.textContent)).toEqual([
      'Изменить', 'Увеличить на %', 'Уменьшить на %',
      'Увеличить на сумму', 'Уменьшить на сумму', 'Округлить до',
    ]);
    // Умолчание — замена: самый частый случай остаётся одним движением,
    // а арифметика лежит рядом и не мешает.
    expect(op.value).toBe('SET');
  });

  it('отправляет операцию и её значение, а не посчитанную цену', async () => {
    render(<PartEditForm partId={7} onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByDisplayValue('27000')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Операция с ценой'),
      { target: { value: 'DECREASE_PERCENT' } });
    // Смена операции чистит поле: оставленные 27 000 при «Уменьшить на %» —
    // это двадцать семь тысяч процентов, то есть отказ на ровном месте.
    const value = screen.getByLabelText('Значение операции с ценой') as HTMLInputElement;
    expect(value.value, 'в поле осталась прежняя цена — она уедет как процент').toBe('');

    fireEvent.change(value, { target: { value: '10' } });
    fireEvent.click(screen.getByText('Сохранить'));

    await waitFor(() => expect(sent.length).toBe(1));
    const body = JSON.parse(sent[0]!.body) as { price: number; priceOp: string };
    expect(body.priceOp).toBe('DECREASE_PERCENT');
    expect(body.price, 'браузер посчитал цену сам — расчёт обязан быть один').toBe(10);
  });

  it('правка списком двигает деньги той же операцией', async () => {
    render(<BulkEditForm partIds={[1, 2, 3]} count={3} onSaved={() => {}} onCancel={() => {}} />);

    const field = [...document.querySelectorAll('.bulk-field')]
      .find((f) => f.textContent?.includes('Цена'))!;
    fireEvent.click(field.querySelector('input[type=checkbox]')!);

    fireEvent.change(screen.getByLabelText('Операция: Цена'),
      { target: { value: 'DECREASE_PERCENT' } });
    fireEvent.change(field.querySelector('input:not([type=checkbox])')!,
      { target: { value: '10' } });
    fireEvent.click(screen.getByText('Изменить 3 позиции'));

    await waitFor(() => expect(sent.length).toBe(1));
    const body = JSON.parse(sent[0]!.body) as {
      changes: Record<string, number>;
      operations: Record<string, string>;
    };
    expect(body.operations['price']).toBe('DECREASE_PERCENT');
    expect(body.changes['price']).toBe(10);
  });

  /**
   * Кнопка, которая ничего не сделает, погашена и называет причину.
   *
   * <p>Без этого владелец выбирает «Уменьшить на %», не вводит числа, жмёт
   * «Изменить 3 позиции» и получает «Изменено позиций: 3» при неизменившихся
   * ценах — экран сообщает о работе, которой не было.
   */
  it('операция без значения не даёт сохранить и говорит почему', async () => {
    render(<BulkEditForm partIds={[1, 2, 3]} count={3} onSaved={() => {}} onCancel={() => {}} />);

    const field = [...document.querySelectorAll('.bulk-field')]
      .find((f) => f.textContent?.includes('Цена'))!;
    fireEvent.click(field.querySelector('input[type=checkbox]')!);
    fireEvent.change(screen.getByLabelText('Операция: Цена'),
      { target: { value: 'ROUND_TO' } });

    expect(screen.getByText(/не задано значение операции/)).toBeTruthy();
    expect((screen.getByText('Изменить 3 позиции') as HTMLButtonElement).disabled).toBe(true);
  });
});

/**
 * История говорит не только «стало», но и насколько подвинулось.
 *
 * <p>«27 000 → 24 300» надо делить в уме, а разбираются с ценой по два
 * десятка строк за раз. Считает разницу сервер — пересчёт на экране был бы
 * вторым счётом того же и разошёлся бы на округлении.
 */
describe('история цены', () => {
  afterEach(cleanup);

  it('показывает, на сколько процентов подвинулась цена', () => {
    render(<PartHistoryView
      tab="changes"
      onTab={() => {}}
      history={{
        movements: [],
        changes: [{
          at: '2026-09-10T10:00:00Z',
          author: 'Владелец',
          action: null,
          fields: [{ label: 'Цена', before: '27000', after: '24300', delta: '−10 %' }],
        }],
      }}
    />);

    expect(screen.getByText('(−10 %)')).toBeTruthy();
  });
});

/**
 * Непрошедшие позиции экран называет, а не прячет за «изменено N».
 *
 * <p><b>Зачем.</b> Головной сценарий — «скинь пятьсот со всего склада»
 * по большому отбору. Позиции, у которых операция дала бы минус или ноль,
 * пропускаются, остальные меняются, — и если экран об этом промолчит,
 * владелец прочитает «Изменено позиций: 2» как «сделано всем» и узнает
 * правду только сверкой склада руками.
 *
 * <p>Проверяется через экран, а не через сам расчёт строки: молчание
 * рождается ровно на стыке — сервер посчитал, ответ приехал, показать
 * забыли.
 */
describe('правка списком говорит о непрошедших', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (init?.method === 'POST') {
        return json({
          changed: 2,
          skipped: 0,
          rejected: 3,
          rejectedCodes: ['A-9', 'B-4'],
          rejectedReason: '«Уменьшить на сумму» на 500: цена позиции A-9 сейчас 100,'
            + ' и после операции получилось бы −400. Отрицательной цены не бывает'
            + ' — ничего не изменено',
        });
      }
      if (url.includes('/values')) return json([]);
      if (url.includes('/api/catalog/vehicles') || url.includes('/api/intake/donors')) {
        return json([]);
      }
      return json({
        total: 5,
        warehouses: [],
        rows: [{ id: 1, publicCode: 'A-1', title: 'Фара', price: '100',
                 stock: {}, photoCount: 0 }],
      });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('называет их числом, поимённо и с причиной', async () => {
    render(<CatalogScreen role="OWNER" />);
    await waitFor(() => expect(document.querySelector('tbody tr')).toBeTruthy());

    fireEvent.click(byText('button', 'Правка списком')!);
    fireEvent.click(document.querySelector('tbody tr')!);
    await waitFor(() => expect(screen.getByText('Выбрано 1')).toBeTruthy());
    fireEvent.click(byText('button', 'Изменить')!);

    const field = [...document.querySelectorAll('.bulk-field')]
      .find((f) => f.textContent?.includes('Цена'))!;
    fireEvent.click(field.querySelector('input[type=checkbox]')!);
    fireEvent.change(screen.getByLabelText('Операция: Цена'),
      { target: { value: 'DECREASE_AMOUNT' } });
    fireEvent.change(field.querySelector('input:not([type=checkbox])')!,
      { target: { value: '500' } });
    fireEvent.click(byText('button', 'Изменить 1 позицию')!);

    // Число — чтобы понять размер беды, коды — чтобы пойти посмотреть,
    // причина — чтобы знать, что случилось. Хвост назван счётом: всех
    // не перечислить, их могут быть тысячи.
    const notice = await screen.findByText(/не прошли 3 позиции/);
    expect(notice.textContent).toContain('A-9, B-4 и ещё 1');
    expect(notice.textContent, 'экран промолчал о причине').toContain(
      'Отрицательной цены не бывает');
    expect(notice.textContent).toContain('Изменено позиций: 2');
  });
});

function byText(tag: string, text: string): HTMLElement | undefined {
  return [...document.querySelectorAll(tag)].find(
    (b) => b.textContent === text) as HTMLElement | undefined;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
