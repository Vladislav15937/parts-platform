package ru.partsflow.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Бакет для фотографий заводится при старте ячейки.
 *
 * <p><b>Свежая ячейка без этого не принимает ни одного снимка.</b> Приложение
 * выдаёт подписанную ссылку, телефон пишет по ней файл — и получает от
 * хранилища «The specified bucket does not exist». Приёмщику это видно как
 * фотография, которая не грузится, а в логах приложения нет ничего: файл
 * идёт мимо него намеренно. Поймано живым прогоном переноса фотографий
 * на чистой ячейке; до того бакет заводил руками тот, кто про него знал,
 * а в документации о нём не было ни слова.
 *
 * <p>Здесь же, а не в провижининге арендатора: бакет один на ячейку,
 * арендаторы разделены префиксом ключа. Это инфраструктура ячейки, как
 * и общая схема {@code catalog}, — и заводится она там же, при подъёме.
 *
 * <p><b>Недоступное хранилище валит запуск намеренно.</b> Ячейка, в которой
 * нельзя сохранить фотографию, неработоспособна; молча подняться и выяснить
 * это первым снимком приёмщика — хуже. Гонку с ещё не поднявшимся MinIO
 * закрывает политика перезапуска контейнера.
 */
@Component
public class S3BucketInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(S3BucketInitializer.class);

    private final S3Client s3;
    private final S3Properties properties;
    private final boolean enabled;

    public S3BucketInitializer(S3Client s3, S3Properties properties,
                               @Value("${app.s3.ensure-bucket:true}") boolean enabled) {
        this.s3 = s3;
        this.properties = properties;
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            // Выключается только там, где хранилища нет вовсе, — в тестах.
            // См. src/test/resources/application.properties.
            return;
        }
        String bucket = properties.bucket();
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Бакет фотографий на месте: {}", bucket);
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Бакет фотографий создан: {}", bucket);
        } catch (S3Exception e) {
            // 404 приходит и просто S3Exception, без разбора на подтип:
            // MinIO отвечает на HEAD пустым телом, и SDK не из чего собрать
            // NoSuchBucketException. Поймано живым прогоном.
            if (e.statusCode() == 404) {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Бакет фотографий создан: {}", bucket);
            } else {
                throw new IllegalStateException(
                        "Хранилище недоступно, ячейка не примет ни одной фотографии: "
                                + e.getMessage(), e);
            }
        }
    }
}
