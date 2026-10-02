import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SettingsScreen } from './SettingsScreen';

/**
 * «Печатные формы» — раздел «Настроек» (задача 0051).
 *
 * <p>До этой задачи реквизиты продавца негде было задать вовсе, а печати,
 * в которую они попадали бы, не существовало. Здесь проверяется то, что делает
 * владелец: видит блок на **каждый** склад, вписывает реквизиты, пометку
 * об НДС и текст про гарантию — и всё это уезжает одним запросом.
 */
describe('печатные формы в настройках', () => {
  let saved: Record<string, unknown>;
  let sent: unknown[] = [];

  beforeEach(() => {
    sent = [];
    saved = {
      extraText: null,
      vatNote: null,
      clientSignature: true,
      issuerSignature: false,
      legal: {
        name: null, address: null, inn: null, kpp: null, bankName: null,
        bankBic: null, bankAccount: null, bankCorrAccount: null,
        director: null, chiefAccountant: null,
      },
      warehouses: [
        { id: 2, name: 'Ткацкая', details: null },
        { id: 3, name: 'Основной склад на Ткацкой, бокс 3 (второй этаж)', details: null },
      ],
    };

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';

      if (url === '/api/company/print-settings' && method === 'PUT') {
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        sent.push(body);
        saved = body;
        return json(saved);
      }
      if (url === '/api/company/print-settings') {
        return json(saved);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('блок реквизитов стоит у каждого склада, а не один на компанию', async () => {
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Печатные формы'));

    // Два склада — два поля: у клиента ориентира на двух складах разные ИП,
    // и общий блок напечатал бы на чеке не того продавца.
    await waitFor(() => expect(
      screen.getByLabelText('Реквизиты склада: Ткацкая')).toBeTruthy());
    expect(screen.getByLabelText(
      'Реквизиты склада: Основной склад на Ткацкой, бокс 3 (второй этаж)')).toBeTruthy();

    // Пояснение к разделу перенесено с ориентира дословно: клиент приходит
    // оттуда и узнаёт его.
    expect(screen.getByText(/Данные о складах используются для накладных/)).toBeTruthy();
  });

  it('сохраняет реквизиты складов, пометку об НДС и текст гарантии одним запросом',
    async () => {
      render(<SettingsScreen />);
      fireEvent.click(screen.getByText('Печатные формы'));
      await waitFor(() => expect(
        screen.getByLabelText('Реквизиты склада: Ткацкая')).toBeTruthy());

      fireEvent.change(screen.getByLabelText('Реквизиты склада: Ткацкая'), {
        target: { value: 'ИП Санин Д.В., Барнаул, Ткацкая 626' },
      });
      fireEvent.change(screen.getByLabelText('Пометка об НДС'), {
        target: { value: 'в том числе НДС 5% - ### руб.' },
      });
      fireEvent.change(screen.getByLabelText('Дополнительный текст'), {
        target: { value: 'Гарантия 14 календарных дней' },
      });
      fireEvent.change(screen.getByLabelText('ИНН'), { target: { value: '7701234567' } });

      fireEvent.click(screen.getByText('Сохранить'));

      await waitFor(() => expect(sent.length).toBe(1));
      const body = sent[0] as {
        vatNote: string; extraText: string;
        legal: { inn: string };
        warehouses: { id: number; details: string | null }[];
      };
      expect(body.vatNote).toBe('в том числе НДС 5% - ### руб.');
      expect(body.extraText).toBe('Гарантия 14 календарных дней');
      expect(body.legal.inn).toBe('7701234567');
      // Оба склада уезжают одной формой: вторым запросом они могли бы
      // сохраниться наполовину, и владелец ушёл бы со страницы уверенным,
      // что реквизиты заданы у обоих.
      expect(body.warehouses.map((w) => w.details))
        .toEqual(['ИП Санин Д.В., Барнаул, Ткацкая 626', null]);
    });

  it('кнопка не молчит: пока ничего не изменено, сказано почему', async () => {
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Печатные формы'));
    await waitFor(() => expect(
      screen.getByLabelText('Реквизиты склада: Ткацкая')).toBeTruthy());

    expect((screen.getByText('Сохранить') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Ничего не изменено')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Пометка об НДС'), {
      target: { value: 'НДС не облагается' },
    });
    expect((screen.getByText('Сохранить') as HTMLButtonElement).disabled).toBe(false);
  });

  it('про пустую пометку об НДС сказано, что строки в документе не будет', async () => {
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Печатные формы'));

    await waitFor(() => expect(
      screen.getByText(/Пустая пометка в документ не печатается вовсе/)).toBeTruthy());
  });

  it('не смогли узнать — сказано почему, а не вечное «Загружаем…»', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/company/print-settings') {
        return json({ message: 'Настройки печати ещё не накатаны' }, 409);
      }
      return json([]);
    }));

    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Печатные формы'));

    await waitFor(() => expect(
      screen.getByText('Настройки печати ещё не накатаны')).toBeTruthy());
    // «Загружаем…» показывается, пока грузим, а не пока пусто.
    expect(screen.queryByText('Загружаем…')).toBeNull();
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
