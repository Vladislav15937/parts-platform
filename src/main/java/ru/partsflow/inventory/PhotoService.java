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

    /** Сколько знаков наименования оставляем в имени архива. */
    private static final int NAME_LIMIT = 60;

    /**
     * Кириллица в латиницу для имени файла.
     *
     * <p>Имя уходит в мессенджер и на чужие устройства, а там кодировку
     * имени файла в архиве и в заголовке ответа разбирают кто во что горазд:
     * «Фара левая.zip» приезжает то вопросами, то «????». Латиница читается
     * везде одинаково.
     */
    private static final java.util.Map<Character, String> TRANSLIT = java.util.Map.ofEntries(
            java.util.Map.entry('а', "a"), java.util.Map.entry('б', "b"),
            java.util.Map.entry('в', "v"), java.util.Map.entry('г', "g"),
            java.util.Map.entry('д', "d"), java.util.Map.entry('е', "e"),
            java.util.Map.entry('ё', "e"), java.util.Map.entry('ж', "zh"),
            java.util.Map.entry('з', "z"), java.util.Map.entry('и', "i"),
            java.util.Map.entry('й', "y"), java.util.Map.entry('к', "k"),
            java.util.Map.entry('л', "l"), java.util.Map.entry('м', "m"),
            java.util.Map.entry('н', "n"), java.util.Map.entry('о', "o"),
            java.util.Map.entry('п', "p"), java.util.Map.entry('р', "r"),
            java.util.Map.entry('с', "s"), java.util.Map.entry('т', "t"),
            java.util.Map.entry('у', "u"), java.util.Map.entry('ф', "f"),
            java.util.Map.entry('х', "h"), java.util.Map.entry('ц', "c"),
            java.util.Map.entry('ч', "ch"), java.util.Map.entry('ш', "sh"),
            java.util.Map.entry('щ', "sch"), java.util.Map.entry('ъ', ""),
            java.util.Map.entry('ы', "y"), java.util.Map.entry('ь', ""),
            java.util.Map.entry('э', "e"), java.util.Map.entry('ю', "yu"),
            java.util.Map.entry('я', "ya"));

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
     * Состав архива снимков карточки: как назвать файл и что в него класть.
     *
     * <p>Читает только базу и ничего не качает: в хранилище ходит
     * {@link #writeArchive}, уже вне транзакции. Транзакция, ждущая S3, —
     * это соединение из пула, удерживаемое на время чужого таймаута.
     *
     * <p>Главный снимок идёт первым — покупателю отправляют «сначала общий
     * вид», — а остальные в том же порядке, в каком стоят в полосе миниатюр
     * ({@code sort_order}), где главный как раз не первый.
     *
     * @throws IllegalStateException если подтверждённых снимков нет: пустой
     *         архив выглядит как удавшееся скачивание, после которого
     *         продавец ищет файлы в «Загрузках»
     */
    @Transactional(readOnly = true)
    public Archive archiveOf(Long partId) {
        Part part = parts.findById(partId).orElseThrow(
                () -> new IllegalArgumentException("Запчасть не найдена: " + partId));

        List<PartPhoto> confirmed = photos.findByPartIdOrderBySortOrderAscIdAsc(partId).stream()
                .filter(PartPhoto::isConfirmed)
                .toList();
        if (confirmed.isEmpty()) {
            throw new IllegalStateException("У этой позиции нет снимков — скачивать нечего");
        }

        List<PartPhoto> ordered = new java.util.ArrayList<>(confirmed.size());
        confirmed.stream().filter(PartPhoto::isMain).forEach(ordered::add);
        confirmed.stream().filter(photo -> !photo.isMain()).forEach(ordered::add);

        List<Entry> entries = new java.util.ArrayList<>(ordered.size());
        for (int at = 0; at < ordered.size(); at++) {
            String key = ordered.get(at).getS3Key();
            entries.add(new Entry("%02d%s".formatted(at + 1, extensionOf(key)), key));
        }
        return new Archive(archiveName(part.getPublicCode(), part.getTitle()), entries);
    }

    /**
     * Пишет архив в поток ответа.
     *
     * <p>Без транзакции намеренно: здесь только хранилище и сеть. И без
     * сборки в память — снимков у позиции девять, но действие может позвать
     * и робот, а каждый снимок это сотни килобайт.
     *
     * <p>Пропавший объект пропускается с записью в лог, а не роняет ответ:
     * заголовки уже отправлены, статус сменить нельзя, и оборванный поток
     * дал бы битый архив вместо архива без одного снимка. Случай редкий —
     * в состав идут только подтверждённые, у которых приложение файл
     * видело, — но снимок могли удалить, пока архив собирался.
     */
    public void writeArchive(Archive archive, java.io.OutputStream out) throws java.io.IOException {
        try (var zip = new java.util.zip.ZipOutputStream(out)) {
            // Снимки уже сжаты: дефлейт даёт те же байты и лишнюю работу
            // на каждое скачивание.
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            for (Entry entry : archive.entries()) {
                try (java.io.InputStream file = storage.open(entry.key())) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry.name()));
                    file.transferTo(zip);
                    zip.closeEntry();
                } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
                    log.warn("Архив снимков: объекта {} в хранилище нет, пропускаем",
                            entry.key());
                }
            }
        }
    }

    /**
     * Имя файла архива: публичный код и наименование.
     *
     * <p>Латиницей, потому что файл уходит в мессенджер и на чужие
     * устройства, а «Загрузки» — общая папка: код впереди отвечает
     * на вопрос «что это», даже когда наименование обрезано.
     */
    private static String archiveName(String code, String title) {
        String slug = slugify(title);
        return (slug.isEmpty() ? code : code + "-" + slug) + ".zip";
    }

    /** Кириллица в латиницу, всё прочее — в дефис. */
    private static String slugify(String title) {
        if (title == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : title.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            String piece = TRANSLIT.get(c);
            if (piece != null) {
                out.append(piece);
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        // Наименование бывает длиной в строку («Фара левая Toyota Camry
        // 2007 в сборе»), а имя файла читают одним взглядом.
        return out.length() > NAME_LIMIT ? out.substring(0, NAME_LIMIT) : out.toString();
    }

    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? ".jpg" : key.substring(dot);
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

    /** Что отдать одним файлом: имя архива и его состав по порядку. */
    public record Archive(String fileName, List<Entry> entries) {
    }

    /** Снимок внутри архива: как назван в нём и где лежит в хранилище. */
    public record Entry(String name, String key) {
    }
}
