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
 * @param foreign права, которых нет у того, кто правит (задача 0044, 2б):
 *               отметить их нельзя — галочка погашена и подписана; снять
 *               уже отмеченное можно, иначе набор было бы не сузить
 */
export function AccessPermissions({
  sections, chosen, onToggle, marked, locked, foreign,
}: {
  sections: readonly Section[];
  chosen: ReadonlySet<PermissionKey>;
  onToggle: (key: PermissionKey, on: boolean) => void;
  marked?: (key: PermissionKey) => string | null;
  locked?: string | undefined;
  foreign?: (key: PermissionKey) => boolean;
}) {
  return (
    <div className="access-permissions">
      {locked !== undefined && <p className="note">{locked}</p>}
      {sections.map((section) => (
        <fieldset key={section.title} className="choices">
          <legend>{section.title}</legend>
          {section.permissions.map((action) => {
            // У погашенного набора (владелец) пометок «поверх роли» нет: их
            // и не бывает, а подпись у каждого из сорока прав врала бы о данных
            // (живой прогон 24.10.2026: API отвечает added: [], removed: []).
            const mark = locked !== undefined ? null : marked?.(action.key) ?? null;
            const notMine = locked === undefined && foreign?.(action.key) === true;
            return (
              <label key={action.key} className="field--check">
                <input
                  type="checkbox"
                  checked={locked !== undefined || chosen.has(action.key)}
                  disabled={locked !== undefined || (notMine && !chosen.has(action.key))}
                  onChange={(e) => onToggle(action.key, e.target.checked)}
                />
                {' '}{action.title}
                {action.hint !== null && <span className="muted"> — {action.hint}</span>}
                {mark !== null && <strong> · {mark}</strong>}
                {notMine && <span className="muted"> · {NOT_MINE}</span>}
              </label>
            );
          })}
        </fieldset>
      ))}
    </div>
  );
}

/** Подпись у права, которого нет у правящего: причина видна, а не по наведению. */
export const NOT_MINE = 'этого права у вас нет';
