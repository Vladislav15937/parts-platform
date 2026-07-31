import { useEffect, useState } from 'react';
import { COLUMNS, loadPhotos, type CatalogRow, type PartPhoto } from '../inventory/catalog';

/**
 * Карточка позиции: всё, что о ней известно, и снимки.
 *
 * <p>Открывается нажатием на строку склада — так в кабинете, на который
 * клиент смотрит каждый день. Данные берутся из уже загруженной строки:
 * в ней лежат все двадцать с лишним колонок, включая скрытые в таблице,
 * и запрашивать их второй раз незачем.
 *
 * <p><b>Пустые поля не показываются.</b> Двадцать строк, из которых
 * заполнены шесть, — это шесть строк, потерянных среди прочерков.
 */
export function PartCard({ row, onClose }: { row: CatalogRow; onClose: () => void }) {
  const [photos, setPhotos] = useState<PartPhoto[]>([]);
  const [shown, setShown] = useState(0);

  useEffect(() => {
    void loadPhotos(row.id).then(setPhotos).catch(() => setPhotos([]));
  }, [row.id]);

  // Закрытие по Escape: карточку открывают десятками, и тянуться мышью
  // к крестику каждый раз — лишнее движение.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const fields = COLUMNS
    .filter((c) => c.image === undefined && c.key !== 'title')
    .map((c) => ({ title: c.title, value: c.value(row) }))
    .filter((f) => f.value !== '');

  const main = photos[shown];

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="card-view" onClick={(event) => event.stopPropagation()}>
        <header className="card-view__head">
          <h2>{row.title}</h2>
          <button type="button" className="button--ghost" onClick={onClose}>
            Закрыть
          </button>
        </header>

        <div className="card-view__body">
          <dl className="card-view__fields">
            {fields.map((field) => (
              <div key={field.title}>
                <dt>{field.title}</dt>
                <dd>{field.value}</dd>
              </div>
            ))}
          </dl>

          <div className="card-view__photos">
            {main === undefined ? (
              <p className="muted">Снимков нет</p>
            ) : (
              <>
                <img className="card-view__main" src={main.url} alt="" />
                {photos.length > 1 && (
                  <div className="card-view__strip">
                    {photos.map((photo, i) => (
                      <button
                        key={photo.photoId}
                        type="button"
                        className={i === shown ? 'thumb-button is-shown' : 'thumb-button'}
                        onClick={() => setShown(i)}
                      >
                        <img className="thumb" src={photo.url} alt="" />
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
