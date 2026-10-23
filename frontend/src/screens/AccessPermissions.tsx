import type { PermissionKey, Section } from '../access/rights';

/**
 * Галочки прав по разделам — одна на роль и на сотрудника.
 *
 * <p>Разделами, а не сплошным списком: прав сорок с лишним, и подряд их
 * владелец не прочтёт («восемьдесят галочек», о которых предупреждала
 * задача 0044). Под названием — что под правом мелко, словами сервера.
 *
 * @param marked права, отмеченные поверх роли: у сотрудника рядом пишется,
 *               что это его отдельное право, а не роли
 * @param locked причина, по которой галочки не меняются (владелец), —
 *               погашены и названы, а не спрятаны
 */
export function AccessPermissions({
  sections, chosen, onToggle, marked, locked,
}: {
  sections: readonly Section[];
  chosen: ReadonlySet<PermissionKey>;
  onToggle: (key: PermissionKey, on: boolean) => void;
  marked?: (key: PermissionKey) => string | null;
  locked?: string | undefined;
}) {
  return (
    <div className="access-permissions">
      {locked !== undefined && <p className="note">{locked}</p>}
      {sections.map((section) => (
        <fieldset key={section.title} className="choices">
          <legend>{section.title}</legend>
          {section.permissions.map((action) => {
            const mark = marked?.(action.key) ?? null;
            return (
              <label key={action.key} className="field--check">
                <input
                  type="checkbox"
                  checked={locked !== undefined || chosen.has(action.key)}
                  disabled={locked !== undefined}
                  onChange={(e) => onToggle(action.key, e.target.checked)}
                />
                {' '}{action.title}
                {action.hint !== null && <span className="muted"> — {action.hint}</span>}
                {mark !== null && <strong> · {mark}</strong>}
              </label>
            );
          })}
        </fieldset>
      ))}
    </div>
  );
}
