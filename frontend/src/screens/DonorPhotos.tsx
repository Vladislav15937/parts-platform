import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  deleteDonorPhoto,
  loadDonorPhotos,
  makeMainDonorPhoto,
  uploadDonorPhoto,
  type DonorPhoto,
} from '../intake/donorPhotos';
import { useMounted } from '../ui/useMounted';

/**
 * Снимки машины-донора под её строкой.
 *
 * <p><b>Зачем они.</b> Для двигателя и коробки состояние машины — половина
 * объявления: покупатель смотрит, откуда снято, какой пробег, цел ли кузов.
 * Фотографии машины уезжают в объявления тех наименований, которые владелец
 * отметил у выгрузки, — и об этом здесь сказано словами: иначе загруженный
 * снимок выглядит как снимок, который видит покупатель, а пока наименование
 * не отмечено, его не видит никто.
 *
 * <p><b>Раскрывается под своей же строкой</b>, как и затраты: у клиента
 * 441 машина, и блок после таблицы открывался бы за одиннадцать экранов
 * вниз — владелец нажимал бы кнопку и не видел ничего, кроме сменившейся
 * надписи на ней.
 */
interface Props {
  donorId: number;
  title: string;
}

export function DonorPhotos({ donorId, title }: Props) {
  const [photos, setPhotos] = useState<DonorPhoto[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    void reload();
  }, [donorId]);

  return (
    <div>
      <h4>Фотографии машины: {title}</h4>
      <p className="note">
        Уезжают в объявления тех наименований, которые отмечены у выгрузки
        («Снимки машины-донора»). Пока наименование не отмечено, покупатель
        их не видит.
      </p>

      {message !== null && <p className="note note--error">{message}</p>}

      {/* Три состояния различимы: грузим, пусто и отказ. Пустой блок без слов
          читался бы как «снимков нет» и при неотвеченном сервере тоже. */}
      {photos === null && message === null && <p className="note">Загружаем…</p>}

      {photos !== null && photos.length === 0 && (
        <p className="note">Снимков машины нет. Добавьте — они пойдут в объявления.</p>
      )}

      {photos !== null && photos.length > 0 && (
        <div className="card-view__strip">
          {photos.map((photo) => (
            // Классы те же, что у полосы миниатюр карточки позиции: своих
            // заводить незачем — выглядеть это должно одинаково, а новый
            // класс «на один экран» в app.css живёт до первой правки палитры.
            <figure key={photo.photoId}>
              <img className="thumb" src={photo.url} alt="Снимок машины" />
              <figcaption className="filter-row">
                {photo.main
                  ? <span className="note">главный</span>
                  : (
                    <button
                      type="button"
                      className="button--ghost"
                      disabled={busy}
                      onClick={() => void setMain(photo.photoId)}
                    >
                      Сделать главным
                    </button>
                  )}
                <button
                  type="button"
                  className="button--ghost"
                  disabled={busy}
                  onClick={() => void remove(photo.photoId)}
                >
                  Удалить
                </button>
              </figcaption>
            </figure>
          ))}
        </div>
      )}

      <label className="card-view__add-photo">
        {busy ? 'Загружаем…' : 'Добавить фото машины'}
        <input
          type="file"
          accept="image/*"
          multiple
          disabled={busy}
          onChange={(e) => void add(e.target.files)}
        />
      </label>
    </div>
  );

  async function reload(): Promise<void> {
    try {
      const found = await loadDonorPhotos(donorId);
      if (mounted.current) {
        setPhotos(found);
        setMessage(null);
      }
    } catch (cause) {
      // «Не смогли узнать» — не то же, что «снимков нет»: пустая полоса
      // на отказе сервера читается как машина без фотографий.
      if (mounted.current) setMessage(describe(cause, 'Снимки машины не загрузились'));
    }
  }

  /**
   * Грузит по одному файлу, а не все разом.
   *
   * <p>Хранилище отвечает на каждый отдельно, и параллельная отправка десяти
   * файлов кончается отказом на половине — та же причина, по которой так же
   * устроена досъёмка позиции.
   */
  async function add(files: FileList | null): Promise<void> {
    if (files === null || files.length === 0) {
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      for (const file of Array.from(files)) {
        await uploadDonorPhoto(donorId, file);
      }
      await reload();
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Снимок не загрузился'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function setMain(photoId: number): Promise<void> {
    setBusy(true);
    try {
      await makeMainDonorPhoto(donorId, photoId);
      await reload();
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Главный снимок не сменился'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }

  async function remove(photoId: number): Promise<void> {
    setBusy(true);
    try {
      await deleteDonorPhoto(donorId, photoId);
      await reload();
    } catch (cause) {
      if (mounted.current) setMessage(describe(cause, 'Снимок не удалён'));
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
}

function describe(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    return cause.status === 0 ? 'Нет связи с сервером' : cause.message;
  }
  return fallback;
}
