import { afterEach, describe, expect, it, vi } from 'vitest';

import { ProofCancelled, ProofUnavailable, leadingZeroBits, solveChallenge } from './proofOfWork';

/**
 * Вычислительная проверка перед регистрацией: подобранное число обязано
 * удовлетворять заданию так, как его проверяет сервер, — иначе форма считала бы
 * секунды впустую и получала бы «проверка не пройдена».
 *
 * <p>Результат сверяется <b>не той же функцией</b>, что подбирала: правило
 * «сколько нулевых бит в начале хеша» здесь выписано заново строкой из нулей
 * и единиц. Две записи одного правила в одном файле расходятся реже, чем одна,
 * проверяющая сама себя, — а ошибка в счёте битов дала бы решение, которое
 * сервер не примет.
 */
describe('вычислительная проверка регистрации', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('подбирает число, у которого хеш начинается с нужного числа нулевых бит', async () => {
    const challenge = 'v1.9999999999.0123456789abcdef.подпись';
    const bits = 10;

    const solution = await solveChallenge(challenge, bits);

    const digest = await crypto.subtle.digest(
      'SHA-256', new TextEncoder().encode(`${challenge}:${solution}`));
    const asBits = Array.from(new Uint8Array(digest), (b) => b.toString(2).padStart(8, '0')).join('');
    expect(asBits.startsWith('0'.repeat(bits))).toBe(true);
  });

  it('считает ведущие нулевые биты по границам байта', () => {
    expect(leadingZeroBits(new Uint8Array([0xff]))).toBe(0);
    expect(leadingZeroBits(new Uint8Array([0x7f]))).toBe(1);
    expect(leadingZeroBits(new Uint8Array([0x01]))).toBe(7);
    expect(leadingZeroBits(new Uint8Array([0x00, 0x80]))).toBe(8);
    expect(leadingZeroBits(new Uint8Array([0x00, 0x00, 0x10]))).toBe(19);
  });

  it('останавливается, когда экран закрыли, а не считает впустую', async () => {
    // Сложность недостижимо большая: без остановки подбор не кончился бы.
    await expect(solveChallenge('v1.x.y.z', 200, () => true)).rejects.toBeInstanceOf(ProofCancelled);
  });

  it('без crypto.subtle говорит словами, чем открыть страницу', async () => {
    vi.stubGlobal('crypto', {});
    await expect(solveChallenge('v1.x.y.z', 8)).rejects.toBeInstanceOf(ProofUnavailable);
    await expect(solveChallenge('v1.x.y.z', 8)).rejects.toThrow(/защищённому адресу/);
  });
});
