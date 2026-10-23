import { lockedReason, useCatalogTitles, type PermissionKey } from '../access/rights';

/**
 * Действие, на которое у вошедшего нет права, — погашенным и с причиной.
 *
 * <p>Решение владельца 23.10.2026 (задача 0044, вопрос 3): «погашен
 * с причиной». До 0044 экран для роли без права молчал о том, чего она
 * не может, — кнопки просто не было, и сотрудник не знал, что такое бывает
 * и у кого просить. Теперь кнопка на месте, погашена, а рядом сказано, какого
 * права нет и кто его даёт. Причина — видимым текстом, а не только подсказкой
 * по наведению: на телефоне наводить нечем.
 *
 * <p>Слово права — справочником сервера (`useCatalogTitles`); без связи
 * причина называется общими словами, но не пропадает.
 */
export function LockedAction({
  permission, label, className = 'button--ghost',
}: {
  permission: PermissionKey;
  label: string;
  className?: string;
}) {
  const titles = useCatalogTitles();
  const reason = lockedReason(permission, titles);
  return (
    <span className="locked-action">
      <button type="button" className={className} disabled title={reason}>
        {label}
      </button>
      <span className="note locked-action__reason">{reason}</span>
    </span>
  );
}
