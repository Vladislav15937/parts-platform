import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { LEGAL_FIELDS, printSettings, savePrintSettings } from '../settings/print';
import type { PrintSettings } from '../settings/print';
import type { PrintLegal } from '../sales/dealPrint';
import { useMounted } from '../ui/useMounted';

/**
 * «Печатные формы» — раздел «Настроек», владелец (задача 0051).
 *
 * <p><b>Два раздела, как у ориентира:</b> «Документы для печати» (реквизиты
 * по складам, текст про гарантию, пометка об НДС, поля подписей) и «Реквизиты
 * юр. лица». Разделены они не для красоты: блок склада отвечает на «кто
 * продал» и печатается в чеке, реквизиты организации — на «кому платить»
 * и печатаются в счёте. У клиента ориентира на двух складах стоят разные ИП,
 * то есть один уровень другой не заменяет.
 *
 * <p><b>Форма отправляется целиком и одним запросом</b> — наполовину
 * сохранённая настройка оставила бы владельца уверенным, что реквизиты заданы,
 * а узнал бы он правду по напечатанному чеку без продавца.
 *
 * <p>Подписи полей и пояснение к разделу складов перенесены с ориентира
 * дословно (§7 `docs/bazon-parity.md`): клиент приходит оттуда и узнаёт их.
 * Форма своя — раздел у клиента открыт только на просмотр, и подражать
 * порядку шагов, которого никто не видел, значит подражать догадке.
 */
export function PrintFormsPanel() {
  const [saved, setSaved] = useState<PrintSettings | null>(null);
  const [draft, setDraft] = useState<PrintSettings | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void load();
  }, []);

  if (draft === null) {
    return (
      <div className="card">
        <h3>Печатные формы</h3>
        {error !== '' && <p className="note note--error">{error}</p>}
        {/* «Загружаем…» показывается, пока грузим, а не пока пусто: правило
            из корневого CLAUDE.md, на которое проект наступал четырьмя
            экранами сразу. */}
        {!loadFailed && <p className="note">Загружаем…</p>}
      </div>
    );
  }

  const changed = saved !== null && JSON.stringify(saved) !== JSON.stringify(draft);

  return (
    <div className="card">
      <h3>Печатные формы</h3>
      <p className="note">
        Настройки печати товарного чека и накладной
      </p>

      {error !== '' && <p className="note note--error">{error}</p>}
      {notice !== '' && <p className="note">{notice}</p>}

      <h4>Данные для складов</h4>
      <p className="note">
        Данные о складах используются для накладных в сделках, возвратах,
        списаниях, перемещениях а также для товарных чеков в сделке
      </p>
      {draft.warehouses.length === 0 ? (
        <p className="note">
          Складов нет — заведите их в разделе «Склады», тогда появится, куда
          писать реквизиты.
        </p>
      ) : (
        draft.warehouses.map((warehouse) => (
          <label className="field" key={warehouse.id}>
            {warehouse.name}
            <textarea
              rows={3}
              aria-label={`Реквизиты склада: ${warehouse.name}`}
              placeholder="Название компании, адрес и контакты"
              value={warehouse.details ?? ''}
              onChange={(e) => setWarehouse(warehouse.id, e.target.value)}
            />
          </label>
        ))
      )}

      <h4>Товарный чек и накладная в сделках</h4>
      <label className="field">
        Дополнительный текст
        <textarea
          rows={3}
          placeholder="Условия возврата, гарантии на товары и пр."
          value={draft.extraText ?? ''}
          onChange={(e) => setDraft({ ...draft, extraText: e.target.value })}
        />
      </label>
      <label className="field">
        Пометка об НДС
        <input
          placeholder="в том числе НДС 5% - ### руб."
          value={draft.vatNote ?? ''}
          onChange={(e) => setDraft({ ...draft, vatNote: e.target.value })}
        />
      </label>
      {/* Пусто означает «не заполнено», а не «НДС 0»: пустая пометка
          не печатает строку вовсе. Сказать это надо здесь — иначе владелец
          будет искать строку в документе и решит, что печать сломана. */}
      <p className="note">
        Пустая пометка в документ не печатается вовсе — строки про НДС в нём
        не будет.
      </p>

      <label className="field field--check">
        <input
          type="checkbox"
          checked={draft.clientSignature}
          onChange={(e) => setDraft({ ...draft, clientSignature: e.target.checked })}
        />
        Поле подписи для клиента
      </label>
      <label className="field field--check">
        <input
          type="checkbox"
          checked={draft.issuerSignature}
          onChange={(e) => setDraft({ ...draft, issuerSignature: e.target.checked })}
        />
        Поле подписи выдавшего товар
      </label>

      <h4>Реквизиты юр. лица</h4>
      <p className="note">
        Используются при печати накладных в поступлениях и счетах на юр.лицо
        в сделках
      </p>
      {LEGAL_FIELDS.map((field) => (
        <label className="field" key={field.key}>
          {field.label}
          <input
            placeholder={field.placeholder}
            value={draft.legal[field.key] ?? ''}
            onChange={(e) => setLegal(field.key, e.target.value)}
          />
        </label>
      ))}

      <button type="button" disabled={busy || !changed} onClick={() => void save()}>
        Сохранить
      </button>
      {/* Серая кнопка обязана называть причину — тем же приёмом, что
          у срока резервирования и у оплаты: одно условие на кнопку
          и на подпись. */}
      {!changed && <p className="note">Ничего не изменено</p>}
    </div>
  );

  function setWarehouse(id: number, details: string): void {
    if (draft === null) return;
    setDraft({
      ...draft,
      warehouses: draft.warehouses.map((w) => (w.id === id ? { ...w, details } : w)),
    });
  }

  function setLegal(key: keyof PrintLegal, value: string): void {
    if (draft === null) return;
    setDraft({ ...draft, legal: { ...draft.legal, [key]: value } });
  }

  async function load(): Promise<void> {
    try {
      const found = await printSettings();
      if (mounted.current) {
        setSaved(found);
        setDraft(found);
        setError('');
        setLoadFailed(false);
      }
    } catch (cause) {
      if (mounted.current) {
        setLoadFailed(true);
        setError(describe(cause, 'Настройки печати не загрузились'));
      }
    }
  }

  async function save(): Promise<void> {
    if (draft === null) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const written = await savePrintSettings(draft);
      if (!mounted.current) return;
      setSaved(written);
      setDraft(written);
      setNotice('Сохранено. Новые документы печатаются с этими реквизитами.');
    } catch (cause) {
      if (mounted.current) setError(describe(cause, 'Настройки печати не сохранены'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  return cause instanceof ApiError && cause.message !== '' ? cause.message : fallback;
}
