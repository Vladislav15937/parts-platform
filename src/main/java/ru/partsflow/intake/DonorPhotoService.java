package ru.partsflow.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.PhotoStorage;
import ru.partsflow.shared.NotFound;

import java.util.List;

/**
 * Фотографии машины-донора.
 *
 * <p><b>Зачем они есть.</b> Для двигателя и коробки состояние машины —
 * половина объявления: покупатель смотрит, откуда снято, какой пробег,
 * цел ли кузов. Снимок самой детали этого не показывает, а фотографировать
 * машину заново на каждую снятую с неё запчасть никто не станет: с одного
 * донора их выходит до полутора сотен. Поэтому машина снимается один раз,
 * а в объявление её снимки попадают тем позициям, чьё наименование владелец
 * отметил у выгрузки ({@code FeedSettings.donorPhotoKinds}).
 *
 * <p><b>Путь тот же, что у снимков детали</b>
 * ({@code ru.partsflow.inventory.PhotoService}): ссылка, загрузка прямо
 * в хранилище, подтверждение с проверкой объекта. Второй способ грузить
 * снимки разошёлся бы с первым на первой же правке, а подтверждение
 * от клиента — утверждение, а не факт: связь обрывается на середине файла,
 * и в объявление уехала бы ссылка в никуда, за которую площадка снимает
 * объявление.
 *
 * <p><b>Отметки об изменении позиции здесь нет намеренно, и это не забытый
 * вызов.</b> Дельта на площадку уходит <b>без снимков вовсе</b> — она
 * сообщает, что позиция продана или подешевела, а фотографии у площадки уже
 * есть ({@code DromPriceGenerator.writeDelta}). Значит отметить сто сорок
 * позиций донора при загрузке одного снимка означало бы сто сорок дельт,
 * ни одна из которых новой фотографии не несёт. Снимок машины доезжает
 * до объявления полным прайсом — тем же путём, которым до него доезжает
 * и новая позиция.
 */
@Service
public class DonorPhotoService {

    private static final Logger log = LoggerFactory.getLogger(DonorPhotoService.class);

    private final DonorPhotoRepository photos;
    private final DonorRepository donors;
    private final PhotoStorage storage;

    public DonorPhotoService(DonorPhotoRepository photos, DonorRepository donors,
                             PhotoStorage storage) {
        this.photos = photos;
        this.donors = donors;
        this.storage = storage;
    }

    /**
     * Повтор, случившийся одновременно с первым запросом.
     *
     * <p>Та же половинчатая защита, что была у приёмки и у снимков детали:
     * проверку «нет ли уже такого» два одновременных повтора проходят оба,
     * дубль отбивает уникальный индекс, а наружу летит 409 — ошибка
     * на успешный запрос. Браузер при этом ждёт ссылку, чтобы залить снимок.
     *
     * <p>Читается новой транзакцией: та, в которой случилось нарушение,
     * помечена на откат.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Upload replayAfterConflict(String requestId, String contentType) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return photos.findByClientRequestId(requestId)
                .map(existing -> new Upload(existing.getId(), existing.getS3Key(),
                        storage.presignUpload(existing.getS3Key(), contentType)))
                .orElse(null);
    }

    /**
     * Заводит фотографию машины и выдаёт ссылку на загрузку.
     *
     * <p>Первая фотография машины становится главной сама — она уйдёт
     * в объявление первой из донорских; заставлять владельца отмечать её
     * руками значит получить машины, у которых главной нет вовсе.
     */
    @Transactional
    public Upload requestUpload(Long donorId, String contentType, String requestId) {
        requireDonor(donorId);

        // Тип сверяется здесь по той же причине, что и у снимков детали:
        // байтов у приложения нет вовсе — файл идёт в хранилище мимо него, —
        // поэтому проверяется ЗАЯВЛЕННЫЙ тип, а содержимое смотрит клиент
        // до отправки. Иначе PDF, переименованный в .jpg, уедет в объявление
        // под видом снимка, а за битую картинку площадка снимает объявление.
        // Список принимаемых типов один на проект, в PhotoStorage.
        if (!PhotoStorage.isSupportedImage(contentType)) {
            throw new IllegalArgumentException(PhotoStorage.refusalFor(contentType));
        }

        // Повтор: отдаём ту же фотографию с новой ссылкой. Ссылка обновляется
        // намеренно — прежняя за время ожидания истекла.
        DonorPhoto existing = requestId == null || requestId.isBlank()
                ? null
                : photos.findByClientRequestId(requestId).orElse(null);
        if (existing != null) {
            return new Upload(existing.getId(), existing.getS3Key(),
                    storage.presignUpload(existing.getS3Key(), contentType));
        }

        String key = storage.newDonorKey(donorId, contentType);
        DonorPhoto photo = new DonorPhoto(donorId, key);
        photo.setClientRequestId(requestId);
        if (photos.findByDonorIdAndMainIsTrue(donorId).isEmpty()) {
            photo.makeMain();
        }
        photo.moveTo(nextSortOrder(donorId));

        DonorPhoto saved = photos.saveAndFlush(photo);
        return new Upload(saved.getId(), key, storage.presignUpload(key, contentType));
    }

