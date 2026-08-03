import { useEffect } from 'react';

/**
 * Держит место в списке, пока открыто окно поверх него.
 *
 * Колесо над карточкой прокручивает таблицу под ней: закрыв карточку,
 * владелец оказывается в другом месте списка на тридцать пять тысяч строк
 * и ищет заново ту, которую только что смотрел. Та же порода ошибки, что
 * и со вставкой строки под курсором, — поймано живым прогоном, тестами
 * не видно: на рендер их в проекте нет.
 *
 * Запирается и html, и body: страницу прокручивает documentElement —
 * у body высота равна содержимому, — и привычное body.style.overflow
 * здесь не делает ничего. Проверено измерением.
 *
 * А позиция возвращается при закрытии, потому что одного запрета мало:
 * Chrome при overflow: hidden на корне всё равно прокручивает колесом,
 * и это тоже измерено, а не предположено. Возврат работает независимо
 * от того, удался запрет или нет.
 */
export function useLockedScroll(): void {
  useEffect(() => {
    const page = document.documentElement;
    const wasPage = page.style.overflow;
    const wasBody = document.body.style.overflow;
    const at = window.scrollY;

    page.style.overflow = 'hidden';
    document.body.style.overflow = 'hidden';

    return () => {
      page.style.overflow = wasPage;
      document.body.style.overflow = wasBody;
      window.scrollTo(0, at);
    };
  }, []);
}
