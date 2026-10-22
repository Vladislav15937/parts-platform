import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { IntakeScreen } from './IntakeScreen';

/**
 * Машина партии из общего справочника уходит в запрос приёмки (задача 0377).
 *
 * <p>Выбранная машина достаётся только новым позициям партии: у заведённой
 * заранее (`partId`) машина уже есть или её выберут в карточке, и приёмка её
 * не трогает — у такой строки уходит `null`. До этого теста экран приёмки
 * не стерёг никто: подделка «всегда `bazonVehicle: null`» оставляла все тесты
 * приёмки зелёными (разбор PR 497).
 */
describe('машина из справочника в запросе приёмки', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/api/catalog/bazon/marks')) return json([{ id: 2, name: 'Toyota' }]);
      if (url.endsWith('/marks/2/models')) return json([{ id: 20, name: 'Camry' }]);
      if (url.endsWith('/marks/2/motors')) {
        return json([{ id: 300, name: '2ARFE', volumeCc: 2500, fuel: 'gasoline' }]);
      }
      if (url.endsWith('/models/20/bodies')) {
        return json([{ id: 200, name: 'XV70', description: null, generation: '8 поколение',
          years: '2017—...' }]);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('новой позиции уходит выбранная машина, заведённой заранее — null', async () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference()} onSend={onSend} />);
    await chooseCamry();

    selectByOption('— выберите склад —', '2');
    addNew('капот');
    selectByOption('не указана', '19');
    fireEvent.click(screen.getByRole('button', { name: 'Принять' }));
    fireEvent.click(screen.getByRole('button', { name: 'Отправить партию' }));

    expect(sentItems(onSend)).toEqual([
      expect.objectContaining({ partId: null,
        bazonVehicle: { markId: 2, modelId: 20, bodyId: 200, motorId: 300 } }),
      expect.objectContaining({ partId: 5, bazonVehicle: null }),
    ]);
  });

  it('машина не выбрана — у новой позиции тоже null, а не четвёрка пустых', async () => {
    const onSend = vi.fn();
    render(<IntakeScreen reference={reference()} onSend={onSend} />);
    await waitFor(() => expect(screen.getByText('Toyota')).toBeTruthy());

    selectByOption('— выберите склад —', '2');
    addNew('капот');
    fireEvent.click(screen.getByRole('button', { name: 'Отправить партию' }));

    expect(sentItems(onSend)).toEqual([
      expect.objectContaining({ partId: null, bazonVehicle: null }),
    ]);
  });
});

async function chooseCamry(): Promise<void> {
  await waitFor(() => expect(screen.getByText('Toyota')).toBeTruthy());
  fireEvent.change(screen.getByLabelText('Марка (справочник)'), { target: { value: '2' } });
  await waitFor(() => expect(screen.getByText('Camry')).toBeTruthy());
  fireEvent.change(screen.getByLabelText('Модель (справочник)'), { target: { value: '20' } });
  await waitFor(() => expect(screen.getByText(/XV70/)).toBeTruthy());
  fireEvent.change(screen.getByLabelText('Кузов (справочник)'), { target: { value: '200' } });
  await waitFor(() => expect(screen.getByText(/2ARFE/)).toBeTruthy());
  fireEvent.change(screen.getByLabelText('Двигатель (справочник)'), { target: { value: '300' } });
}

function addNew(name: string): void {
  fireEvent.change(screen.getByPlaceholderText('например, фара левая'), { target: { value: name } });
  fireEvent.change(screen.getByLabelText('Цена'), { target: { value: '3000' } });
  fireEvent.click(screen.getByRole('button', { name: 'Добавить в партию' }));
}

function sentItems(onSend: ReturnType<typeof vi.fn>): unknown {
  expect(onSend, 'партия не ушла').toHaveBeenCalledTimes(1);
  const [payload] = onSend.mock.calls[0]!;
  return (payload as { items: unknown }).items;
}

function selectByOption(optionText: string, value: string): void {
  const select = [...document.querySelectorAll('select')]
    .find((s) => [...s.options].some((o) => o.textContent === optionText))!;
  fireEvent.change(select, { target: { value } });
}

function reference() {
  return {
    loadedAt: new Date().toISOString(),
    warehouses: [{ id: 2, name: 'Ткацкая', cells: [] }],
    supplies: [
      { id: 19, number: '18', supplierName: 'Иокогама', kind: 'CONTAINER',
        status: 'EXPECTED', arrivedOn: null, expectedOn: null },
    ],
    donors: [],
    partNames: [],
    expectedParts: [
      { id: 5, supplyId: 19, title: 'Фара левая Toyota Camry', condition: 'USED', price: 9000,
        remaining: 3 },
    ],
  } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
