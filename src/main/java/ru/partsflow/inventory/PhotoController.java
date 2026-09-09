package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST фотографий.
 *
 * <p><b>Файлы через этот контроллер не проходят.</b> Он выдаёт подписанную
 * ссылку, телефон пишет снимок прямо в хранилище и потом подтверждает загрузку.
 * Так приёмка с телефона работает на плохой связи: приложение не держит потоки
 * на многомегабайтных запросах, а повторить нужно только оборвавшийся файл,
 * а не весь запрос.
 */
@RestController
@RequestMapping("/api/parts/{partId}/photos")
public class PhotoController {

    private final PhotoService photos;

    public PhotoController(PhotoService photos) {
        this.photos = photos;
    }

    /** Шаг 1: получить ссылку на загрузку. */
    @PostMapping("/upload-url")
    public ResponseEntity<PhotoService.Upload> requestUpload(
            @PathVariable Long partId,
            @Valid @RequestBody UploadRequest request) {

        PhotoService.Upload upload;
        try {
            upload = photos.requestUpload(partId, request.contentType(), request.requestId());
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // Одновременный повтор: первый запрос успел вставить снимок,
            // второй упёрся в уникальный ключ. Телефон ждёт ссылку —
            // отдаём ту же, что и первому.
            upload = photos.replayAfterConflict(request.requestId(), request.contentType());
            if (upload == null) {
                throw conflict;
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(upload);
    }

    /**
     * Шаг 2: подтвердить, что файл загружен.
     *
     * <p>Приложение проверяет хранилище само. {@code 409} означает, что объекта
     * там нет — надо повторить загрузку по новой ссылке.
     *
     * <p><b>Отказ объясняется словами.</b> Пока снимки грузил только телефон,
     * хватало кода ответа: очередь разбирает его сама. С появлением досъёмки
     * из карточки этот отказ увидел человек — и увидел «Запрос отклонён
     * (409)», по которому не понять ни что случилось, ни что делать.
     * Классификация при этом та же: 409 остаётся 409, меняется только тело.
     */
    @PostMapping("/{photoId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable Long partId,
                                        @PathVariable Long photoId,
                                        @RequestBody(required = false) ConfirmRequest request) {

        boolean ok = photos.confirmUpload(photoId,
                request == null ? null : request.width(),
                request == null ? null : request.height());

        if (!ok) {
            throw new IllegalStateException(
                    "Снимок не найден в хранилище: загрузка оборвалась. Повторите её");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<PhotoService.PhotoView> list(@PathVariable Long partId) {
        return photos.of(partId);
    }

    /**
     * Все снимки позиции одним архивом.
     *
     * <p><b>Единственное место, где файл идёт через приложение</b>, и это
     * не отступление от правила класса, а его следствие. Разговор продавца
     * с покупателем кончается «скиньте фото», снимков у позиции шесть-девять,
     * а ссылки на просмотр подписанные и короткоживущие: сохранять их
     * приходилось по одной и сразу, девять раз, на глазах у ждущего
     * на линии. Собрать архив в браузере нельзя по той же причине, по
     * которой снимки грузятся по одному: девять параллельных скачиваний
     * с телефона по мобильной связи кончаются отказом на половине.
     *
     * <p>Роль не проверяется — как и у просмотра снимков выше: в архиве
     * ровно те же фотографии, которые открывший карточку и так видит.
     * Это не выгрузка таблицы склада, где право унести содержимое файлом
     * отделено от права смотреть на экран.
     *
     * <p><b>Состав читается до {@code getOutputStream()}.</b> Отдав первый
     * байт, статус ответа уже не сменить: «снимков нет» уехало бы двухсоткой
     * с пустым архивом внутри, а пустой архив продавец читает как удавшееся
     * скачивание и идёт искать файлы в «Загрузках».
     */
    @GetMapping("/archive")
    public void archive(@PathVariable Long partId,
                        jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {

        PhotoService.Archive archive = photos.archiveOf(partId);

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + archive.fileName() + "\"");

        photos.writeArchive(archive, response.getOutputStream());
    }

    @PostMapping("/{photoId}/main")
    public ResponseEntity<Void> makeMain(@PathVariable Long partId, @PathVariable Long photoId) {
        photos.makeMain(photoId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> delete(@PathVariable Long partId, @PathVariable Long photoId) {
        photos.delete(photoId);
        return ResponseEntity.noContent().build();
    }

    public record UploadRequest(@NotBlank String contentType,
                                /* Ключ запроса: повтор не создаёт вторую запись
                                   и мусор в хранилище. */
                                @NotBlank String requestId) {
    }

    public record ConfirmRequest(Integer width, Integer height) {
    }
}
