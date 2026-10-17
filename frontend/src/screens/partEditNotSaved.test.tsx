import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm } from './PartEditForm';
import type { CatalogRow } from '../inventory/catalog';

/** Объявлено здесь, а не через `@types/node`: см. `inventoryMountedGuard.test.tsx`. */
declare const process: {
  on(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
  off(event: 'unhandledRejection', listener: (reason: unknown) => void): void;
};

/**
 * Сохранено не всё: то же поле успел изменить другой (задача 0313).
 *
 * <p>Ответ владельца продукта 17.10.2026: «не сохраняются только конфликтные поля,
 * а остальные сохраняются». Сервер отвечает 200 и называет несохранённое в
 * `notSaved`. Экран обязан сказать это человеку, а не закрыться молча, как после
 * полного успеха, — и не слать сохранённое второй раз.
 */
describe('правка карточки: сохранено не всё', () => {
  let sent: Array<Record<string, unknown>>;
  let answer: (body: Record<string, unknown>) => Record<string, unknown>;

  beforeEach(() => {
    sent = [];
    answer = () => ({ price: 27000 });
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') !== 'GET') {
        const body = JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>;
        sent.push(body);
        return json(answer(body));
      }
      return json(card());
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('цена занята, заметка легла: сводка, цена раскрыта с набранным, заметка больше не изменённая', async () => {
    answer = () => partial({ note: 'трещина' }, [{ key: 'price', label: 'Цена', cause: 'Цена' }]);
    let saved = false;
    render(<PartEditForm partId={7} row={row()}
                         onSaved={() => { saved = true; }} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Цена'));
    fireEvent.change(screen.getByLabelText('Значение операции с ценой'),
      { target: { value: '25000' } });
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'трещина' } });
    fireEvent.click(save());

    await waitFor(() => expect(screen.getByText('Не сохранено: Цена. Остальное сохранено'))
      .toBeTruthy());
    expect(saved, 'форма закрылась, будто сохранено всё').toBe(false);

    // Несохранённое — раскрыто, с набранным и со словами у поля.
    expect(screen.getByDisplayValue('25000'), 'набранная цена подменена').toBeTruthy();
    expect(line('Цена').className).toContain('card-edit__row--changed');
    expect(line('Цена').textContent)
      .toContain('Не сохранено: другой человек уже изменил это поле');
    expect(line('Цена').textContent).toContain('Перезагрузите страницу');

    // Сохранённое — закрыто, без подсветки, со значением из ответа сервера.
    expect(screen.queryByLabelText('Заметка'), 'сохранённая заметка осталась раскрытой')
      .toBeNull();
    expect(line('Заметка').className).not.toContain('card-edit__row--changed');
    expect(line('Заметка').textContent).toContain('трещина');
    expect(screen.getByText('Изменен 1 параметр')).toBeTruthy();

    // Повтор шлёт только несохранённое и прежнее увиденное — не чужую цену.
    fireEvent.click(save());
    await waitFor(() => expect(sent.length).toBe(2));
    expect(Object.keys(sent[1]!).sort(), 'сохранённое уехало второй раз')
      .toEqual(['price', 'priceOp', 'seen']);
    expect(sent[1]!['seen']).toEqual({ price: 27000 });
  });

  it('второе поле связки названо причиной — номером производителя', async () => {
    answer = () => partial({}, [
      { key: 'oem', label: 'Номер производителя', cause: 'Номер производителя' },
      { key: 'crosses', label: 'Кросс-номера', cause: 'Номер производителя' },
    ]);
    render(<PartEditForm partId={7} row={row()} onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Кросс-номера')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Номер производителя'));
    fireEvent.change(screen.getByLabelText('Номер производителя'), { target: { value: '81170-1' } });
    fireEvent.click(screen.getByLabelText('Изменить: Кросс-номера'));
    fireEvent.change(screen.getByLabelText('Кросс-номера'), { target: { value: '81170-2' } });
    fireEvent.click(save());

    await waitFor(() => expect(screen.getByText(
      'Не сохранено: Номер производителя, Кросс-номера. Остальное сохранено')).toBeTruthy());
    expect(line('Кросс-номера').textContent)
      .toContain('сохраняется только вместе с полем «Номер производителя»');
    expect(screen.getByDisplayValue('81170-2')).toBeTruthy();
  });

  it('после частичного сохранения кнопка внизу закрывает и перечитывает, а не «отменяет»', async () => {
    answer = () => partial({ note: 'трещина' }, [{ key: 'price', label: 'Цена', cause: 'Цена' }]);
    let saved = false;
    let cancelled = false;
    render(<PartEditForm partId={7} row={row()}
                         onSaved={() => { saved = true; }}
                         onCancel={() => { cancelled = true; }} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Цена'));
    fireEvent.change(screen.getByLabelText('Значение операции с ценой'),
      { target: { value: '25000' } });
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'трещина' } });
    fireEvent.click(save());
    await waitFor(() => expect(screen.getByText('Закрыть')).toBeTruthy());

    fireEvent.click(screen.getByText('Закрыть'));
    expect(saved, 'заметка легла, а карточка за формой не перечитана').toBe(true);
    expect(cancelled).toBe(false);
  });

  it('пустой notSaved — сохранено всё, форма закрывается, как раньше', async () => {
    answer = () => partial({ note: 'трещина' }, []);
    let saved = false;
    render(<PartEditForm partId={7} row={row()}
                         onSaved={() => { saved = true; }} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Заметка')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'трещина' } });
    fireEvent.click(save());

    await waitFor(() => expect(saved).toBe(true));
    expect(screen.queryByText(/Не сохранено/)).toBeNull();
  });

  /**
   * Сторож размонтирования на новом пути (`useMounted`): ответ приходит после
   * ожидания, а форму закрывают когда угодно — в том числе пока ответ в пути.
   * Гонка устраивается руками, как в `partEditVehicleMountedGuard.test.tsx`.
   *
   * <p>Различает сторожа здесь вызов `onSaved`: закрытая форма, получив ответ,
   * не должна просить перечитать карточку, которой на экране уже нет. Запись
   * состояния после снятия React 18 молча пропускает, поэтому у частичного ответа
   * проверяется только, что среда не потревожена, — снятый сторож этого случая
   * не покраснит, и это сказано здесь, а не спрятано.
   */
  it.each([
    ['сохранено всё', partial({ note: 'трещина' }, [])],
    ['сохранено не всё', partial({ note: 'трещина' }, [{ key: 'price', label: 'Цена', cause: 'Цена' }])],
  ])('закрытая до ответа форма (%s) не зовёт onSaved и не будит снесённую среду', async (_case, reply) => {
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') !== 'GET') {
        await sleep(100);
        return json(reply);
      }
      return json(card());
    }));
    const escaped: unknown[] = [];
    const collect = (reason: unknown): void => { escaped.push(reason); };
    process.on('unhandledRejection', collect);
    let saved = false;
    try {
      render(<PartEditForm partId={7} row={row()}
                           onSaved={() => { saved = true; }} onCancel={() => {}} />);
      await waitFor(() => expect(screen.getByLabelText('Изменить: Заметка')).toBeTruthy());
      fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
      fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'трещина' } });
      fireEvent.click(save());
      cleanup();
      await sleep(20);

      const globals = globalThis as unknown as { window?: unknown };
      const savedWindow = globals.window;
      delete globals.window;
      await sleep(400);
      globals.window = savedWindow;

      expect(escaped.map(String), 'ответ записал состояние закрытой формы').toEqual([]);
      expect(saved, 'закрытая форма попросила перечитать карточку').toBe(false);
    } finally {
      process.off('unhandledRejection', collect);
    }
  });
});

