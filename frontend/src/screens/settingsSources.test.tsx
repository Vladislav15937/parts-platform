import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

import { SettingsScreen } from './SettingsScreen';

/**
 * Экран «Настройки»: источники платежей и источники сделок.
 *
 * <p>Обе таблицы жили в схеме и в контроллерах с самого начала — продавец
 * выбирает их при каждой продаже и оплате, — а завести новый источник
 * или снять лишний с работы было нечем, кроме SQL. Ровно та ловушка
 * из корневого CLAUDE.md: поле есть, человеку недоступно.
 */
describe('источники платежей', () => {
  let paymentSourcesData: Array<{
    id: number; name: string; sourceType: string | null; archived: boolean;
  }> = [];
  let created: unknown = null;

  beforeEach(() => {
    paymentSourcesData = [];
    created = null;

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';

      if (url.includes('/api/deal-sources')) {
        return json([]);
      }
      if (url === '/api/payment-sources' && method === 'POST') {
        const body = JSON.parse(String(init?.body)) as { name: string; sourceType: string | null };
        created = body;
        const taken = paymentSourcesData.some((s) => s.name === body.name);
        if (taken) {
          return json({ message: `Источник «${body.name}» уже заведён` }, 400);
        }
        const row = { id: paymentSourcesData.length + 1, name: body.name,
                      sourceType: body.sourceType, archived: false };
        paymentSourcesData.push(row);
        return json(row, 201);
      }
      if (/\/api\/payment-sources\/\d+\/archive/.exec(url)) {
        const id = Number(url.match(/(\d+)\/archive/)![1]);
        const row = paymentSourcesData.find((s) => s.id === id)!;
        row.archived = true;
        return json(row);
      }
      if (/\/api\/payment-sources\/\d+\/unarchive/.exec(url)) {
        const id = Number(url.match(/(\d+)\/unarchive/)![1]);
        const row = paymentSourcesData.find((s) => s.id === id)!;
        row.archived = false;
        return json(row);
      }
      if (url === '/api/payment-sources') {
        return json(paymentSourcesData);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('пустой раздел говорит ровно заданный текст', async () => {
    render(<SettingsScreen />);

    await waitFor(() => expect(screen.getByText(
      'Источники платежей не заведены. Пока их нет, оплата записывается без способа.',
    )).toBeTruthy());
  });

  it('заведённый источник появляется в таблице с подписью типа', async () => {
    render(<SettingsScreen />);
    await waitFor(() => screen.getByPlaceholderText('ККМ'));

    fireEvent.change(screen.getByPlaceholderText('ККМ'), { target: { value: 'ККМ' } });
    fireEvent.change(screen.getByLabelText('Тип источника'), { target: { value: 'CASH' } });
    fireEvent.click(screen.getByText('Добавить источник'));

    await waitFor(() => expect(created).toEqual({ name: 'ККМ', sourceType: 'CASH' }));
    await waitFor(() => {
      const table = screen.getByRole('table');
      expect(within(table).getByText('Наличный расчёт')).toBeTruthy();
    });
  });

  it('повтор названия отвечает словами, а не «Операция нарушает целостность данных»', async () => {
    paymentSourcesData.push({ id: 1, name: 'ККМ', sourceType: null, archived: false });
    render(<SettingsScreen />);
    await waitFor(() => screen.getByText('ККМ'));

    fireEvent.change(screen.getByPlaceholderText('ККМ'), { target: { value: 'ККМ' } });
    fireEvent.click(screen.getByText('Добавить источник'));

    await waitFor(() => expect(screen.getByText('Источник «ККМ» уже заведён')).toBeTruthy());
  });

  it('архивная строка серая с плашкой и уходит из активных, обратно — по кнопке', async () => {
    paymentSourcesData.push({ id: 1, name: 'р/с Альфа банк', sourceType: 'BANK_ACCOUNT',
                              archived: false });
    render(<SettingsScreen />);
    await waitFor(() => screen.getByText('р/с Альфа банк'));

    fireEvent.click(screen.getByText('В архив'));

    await waitFor(() => expect(screen.getByText('Архивный')).toBeTruthy());
    expect(screen.getByText('Вернуть из архива')).toBeTruthy();

    fireEvent.click(screen.getByText('Вернуть из архива'));
    await waitFor(() => expect(screen.getByText('В архив')).toBeTruthy());
  });
});

describe('источники сделок', () => {
  let dealSourcesData: Array<{ id: number; name: string; archived: boolean }> = [];

  beforeEach(() => {
    dealSourcesData = [];

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';

      if (url.includes('/api/payment-sources')) {
        return json([]);
      }
      if (url === '/api/deal-sources' && method === 'POST') {
        const body = JSON.parse(String(init?.body)) as { name: string };
        const taken = dealSourcesData.some((s) => s.name === body.name);
        if (taken) {
          return json({ message: `Источник «${body.name}» уже заведён` }, 400);
        }
        const row = { id: dealSourcesData.length + 1, name: body.name, archived: false };
        dealSourcesData.push(row);
        return json(row, 201);
      }
      if (url === '/api/deal-sources') {
        return json(dealSourcesData);
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('одна колонка «Источник», без типа', async () => {
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Источники сделок'));

    fireEvent.change(await waitFor(() => screen.getByPlaceholderText('Авито')),
      { target: { value: 'Авито' } });
    fireEvent.click(screen.getByText('Добавить источник'));

    await waitFor(() => expect(screen.getByText('Авито')).toBeTruthy());
    expect(screen.queryByText('Тип источника')).toBeNull();
  });

  it('повтор названия отвечает словами', async () => {
    dealSourcesData.push({ id: 1, name: 'Авито', archived: false });
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Источники сделок'));

    fireEvent.change(await waitFor(() => screen.getByPlaceholderText('Авито')),
      { target: { value: 'Авито' } });
    fireEvent.click(screen.getByText('Добавить источник'));

    await waitFor(() => expect(screen.getByText('Источник «Авито» уже заведён')).toBeTruthy());
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
