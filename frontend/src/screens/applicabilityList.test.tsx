import { rightsAs } from '../test/rights';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApplicabilityList } from './ApplicabilityList';

/**
 * «Применимость подробно» (задача 0363): автор и время установки, «Двигатель важен»,
 * три состояния списка различимы, правка признака уходит на сервер.
 */
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

const ROWS = [
  { partId: 7, number: 12, title: 'Брызговик', engineMatters: false, brand: 'Toyota',
    model: 'Camry', generation: '2001—2006', yearFrom: 2001, yearTo: 2006,
    setBy: 'Приёмщик', setAt: '2026-10-19T10:00:00Z' },
  { partId: 8, number: 11, title: 'Двигатель', engineMatters: true, brand: null,
    model: null, generation: null, yearFrom: null, yearTo: null, setBy: null, setAt: null },
];

describe('ApplicabilityList', () => {
  let sent: { url: string; method: string; body: string | null }[];

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      sent.push({ url, method: init?.method ?? 'GET', body: (init?.body as string) ?? null });
      if (url.includes('/engine-matters')) {
        return json({ engineMatters: true, note: null });
      }
      return json({ rows: ROWS, total: 2, size: 50 });
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показывает автора и время, у неизвестного — прочерк, а не выдуманное', async () => {
    render(<ApplicabilityList canEdit={rightsAs('OWNER').has('part.edit')} onClose={() => {}} />);
    expect(await screen.findByText('Приёмщик')).toBeTruthy();
    expect(screen.getByText('важен')).toBeTruthy();
    expect(screen.getByText('неважен')).toBeTruthy();
    // У второй строки автора и времени нет: два прочерка, плюс марка/модель/поколение/годы.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(6);
    expect(screen.getByText(/Строк 2 из 2/)).toBeTruthy();
  });

  it('владелец меняет признак — уходит PUT с новым значением', async () => {
    render(<ApplicabilityList canEdit={rightsAs('OWNER').has('part.edit')} onClose={() => {}} />);
    await screen.findByText('Приёмщик');
    fireEvent.click(screen.getAllByText('изменить')[0]!);
    await screen.findByText('Приёмщик');
    const put = sent.find((r) => r.method === 'PUT');
    expect(put?.url).toContain('/api/parts/catalog/7/engine-matters');
    expect(JSON.parse(put!.body!)).toEqual({ matters: true });
  });

  it('кладовщику правки признака не показывается', async () => {
    render(<ApplicabilityList canEdit={rightsAs('STOREKEEPER').has('part.edit')} onClose={() => {}} />);
    await screen.findByText('Приёмщик');
    expect(screen.queryByText('изменить')).toBeNull();
  });

  it('отказ сервера не выдаётся за пустой список', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'x' }, 500)));
    render(<ApplicabilityList canEdit={rightsAs('OWNER').has('part.edit')} onClose={() => {}} />);
    expect(await screen.findByText(/Не удалось получить список/)).toBeTruthy();
    expect(screen.queryByText('Позиций нет.')).toBeNull();
  });
});
