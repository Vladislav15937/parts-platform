import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { momentText } from '../organization/adminAccess';
import { useMounted } from '../ui/useMounted';
import { navigation } from './navigation';
import { adminMe, leaveCabinet, remainingText, type AdminMe } from './adminApi';
import { ADMIN_PATH } from './AdminScreen';

/**
 * Полоса над кабинетом, пока в нём работает администратор сервиса (задача 0042).
 *
 * <p>Две вещи, и обе из критерия приёмки. Первая — срок: «срок окончания
 * виден обеим сторонам», и администратору — сколько осталось; без полосы
 * доступ кончился бы для него посреди правки внезапным «войдите заново».
 * Вторая — путь обратно в систему администрирования: кабинет не его,
 * и «Выйти» здесь означало бы выйти совсем.
 */
export function AdminVisitBanner() {
  const [me, setMe] = useState<AdminMe | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const mounted = useMounted();

  useEffect(() => {
    adminMe()
      .then((found) => { if (mounted.current) setMe(found); })
      .catch((cause) => {
        if (mounted.current) setError(describe(cause, 'Срок доступа не загрузился'));
      });
  }, [mounted]);

  const visit = me?.visit ?? null;
  return (
    <div className="note">
      <strong>Вы в кабинете как администратор сервиса.</strong>{' '}
      {visit === null
        ? (error === '' ? 'Узнаём срок доступа…' : error)
        : visit.expiresAt === null
          ? 'Доступ открыт владельцем до отзыва.'
          : `Доступ открыт владельцем до ${momentText(visit.expiresAt)} · осталось ${remainingText(visit.expiresAt)}.`}{' '}
      <button type="button" className="button--ghost" disabled={busy} onClick={() => void leave()}>
        Вернуться в администрирование
      </button>
    </div>
  );

  async function leave(): Promise<void> {
    setBusy(true);
    try {
      await leaveCabinet();
    } catch {
      // Доступ уже закрыт или сессия кончилась — вернуться всё равно туда:
      // страница администрирования сама спросит вход.
    }
    navigation.go(ADMIN_PATH);
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
