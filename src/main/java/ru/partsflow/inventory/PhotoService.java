package ru.partsflow.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Фотографии запчастей.
 *
 * <p>Загрузка в два шага, потому что файл идёт мимо приложения:
 * <ol>
 *   <li>{@link #requestUpload} — заводит запись и отдаёт подписанную ссылку;
 *   <li>телефон пишет файл прямо в хранилище;
 *   <li>{@link #confirmUpload} — приложение <b>проверяет</b>, что объект
 *       действительно на месте, и только тогда считает фотографию готовой.
 * </ol>
 *
 * <p>Третий шаг не формальность. Подтверждение от клиента — утверждение,
 * а не факт: связь в ангаре обрывается на середине файла, и без проверки
 * в карточке появится битая картинка, а в фид уедет ссылка в никуда —
 * за такое площадка снимает объявление.
 */
@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    /** Сколько ждём подтверждения, прежде чем счесть загрузку оборванной. */
    private static final Duration ABANDONED_AFTER = Duration.ofHours(1);

    private final PartPhotoRepository photos;
    private final PartRepository parts;
    private final PhotoStorage storage;

    private final PartChangeLog partChanges;

    public PhotoService(PartPhotoRepository photos, PartRepository parts, PhotoStorage storage,
                        PartChangeLog partChanges) {
        this.photos = photos;
        this.parts = parts;
        this.storage = storage;
        this.partChanges = partChanges;
    }

    /**
     * Заводит фотографию и выдаёт ссылку на загрузку.
     *
     * <p>Первая фотография детали становится главной сама: заставлять приёмщика
     * отмечать её вручную значит получить склад, где у половины позиций главной
     * нет, а в списке и на площадке показывается случайная.
     */
    /**
     * Повтор, случившийся одновременно с первым запросом.
     *
     * <p>Та же половинчатая защита, что была у приёмки: проверку «нет ли уже
     * такого» два одновременных повтора проходят оба, дубль отбивает
     * уникальный индекс, а наружу летит 409 — ошибка на успешный запрос.
     * Телефон при этом ждёт ссылку, чтобы залить снимок, и вместо неё
     * получает отказ.
     *
     * <p>Читается новой транзакцией: та, в которой случилось нарушение,
     * помечена на откат.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
                   readOnly = true)
    public Upload replayAfterConflict(String requestId, String contentType) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return photos.findByClientRequestId(requestId)
                .map(existing -> new Upload(existing.getId(), existing.getS3Key(),
                        storage.presignUpload(existing.getS3Key(), contentType)))
                .orElse(null);
    }

    @Transactional
    public Upload requestUpload(Long partId, String contentType, String requestId) {
        requirePart(partId);

        // Повтор из офлайн-очереди: отдаём ту же фотографию с новой ссылкой.
        // Ссылка обновляется намеренно — прежняя за время ожидания истекла.
        PartPhoto existing = requestId == null || requestId.isBlank()
                ? null
                : photos.findByClientRequestId(requestId).orElse(null);
        if (existing != null) {
            return new Upload(existing.getId(), existing.getS3Key(),
                    storage.presignUpload(existing.getS3Key(), contentType));
        }

        String key = storage.newKey(partId, contentType);
        PartPhoto photo = new PartPhoto(partId, key);
        photo.setClientRequestId(requestId);
        if (photos.findByPartIdAndMainIsTrue(partId).isEmpty()) {
            photo.makeMain();
        }
        photo.moveTo(nextSortOrder(partId));

        PartPhoto saved = photos.saveAndFlush(photo);
        return new Upload(saved.getId(), key, storage.presignUpload(key, contentType));
    }

    /**
     * Подтверждает загрузку, сверившись с хранилищем.
     *
     * @return {@code false}, если файла в хранилище нет — фотография помечена
     *         неудачной и в карточке не покажется
     */
    @Transactional
    public boolean confirmUpload(Long photoId, Integer width, Integer height) {
        PartPhoto photo = requirePhoto(photoId);

        var size = storage.sizeOf(photo.getS3Key());
        if (size.isEmpty()) {
            photo.markFailed();
            photos.saveAndFlush(photo);
            log.warn("Фото {}: подтверждение пришло, но объекта {} в хранилище нет",
                    photoId, photo.getS3Key());
            return false;
        }

        photo.confirm(size.get(), width, height);
        photos.saveAndFlush(photo);
        // Ссылки на снимки едут в прайс, и площадка сама говорит, что
        // фотография увеличивает просмотры в четыре-пять раз: появившаяся
        // не должна ждать суток.
        partChanges.changed(photo.getPartId());
        return true;
    }

    /**
     * Делает фотографию главной, снимая признак с прежней.
     *
     * <p>Порядок важен: в БД частичный уникальный индекс «одна главная
     * на деталь», и установка новой до снятия старой упадёт на нём.
     */
    @Transactional
    public void makeMain(Long photoId) {
        PartPhoto photo = requirePhoto(photoId);
        if (!photo.isConfirmed()) {
            throw new IllegalStateException(
                    "Главной можно сделать только загруженную фотографию, а эта в состоянии "
                            + photo.getStatus());
        }

        photos.findByPartIdAndMainIsTrue(photo.getPartId()).ifPresent(current -> {
            if (!current.getId().equals(photoId)) {
                current.unmakeMain();
                photos.saveAndFlush(current);
            }
        });
        photo.makeMain();
        photos.saveAndFlush(photo);
        // Главный снимок идёт в прайсе первым — площадка ставит его обложкой.
        partChanges.changed(photo.getPartId());
    }

    /**
     * Удаляет фотографию из карточки и из хранилища.
     *
     * <p>Если удалили главную, главной становится следующая: карточка без
     * главной фотографии показывается на площадке пустой.
     */
    @Transactional
    public void delete(Long photoId) {
        PartPhoto photo = requirePhoto(photoId);
        boolean wasMain = photo.isMain();
        Long partId = photo.getPartId();

        photos.delete(photo);
        photos.flush();
        storage.delete(photo.getS3Key());

        if (wasMain) {
            photos.findByPartIdOrderBySortOrderAscIdAsc(partId).stream()
                    .filter(PartPhoto::isConfirmed)
                    .findFirst()
                    .ifPresent(next -> {
                        next.makeMain();
                        photos.saveAndFlush(next);
                    });
        }
        // Ссылка на удалённый снимок в прайсе — битая картинка, а за неё
        // площадка снимает объявление.
        partChanges.changed(partId);
    }

    /** Фотографии карточки со ссылками на просмотр. */
    @Transactional(readOnly = true)
    public List<PhotoView> of(Long partId) {
        return photos.findByPartIdOrderBySortOrderAscIdAsc(partId).stream()
                .filter(PartPhoto::isConfirmed)
                .map(photo -> new PhotoView(photo.getId(), photo.isMain(),
                        storage.presignView(photo.getS3Key())))
                .toList();
    }

    /**
     * Чистит оборванные загрузки: ссылку выдали, подтверждения не было.
     *
     * @return сколько записей убрано
     */
    @Transactional
    public int cleanUpAbandoned() {
        List<PartPhoto> abandoned = photos.findByStatusAndCreatedAtBefore(
                PartPhoto.PhotoStatus.UPLOADED, Instant.now().minus(ABANDONED_AFTER));

        for (PartPhoto photo : abandoned) {
            // Файл может лежать наполовину загруженным — убираем и его.
            storage.delete(photo.getS3Key());
            photos.delete(photo);
        }
        photos.flush();
        return abandoned.size();
    }

    private int nextSortOrder(Long partId) {
        return photos.findByPartIdOrderBySortOrderAscIdAsc(partId).stream()
                .mapToInt(PartPhoto::getSortOrder)
                .max()
                .orElse(-1) + 1;
    }

    private void requirePart(Long partId) {
        if (!parts.existsById(partId)) {
            throw new IllegalArgumentException("Запчасть не найдена: " + partId);
        }
    }

    private PartPhoto requirePhoto(Long photoId) {
        return photos.findById(photoId).orElseThrow(
                () -> new IllegalArgumentException("Фотография не найдена: " + photoId));
    }

    /** Ссылка на загрузку для телефона. */
    public record Upload(Long photoId, String key, String uploadUrl) {
    }

    /** Фотография для карточки: ссылка подписана и живёт ограниченное время. */
    public record PhotoView(Long photoId, boolean main, String url) {
    }
}