/** Ответ сервера: прежние поля, что не легло и форма после записи. */
function partial(changes: Record<string, unknown>,
                 notSaved: Array<{ key: string; label: string; cause: string }>)
  : Record<string, unknown> {
  return { price: 27000, notSaved, form: { ...card(), ...changes } };
}

function line(label: string): HTMLElement {
  const found = [...document.querySelectorAll('.card-edit__row')].find(
    (r) => r.querySelector('.card-edit__label')?.textContent === label);
  if (found === undefined) throw new Error(`строки «${label}» на экране нет`);
  return found as HTMLElement;
}

function save(): HTMLButtonElement {
  return screen.getByText('Сохранить') as HTMLButtonElement;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

function card(): Record<string, unknown> {
  return {
    price: 27000, minPrice: null, costPrice: null, installationPrice: null,
    condition: 'USED', qualityGrade: null, description: null, note: 'скол на креплении',
    textBlock: null, videoUrl: null, marking: null, manufacturer: null,
    color: null, section: null, barcode: null, weightKg: null, lengthMm: null,
    widthMm: null, heightMm: null, packageLengthMm: null, packageWidthMm: null,
    packageHeightMm: null, packageWeightKg: null, storageCellId: null, published: true,
    warranty: null, pickup: null, deliveryPayment: null, dromKind: null, dromSection: null,
    applicability: null, characteristics: null, mayFit: null, adDate: null,
    number: 7, code: 'B-40219', title: 'Фара левая', sideLr: null, sideFr: null,
    authenticity: null, oem: '81170-0', crosses: null, legacyCode: null, dromAdNumber: null,
    donorCode: null, brand: null, model: null, generation: null, body: null, engine: null,
    year: null, equipment: null,
  };
}

function row(): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title: 'Фара левая',
    brand: null, model: null, generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: null, donorCode: null, price: 27000, stock: {},
  } as unknown as CatalogRow;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
