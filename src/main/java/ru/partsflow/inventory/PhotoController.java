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

        PhotoService.Upload upload = photos.requestUpload(partId, request.contentType());
        return ResponseEntity.status(HttpStatus.CREATED).body(upload);
    }

    /**
     * Шаг 2: подтвердить, что файл загружен.
     *
     * <p>Приложение проверяет хранилище само. {@code 409} означает, что объекта
     * там нет — телефону надо повторить загрузку по новой ссылке.
     */
    @PostMapping("/{photoId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable Long partId,
                                        @PathVariable Long photoId,
                                        @RequestBody(required = false) ConfirmRequest request) {

        boolean ok = photos.confirmUpload(photoId,
                request == null ? null : request.width(),
                request == null ? null : request.height());

        return ok ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @GetMapping
    public List<PhotoService.PhotoView> list(@PathVariable Long partId) {
        return photos.of(partId);
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

    public record UploadRequest(@NotBlank String contentType) {
    }

    public record ConfirmRequest(Integer width, Integer height) {
    }
}
