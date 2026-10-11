/**
 * Вычислительная проверка перед регистрацией компании (задача 0184).
 *
 * <p>Сервер выдаёт задание, браузер подбирает число так, чтобы
 * {@code SHA-256(задание + ":" + число)} начиналось с заданного числа нулевых
 * битов. Это замена капче, и честное её описание — на сервере
 * ({@code RegistrationChallenge}): человеку она ничего не стоит, скрипту
 * без браузера стоит вычисления на каждую регистрацию, а решающего противника
 * не останавливает — того держат предел с адреса и потолок числа компаний.
 *
 * <p>Считается в фоне, пока человек заполняет форму: к нажатию кнопки
 * решение, как правило, готово, и он ждёт только ответа сервера.
 */

/** Сколько хешей считать, не отдавая управление: меньше — лишние паузы, больше — тормозит ввод. */
const BATCH = 500;

/**
 * Сколько ведущих нулевых битов в начале хеша.
 *
 * <p>Вынесено отдельно и экспортировано: тест сверяет решение этой же
 * функцией, а не вторым написанием правила, которое разошлось бы с ней молча.
 */
export function leadingZeroBits(hash: Uint8Array): number {
  let count = 0;
  for (const byte of hash) {
    if (byte === 0) {
      count += 8;
      continue;
    }
    // Math.clz32 считает по 32 битам: у байта лишние 24 нуля впереди.
    count += Math.clz32(byte) - 24;
    break;
  }
  return count;
}

/** Браузер не умеет считать SHA-256: незащищённый адрес или очень старый браузер. */
export class ProofUnavailable extends Error {
  constructor() {
    super('Браузер не умеет проверку «вы не робот». Откройте страницу по защищённому адресу '
      + '(https) в современном браузере');
    this.name = 'ProofUnavailable';
  }
}

/** Вычисление прервано: экран закрыли раньше, чем нашлось решение. */
export class ProofCancelled extends Error {
  constructor() {
    super('Вычисление прервано');
    this.name = 'ProofCancelled';
  }
}

/**
 * Подбирает число, удовлетворяющее заданию.
 *
 * @param cancelled спрашивается между пачками: ушедший с экрана человек не
 *        должен оставлять браузер считать впустую, а колбэк — писать
 *        в размонтированный компонент
 * @throws ProofUnavailable нет {@code crypto.subtle}
 * @throws ProofCancelled {@code cancelled} вернул истину
 */
export async function solveChallenge(
  challenge: string,
  bits: number,
  cancelled: () => boolean = () => false,
): Promise<string> {
  const subtle = globalThis.crypto?.subtle;
  if (subtle === undefined) {
    throw new ProofUnavailable();
  }
  const encoder = new TextEncoder();

  for (let start = 0; ; start += BATCH) {
    if (cancelled()) {
      throw new ProofCancelled();
    }
    for (let n = start; n < start + BATCH; n++) {
      const hash = new Uint8Array(
        await subtle.digest('SHA-256', encoder.encode(`${challenge}:${n}`)));
      if (leadingZeroBits(hash) >= bits) {
        return String(n);
      }
    }
    // Отдать управление: иначе поле ввода замирает на время подбора.
    await new Promise<void>((resolve) => { setTimeout(resolve, 0); });
  }
}
