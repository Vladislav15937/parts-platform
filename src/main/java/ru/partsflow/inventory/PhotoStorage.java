package ru.partsflow.inventory;

import org.springframework.stereotype.Component;
import ru.partsflow.platform.config.S3Properties;
import ru.partsflow.platform.tenant.TenantContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Доступ к хранилищу фотографий.
 *
 * <p><b>Телефон грузит файл сам, минуя приложение.</b> Снимок с камеры — это
 * несколько мегабайт, приёмщик делает их десятками, а связь в ангаре плохая.
 * Пропускать этот трафик через приложение значит держать его потоки занятыми
 * на минуты и упереться в лимиты загрузки на каждом прокси по пути. Приложение
 * выдаёт подписанную ссылку, телефон пишет прямо в хранилище.
 *
 * <p><b>Арендатор — в префиксе ключа.</b> Бакет один на ячейку, изоляция
 * префиксом: {@code t_000042/parts/123/uuid.jpg}. Так же, как со схемой в БД,
 * это даёт удаление и выгрузку одного клиента одной операцией по префиксу.
 */
@Component
public class PhotoStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties properties;

    public PhotoStorage(S3Client s3, S3Presigner presigner, S3Properties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    /**
     * Ключ нового объекта.
     *
     * <p>UUID, а не имя файла с телефона: имена приходят одинаковые
     * («IMG_0001.jpg»), в разной кодировке и с пробелами, и второй снимок
     * затрёт первый.
     */
    public String newKey(long partId, String contentType) {
        return "%s/parts/%d/%s%s".formatted(
                TenantContext.require(), partId, UUID.randomUUID(), extensionFor(contentType));
    }

    /** Подписанная ссылка на загрузку. Короткоживущая — см. {@link S3Properties}. */
    public String presignUpload(String key, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .build();

        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(properties.uploadUrlTtl())
                        .putObjectRequest(put)
                        .build())
                .url()
                .toString();
    }

    /** Подписанная ссылка на просмотр: бакет закрытый, публичных ссылок нет. */
    public String presignView(String key) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build();

        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(properties.viewUrlTtl())
                        .getObjectRequest(get)
                        .build())
                .url()
                .toString();
    }

    /**
     * Размер объекта, если он существует.
     *
     * <p>Нужен, чтобы не верить телефону на слово. Подтверждение загрузки
     * от клиента — это утверждение, а не факт: связь могла оборваться
     * на середине файла.
     */
    public Optional<Long> sizeOf(String key) {
        try {
            return Optional.of(s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build()).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic", "image/heif" -> ".heic";
            default -> ".jpg";
        };
    }
}
