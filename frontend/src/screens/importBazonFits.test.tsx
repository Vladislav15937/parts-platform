import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { ImportScreen } from './ImportScreen';

/**
 * Описания объявлений из файла Bazon на Дром (задача 0337): блок «подходит»
 * и текстовые блоки переезжают отдельным действием на экране «Импорт».
 */
describe('перенос описаний из файла Bazon', () => {
  let sent: Array<{ url: string; file: File | null }>;

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/api/import/bazon/fits') && init?.method === 'POST') {
        const form = init.body as FormData;
        sent.push({ url, file: form.get('file') as File | null });
        return json({
          items: 120, notFound: 3, fitsFilled: 90, fitsSame: 10, fitsDiffers: 2,
          textBlocksSet: 100, textBlockBusy: 5, textBlocksNew: 1, tailUnclear: 8,
        });
      }
      if (url.includes('/api/import/bazon/photos')) {
        return json({ done: 0, failed: 0, pending: 0, total: 0, broken: 0 });
      }
      return json({});
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('кнопка гаснет без файла; файл уезжает ключом file; итог называет каждое число', async () => {
    render(<ImportScreen reference={reference()} canImport />);

    const run = button('Перенести описания')!;
    expect((run as HTMLButtonElement).disabled, 'кнопка без файла').toBe(true);

    const input = document.querySelector('input[accept=".xml"]') as HTMLInputElement;
    const file = new File(['<parts/>'], 'drom-parts.xml', { type: 'application/xml' });
    fireEvent.change(input, { target: { files: [file] } });
    expect((button('Перенести описания') as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(button('Перенести описания')!);

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!.file?.name).toBe('drom-parts.xml');
    await waitFor(() => expect(screen.getByText(/Позиций в файле: 120, нет на складе: 3/)).toBeTruthy());
    expect(screen.getByText(/перенесён 90, уже был такой 10, у позиции другой — не тронут: 2/)).toBeTruthy();
    expect(screen.getByText(/выбран у 100, заведено блоков 1, у позиции уже есть свой — не тронут: 5/)).toBeTruthy();
    expect(screen.getByText(/не отличить от комментария — не тронут: 8/)).toBeTruthy();
  });
});

function button(text: string): HTMLElement | undefined {
  return [...document.querySelectorAll('button')].find((b) => b.textContent === text);
}

function reference(): never {
  return { warehouses: [], supplies: [], donors: [], cells: [], partNames: [] } as never;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
