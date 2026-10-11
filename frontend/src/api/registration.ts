import { request } from './client';

/**
 * Регистрация компании клиентом без входа (задача 0184).
 *
 * <p>Путь открыт без сессии и без CSRF-токена: человека, который заводит
 * себя сам, система ещё не знает.
 */

/** Задание для вычислительной проверки. */
export interface Challenge {
  challenge: string;
  /** Сколько нулевых битов должно быть в начале хеша. */
  bits: number;
}

/**
 * Что пришло в ответ на регистрацию.
 *
 * <p>Без номера схемы и номера в реестре: человеку нужен код компании —
 * его он наберёт на входе.
 */
export interface Registered {
  companyCode: string;
  companyName: string;
  /** Это результат прежнего нажатия, а не новая компания. */
  replayed: boolean;
}

export interface RegistrationForm {
  /** Ключ повтора: тот же при каждой отправке этой формы, иначе появится вторая компания. */
  requestId: string;
  challenge: string;
  solution: string;
  companyName: string;
  companyCode: string;
  email: string;
  ownerLogin: string;
  ownerPassword: string;
  ownerName: string;
}

/**
 * Берёт задание. Отказывает 409, если регистрация закрыта потолком, — об
 * этом форма узнаёт при загрузке, а не после заполнения.
 */
export function fetchChallenge(): Promise<Challenge> {
  return request<Challenge>('/api/registration/challenge');
}

export function registerCompany(form: RegistrationForm): Promise<Registered> {
  return request<Registered>('/api/registration/companies', { method: 'POST', body: form });
}
