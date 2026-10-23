import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { forgetCatalog } from '../access/rights';
import { SessionProvider } from '../auth/SessionProvider';
import { HomeScreen } from './HomeScreen';

/**
 * Раздел без права — погашен и называет причину, а не спрятан (задача 0044).
 *
 * <p>Решение владельца 23.10.2026, вопрос 3, дословно: «погашен с причиной».
 * До 0044 рельс показывал роли только её разделы: сотрудник не знал, что
 * «Сделки» вообще бывают и у кого просить, а владелец, давший право, не мог
 * проверить по экрану сотрудника, что оно дошло.
 *
 * <p>Права приезжают с сервера в «кто я» — своего списка ролей у экрана нет.
 * Прежняя личность в кэше телефона (запомненная до выкладки, без набора прав)
 * получает права своей прежней роли: иначе приёмщик в ангаре без связи увидел
 * бы погашенную приёмку в день выкладки.
 */
describe('погашенные разделы рельса', () => {
  let me: Record<string, unknown>;
  let requested: string[];

  beforeEach(() => {
    forgetCatalog();
    requested = [];
    me = {
      memberId: 5, login: 'smotrit', displayName: 'Иван', role: 'VIEWER',
      companySchema: 't_000441', owner: false, permissions: ['inventory.journal.view'],
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requested.push(url);
      if (url.includes('/api/auth/csrf')) {
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/auth/me')) {
        return json(me);
      }
      if (url.endsWith('/api/access/catalog')) {
        return json([
          { title: 'Приёмка', permissions: [
            { key: 'intake.receive', title: 'Принимать товар', hint: null }] },
          { title: 'Продажи', permissions: [
            { key: 'deal.sell', title: 'Продавать', hint: null }] },
          { title: 'Пересчёт', permissions: [
            { key: 'inventory.count', title: 'Считать товар на пересчёте', hint: null },
            { key: 'inventory.journal.view', title: 'Смотреть журнал пересчётов', hint: null },
            { key: 'inventory.reconcile', title: 'Сводить расхождения пересчёта', hint: null }] },
        ]);
      }
      if (url.endsWith('/api/intake/reference')) {
        return json({ loadedAt: new Date().toISOString(), warehouses: [], supplies: [],
          donors: [], partNames: [], expectedParts: [] });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('раздел без права виден погашенным, открывается причиной и ничего не грузит', async () => {
    render(<SessionProvider><HomeScreen /></SessionProvider>);

    const deals = await waitFor(() => screen.getByRole('button',
      { name: 'Сделки — Нет права «Продавать» — его даёт владелец на экране «Сотрудники»' }));
    expect(deals.className, 'раздел без права не погашен').toContain('rail__item--locked');

    fireEvent.click(deals);
    await waitFor(() => expect(screen.getByText(
      'Нет права «Продавать» — его даёт владелец на экране «Сотрудники».')).toBeTruthy());
    expect(requested.some((url) => url.includes('/api/deals/')),
      'погашенный раздел пошёл за данными, которые сервер отобьёт').toBe(false);

    // Открытое праву — обычной кнопкой, без отметки.
    const inventory = screen.getByRole('button', { name: 'Пересчёт' });
    expect(inventory.className).not.toContain('rail__item--locked');
  });

  it('без права принимать товар приёмка погашена и называет причину', async () => {
    render(<SessionProvider><HomeScreen /></SessionProvider>);

    const intake = await waitFor(() => screen.getByRole('button',
      { name: /^Приёмка — Нет права «Принимать товар»/ }));
    fireEvent.click(intake);
    await waitFor(() => expect(screen.getByText(/^Нет права «Принимать товар»/)).toBeTruthy());
    expect(screen.queryByText('Добавить в партию'), 'форма приёмки открыта без права').toBeNull();
  });

  it('личность, запомненная до 0044, получает права прежней роли', async () => {
    me = { memberId: 6, login: 'kladovshchik', displayName: 'Пётр', role: 'STOREKEEPER',
      companySchema: 't_000441' };
    render(<SessionProvider><HomeScreen /></SessionProvider>);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Приёмка' })).toBeTruthy());
    expect(screen.getByRole('button', { name: 'Приёмка' }).className)
      .not.toContain('rail__item--locked');
    expect(screen.getByRole('button', { name: /^Сделки — Нет права/ })).toBeTruthy();
  });

  it('раздел, открытый любым из нескольких прав, называет их все', async () => {
    me = { ...me, permissions: [] };
    render(<SessionProvider><HomeScreen /></SessionProvider>);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Пересчёт — Нет ни одного из прав: '
      + '«Считать товар на пересчёте», «Смотреть журнал пересчётов» или «Сводить расхождения '
      + 'пересчёта» — их даёт владелец на экране «Сотрудники»' })).toBeTruthy());
  });

  it('у владельца погашенного нет', async () => {
    me = { memberId: 1, login: 'vladelec', displayName: 'Хозяин', role: 'OWNER',
      companySchema: 't_000441', owner: true, permissions: [] };
    render(<SessionProvider><HomeScreen /></SessionProvider>);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Сотрудники' })).toBeTruthy());
    expect(document.querySelectorAll('.rail__item--locked').length,
      'у владельца права на всё и всегда — пустой набор этого не сужает').toBe(0);
  });
});

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
