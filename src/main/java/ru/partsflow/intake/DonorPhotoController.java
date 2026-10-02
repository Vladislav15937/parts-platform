package ru.partsflow.intake;

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
 * REST снимков машины-донора.
 *
 * <p><b>Файлы через этот контроллер не проходят.</b> Он выдаёт подписанную
 * ссылку, браузер пишет снимок прямо в хранилище и потом подтверждает
 * загрузку — тот же путь, что у снимков детали, и по той же причине:
 * многомегабайтный файл, идущий через приложение, занимает его потоки,
 * а повторять после обрыва приходится весь запрос вместо одного файла.
 *
 * <p>Роли отдельно не перечислены: писать по {@code /api/**} разрешено
 * владельцу, менеджеру, кладовщику и продавцу ({@code SecurityConfig}),
 * а машину заводят те же люди, что её и фотографируют. Чтение открыто
 * всякому вошедшему, как и снимки детали: в объявлении эти фотографии
 * увидит любой покупатель.
 */
@RestController
@RequestMapping("/api/intake/donors/{donorId}/photos")
public class DonorPhotoController {

    private final DonorPhotoService photos;

    public DonorPhotoController(DonorPhotoService photos) {
        this.photos = photos;
    }

    /** Шаг 1: получить ссылку на загрузку. */
    @PostMapping("/upload-url")
    public ResponseEntity<DonorPhotoService.Upload> requestUpload(
            @PathVariable Long donorId,
            @Valid @RequestBody UploadRequest request) {

        DonorPhotoService.Upload upload;
        try {
            upload = photos.requestUpload(donorId, request.contentType(), request.requestId());
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            // Одновременный повтор: первый запрос успел вставить снимок,
            // второй упёрся в уникальный ключ. Браузер ждёт ссылку —
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
     * <p>Приложение проверяет хранилище само, а не верит клиенту на слово.
     * Отказ объясняется словами: этот путь зовёт человек из кабинета,
     * и «Запрос отклонён (409)» не говорит ему ни что случилось, ни что
     * делать.
     */
    @PostMapping("/{photoId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable Long donorId,
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
    public List<DonorPhotoService.PhotoView> list(@PathVariable Long donorId) {
        return photos.of(donorId);
    }

    @PostMapping("/{photoId}/main")
    public ResponseEntity<Void> makeMain(@PathVariable Long donorId, @PathVariable Long photoId) {
        photos.makeMain(photoId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> delete(@PathVariable Long donorId, @PathVariable Long photoId) {
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
