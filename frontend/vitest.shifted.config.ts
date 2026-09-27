/**
 * Тот же прогон, но с часами, переведёнными вперёд.
 *
 * <p>Отдельным файлом, а не флагом обычной настройки: сдвинутые часы —
 * это проверка проверок, и включаться она должна нарочно. Всё остальное
 * берётся из `vite.config.ts` как есть, чтобы два прогона отличались
 * ровно одним — показанием часов.
 *
 * <p>Зовёт его `tools/clock-shift-run.sh`; сдвиг задаётся `CLOCK_SHIFT_DAYS`.
 */
import { mergeConfig } from 'vitest/config';

import base from './vite.config';

export default mergeConfig(base, {
  test: {
    setupFiles: ['./src/test/setup.ts', './src/test/shiftedClock.ts'],
  },
});
