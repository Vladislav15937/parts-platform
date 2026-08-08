import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

import { DeliveryScreen } from './DeliveryScreen';
import { FeedsScreen } from './FeedsScreen';
import { OrdersScreen } from './OrdersScreen';

/**
 * Неудачная загрузка не выдаётся ни за пустоту, ни за вечную загрузку.
 *
 * <p><b>Зачем.</b> Пустой список получается двумя способами: данных нет
 * и узнать не удалось. Разница видна человеку, а коду — нет, если условие
 * показа смотрит только на длину.
 *
 * <p>Поймано живым прогоном с истёкшей сессией: экран «Доставка» показал
 * рядом красное «Запрос отклонён (401)» и успокаивающее «Всё доставлено».
 * Этот экран — про то, дошли ли дельты до площадки; ложное спокойствие
 * тут стоит непринятых событий, за которыми никто не пойдёт. У соседей
 * та же болезнь с другой стороны: «Выгрузки» и «Заказы» при ошибке
 * показывали «Загружаем…» бесконечно, а причина лежала рядом непоказанной.
 */
describe('экран при неудачной загрузке', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('«Доставка» не говорит «всё доставлено», когда не смогла узнать', async () => {
    refuse();
    render(<DeliveryScreen canManage onTotalChanged={() => {}} />);

    await waitFor(() => expect(screen.getByText(/Сессия кончилась/)).toBeTruthy());
    expect(screen.queryByText(/Всё доставлено/),
      'непринятые события выданы за их отсутствие').toBeNull();
  });

  it('«Выгрузки» показывают причину, а не вечное «Загружаем»', async () => {
    refuse();
    render(<FeedsScreen role="OWNER" />);

    await waitFor(() => expect(screen.getByText(/Сессия кончилась/)).toBeTruthy());
    expect(screen.queryByText(/Загружаем выгрузки/),
      'экран висит на загрузке, а причина не показана').toBeNull();
  });

  it('«Заказы» — тоже', async () => {
    refuse();
    render(<OrdersScreen canSell />);

    await waitFor(() => expect(screen.getByText(/Сессия кончилась/)).toBeTruthy());
    expect(screen.queryByText(/Загружаем заказы/)).toBeNull();
  });
});

/** Так выглядит истёкшая сессия: сервер отвечает 401 на любой запрос. */
function refuse(): void {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(
    JSON.stringify({ message: 'Сессия кончилась — войдите заново' }),
    { status: 401, headers: { 'Content-Type': 'application/json' } },
  )));
}
