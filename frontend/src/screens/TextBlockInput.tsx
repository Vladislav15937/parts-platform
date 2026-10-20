import { useState } from 'react';
import { ApiError } from '../api/client';
import {
  createTextBlock, deleteTextBlock, updateTextBlock, type TextBlock,
} from '../inventory/textBlocks';
import { useMounted } from '../ui/useMounted';

type Mode = 'pick' | 'new' | 'edit';

/**
 * Выбор именованного текстового блока в карточке (задача 0337, как у Bazon:
 * «Текстовый блок» — выбор из списка по имени, текст уезжает в описание).
 *
 * <p>Блок можно завести и поправить здесь же: текст блока общий для всех
 * позиций, которые его выбрали, и об этом сказано под полем. Заведённый
 * блок сразу встаёт в поле, но в позицию он ляжет только вместе
 * с «Сохранить» карточки.
 */
export function TextBlockInput({ blocks, value, onPick, onBlocks, label }: {
  blocks: TextBlock[];
  /** Номер выбранного блока строкой; пусто — не выбран. */
  value: string;
  onPick: (id: string) => void;
  /** Список блоков изменился (завели или поправили). */
  onBlocks: (next: TextBlock[]) => void;
  label: string;
}) {
  const [mode, setMode] = useState<Mode>('pick');
  const [name, setName] = useState('');
  const [body, setBody] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const mounted = useMounted();
  const chosen = blocks.find((b) => String(b.id) === value) ?? null;

  function open(next: Mode): void {
    setError('');
    setName(next === 'edit' && chosen !== null ? chosen.name : '');
    setBody(next === 'edit' && chosen !== null ? chosen.body : '');
    setMode(next);
  }

  async function save(): Promise<void> {
    setBusy(true);
    setError('');
    try {
      if (mode === 'new') {
        const created = await createTextBlock(name, body);
        if (!mounted.current) return;
        onBlocks([...blocks, created]);
        onPick(String(created.id));
      } else if (chosen !== null) {
        const updated = await updateTextBlock(chosen.id, name, body);
        if (!mounted.current) return;
        onBlocks(blocks.map((b) => (b.id === updated.id ? updated : b)));
      }
      setMode('pick');
    } catch (e) {
      if (mounted.current) {
        setError(e instanceof ApiError ? e.message : 'Не удалось сохранить текстовый блок');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function remove(): Promise<void> {
    if (chosen === null) return;
    setBusy(true);
    setError('');
    try {
      await deleteTextBlock(chosen.id);
      if (!mounted.current) return;
      onBlocks(blocks.filter((b) => b.id !== chosen.id));
      onPick('');
      setMode('pick');
    } catch (e) {
      if (mounted.current) {
        setError(e instanceof ApiError ? e.message : 'Не удалось удалить текстовый блок');
      }
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  return (
    <span className="text-block-input">
      <select aria-label={label} autoFocus value={value}
              onChange={(e) => onPick(e.target.value)}>
        <option value="">— не выбран —</option>
        {blocks.map((b) => <option key={b.id} value={String(b.id)}>{b.name}</option>)}
      </select>
      {chosen !== null && mode === 'pick' && (
        <span className="muted"> {chosen.body}</span>
      )}
      {mode === 'pick' && (
        <span className="row">
          <button type="button" className="button--ghost" onClick={() => open('new')}>
            Новый блок
          </button>
          {chosen !== null && (
            <button type="button" className="button--ghost" onClick={() => open('edit')}>
              Изменить блок
            </button>
          )}
        </span>
      )}
      {mode !== 'pick' && (
        <>
          <input aria-label="Название блока" value={name}
                 onChange={(e) => setName(e.target.value)} />
          <textarea aria-label="Текст блока" rows={4} value={body}
                    onChange={(e) => setBody(e.target.value)} />
          {mode === 'edit' && (
            <p className="muted">
              Текст блока общий: он поменяется во всех позициях, где выбран этот блок.
            </p>
          )}
          {error !== '' && <p className="note note--error">{error}</p>}
          <span className="row">
            <button type="button" disabled={busy || name.trim() === '' || body.trim() === ''}
                    onClick={() => void save()}>
              {mode === 'new' ? 'Создать блок' : 'Сохранить блок'}
            </button>
            {mode === 'edit' && (
              <button type="button" className="button--ghost" disabled={busy}
                      onClick={() => void remove()}>
                Удалить блок
              </button>
            )}
            <button type="button" className="button--ghost" onClick={() => setMode('pick')}>
              Отмена
            </button>
          </span>
        </>
      )}
    </span>
  );
}
