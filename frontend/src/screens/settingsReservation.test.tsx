import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { SettingsScreen } from './SettingsScreen';

/**
 * «Срок резервирования сделок» — раздел экрана «Настройки» (задача 0049).
 *
 * <p>До этой правки число было зашито в сборку, и разборка, держащая резерв
 * сутки, правила срок руками в каждой сделке. Здесь проверяется то, что видит
 * и делает владелец: показанное значение, отправленное число и слова,
 * которыми экран отвечает на «а что будет с уже открытыми сделками».
 */
describe('срок резервирования в настройках', () => {
  let days = 3;
  let sent: unknown[] = [];

  beforeEach(() => {
    days = 3;
    sent = [];

    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';

      if (url === '/api/company/settings' && method === 'PUT') {
        const body = JSON.parse(String(init?.body)) as { reservationDays: number };
        sent.push(body);
        if (body.reservationDays < 1 || body.reservationDays > 365) {
          return json({ message: 'Срок резервирования — от 1 до 365 дней' }, 400);
        }
        days = body.reservationDays;
        return json({ reservationDays: days });
      }
      if (url === '/api/company/settings') {
        return json({ reservationDays: days });
      }
      return json([]);
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показывает поставленный срок словами и пояснения ориентира', async () => {
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Срок резервирования'));

    await waitFor(() => expect(screen.getByText('3 дня')).toBeTruthy());
    expect(screen.getByText('Срок резервирования сделок')).toBeTruthy();
    expect(screen.getByText(
      'Используется как параметр по умолчанию для всех новых сделок')).toBeTruthy();
    expect(screen.getByText('Срок резерва всегда можно изменить в самой сделке')).toBeTruthy();
    // Про уже открытые сделки экран обязан сказать словами: иначе владелец
    // гадает, не сдвинулись ли полсотни отложенных разом.
    expect(screen.getByText(/Уже оформленные сделки свой срок не меняют/)).toBeTruthy();
  });

  it('сохраняет новый срок и показывает его склонённым', async () => {
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Срок резервирования'));
    await waitFor(() => expect(screen.getByText('3 дня')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Дней'), { target: { value: '1' } });
    fireEvent.click(screen.getByText('Сохранить'));

    await waitFor(() => expect(screen.getByText('1 день')).toBeTruthy());
    expect(sent).toEqual([{ reservationDays: 1 }]);
  });

  it('кнопка не молчит: пока срок не изменён, сказано почему', async () => {
    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Срок резервирования'));
    await waitFor(() => expect(screen.getByText('3 дня')).toBeTruthy());

    const save = screen.getByText('Сохранить') as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    expect(screen.getByText('Срок не изменён')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Дней'), { target: { value: '0' } });
    expect((screen.getByText('Сохранить') as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Срок резервирования — от 1 до 365 дней')).toBeTruthy();
    expect(sent).toEqual([]);
  });

  it('не смогли узнать — сказано почему, а не вечное «Загружаем…»', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/company/settings') {
        return json({ message: 'База недоступна' }, 500);
      }
      return json([]);
    }));

    render(<SettingsScreen />);
    fireEvent.click(screen.getByText('Срок резервирования'));

    await waitFor(() => expect(screen.getByText('База недоступна')).toBeTruthy());
    // «Загружаем…» показывается, пока грузим, а не пока пусто: правило
    // из корневого CLAUDE.md, на которое этот проект наступал четырьмя
    // экранами сразу.
    expect(screen.queryByText('Загружаем…')).toBeNull();
  });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
