import { useEffect, useRef, useState } from 'react';
import { resizePhoto, type ResizedPhoto } from './resize';

/**
 * Съёмка детали.
 *
 * <p><b>Через {@code <input capture>}, а не через {@code getUserMedia}.</b>
 * Штатная камера телефона умеет то, что своим видоискателем не повторить:
 * автофокус по нажатию, вспышку, поворот, HDR — и приёмщик уже знает, как ей
 * пользоваться. {@code getUserMedia} вдобавок требует HTTPS и отдельного
 * разрешения, которое на чужом телефоне однажды нажмут «запретить», и починить
 * это из приложения будет нельзя.
 *
 * <p>Уменьшение делается сразу при выборе, а не перед отправкой: иначе в
 * IndexedDB осядут исходные мегабайты, и браузер начнёт вытеснять очередь.
 */

export interface PhotoPickerProps {
  photos: ResizedPhoto[];
  onChange: (photos: ResizedPhoto[]) => void;
  /** Больше десятка снимков на деталь не нужны ни площадкам, ни памяти. */
  max?: number;
}

const DEFAULT_MAX = 8;

export function PhotoPicker({ photos, onChange, max = DEFAULT_MAX }: PhotoPickerProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);

  async function pick(event: React.ChangeEvent<HTMLInputElement>): Promise<void> {
    const files = Array.from(event.target.files ?? []);
    // Сбрасываем сразу: иначе повторный выбор того же файла не вызовет событие.
    event.target.value = '';
    if (files.length === 0) {
      return;
    }

    setBusy(true);
    try {
      const room = Math.max(0, max - photos.length);
      const resized = await Promise.all(files.slice(0, room).map(resizePhoto));
      onChange([...photos, ...resized]);
    } finally {
      setBusy(false);
    }
  }

  const full = photos.length >= max;

  return (
    <div className="photos">
      <div className="photo-strip">
        {photos.map((photo, index) => (
          <Thumb
            key={index}
            photo={photo}
            onRemove={() => onChange(photos.filter((_, i) => i !== index))}
          />
        ))}
        <button
          type="button"
          className="photo-add"
          disabled={busy || full}
          onClick={() => inputRef.current?.click()}
        >
          {busy ? '…' : '+ фото'}
        </button>
      </div>

      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        // environment — задняя камера. Без этого откроется селфи-камера.
        capture="environment"
        multiple
        hidden
        onChange={(event) => {
          void pick(event);
        }}
      />
      {full && <span className="hint">Больше {max} снимков на деталь не нужно</span>}
    </div>
  );
}

/**
 * Миниатюра снимка.
 *
 * <p>Ссылка на blob освобождается при размонтировании: без этого каждая
 * фотография остаётся в памяти вкладки до перезагрузки, а за смену их сотни.
 */
function Thumb({ photo, onRemove }: { photo: ResizedPhoto; onRemove: () => void }) {
  const [url, setUrl] = useState<string>('');

  useEffect(() => {
    const objectUrl = URL.createObjectURL(photo.blob);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [photo.blob]);

  return (
    <div className="photo-thumb">
      {url !== '' && <img src={url} alt="" />}
      <button type="button" className="photo-remove" onClick={onRemove} aria-label="Убрать снимок">
        ×
      </button>
    </div>
  );
}