    /**
     * Подтверждает загрузку, сверившись с хранилищем.
     *
     * @return {@code false}, если файла в хранилище нет — фотография помечена
     *         неудачной и ни в карточке машины, ни в объявлении не покажется
     */
    @Transactional
    public boolean confirmUpload(Long photoId, Integer width, Integer height) {
        DonorPhoto photo = requirePhoto(photoId);

        var size = storage.sizeOf(photo.getS3Key());
        if (size.isEmpty()) {
            photo.markFailed();
            photos.saveAndFlush(photo);
            log.warn("Снимок машины {}: подтверждение пришло, но объекта {} в хранилище нет",
                    photoId, photo.getS3Key());
            return false;
        }

        photo.confirm(size.get(), width, height);
        photos.saveAndFlush(photo);
        return true;
    }

    /**
     * Делает фотографию главной, снимая признак с прежней.
     *
     * <p>Порядок важен: в БД частичный уникальный индекс «одна главная
     * на машину», и установка новой до снятия старой упадёт на нём.
     */
    @Transactional
    public void makeMain(Long photoId) {
        DonorPhoto photo = requirePhoto(photoId);
        if (!photo.isConfirmed()) {
            throw new IllegalStateException(
                    "Главной можно сделать только загруженную фотографию, а эта в состоянии "
                            + photo.getStatus());
        }

        photos.findByDonorIdAndMainIsTrue(photo.getDonorId()).ifPresent(current -> {
            if (!current.getId().equals(photoId)) {
                current.unmakeMain();
                photos.saveAndFlush(current);
            }
        });
        photo.makeMain();
        photos.saveAndFlush(photo);
    }

    /**
     * Удаляет фотографию машины и из хранилища тоже.
     *
     * <p>Если удалили главную, главной становится следующая: порядок снимков
     * машины в объявлении начинается с неё.
     */
    @Transactional
    public void delete(Long photoId) {
        DonorPhoto photo = requirePhoto(photoId);
        boolean wasMain = photo.isMain();
        Long donorId = photo.getDonorId();

        photos.delete(photo);
        photos.flush();
        storage.delete(photo.getS3Key());

        if (wasMain) {
            photos.findByDonorIdOrderBySortOrderAscIdAsc(donorId).stream()
                    .filter(DonorPhoto::isConfirmed)
                    .findFirst()
                    .ifPresent(next -> {
                        next.makeMain();
                        photos.saveAndFlush(next);
                    });
        }
    }

    /** Снимки машины со ссылками на просмотр, главный первым. */
    @Transactional(readOnly = true)
    public List<PhotoView> of(Long donorId) {
        List<DonorPhoto> confirmed = photos.findByDonorIdOrderBySortOrderAscIdAsc(donorId).stream()
                .filter(DonorPhoto::isConfirmed)
                .toList();

        // Главный первым — тем же порядком, каким снимки машины уезжают
        // в объявление. Полоса миниатюр у детали идёт по sort_order, и там
        // это верно (прыжок на нулевой показывал бы чужую фотографию),
        // а здесь владелец смотрит ровно то, что увидит покупатель.
        List<DonorPhoto> ordered = new java.util.ArrayList<>(confirmed.size());
        confirmed.stream().filter(DonorPhoto::isMain).forEach(ordered::add);
        confirmed.stream().filter(photo -> !photo.isMain()).forEach(ordered::add);

        return ordered.stream()
                .map(photo -> new PhotoView(photo.getId(), photo.isMain(),
                        storage.presignView(photo.getS3Key())))
                .toList();
    }

    private int nextSortOrder(Long donorId) {
        return photos.findByDonorIdOrderBySortOrderAscIdAsc(donorId).stream()
                .mapToInt(DonorPhoto::getSortOrder)
                .max()
                .orElse(-1) + 1;
    }

    private Donor requireDonor(Long donorId) {
        return donors.findById(donorId).orElseThrow(() -> NotFound.DONOR.error(donorId));
    }

    private DonorPhoto requirePhoto(Long photoId) {
        return photos.findById(photoId).orElseThrow(() -> NotFound.PHOTO.error(photoId));
    }

    /** Ссылка на загрузку: запись уже есть, файла ещё нет. */
    public record Upload(Long photoId, String key, String uploadUrl) {
    }

    /** Снимок машины для экрана: ссылка подписанная и короткоживущая. */
    public record PhotoView(Long photoId, boolean main, String url) {
    }
}
