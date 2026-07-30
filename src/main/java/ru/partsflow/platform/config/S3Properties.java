package ru.partsflow.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки хранилища фотографий.
 *
 * <p>{@code endpoint} задаётся явно: в разработке это MinIO, у клиентов —
 * обычный S3, протокол один. {@code pathStyleAccess} нужен MinIO, потому что
 * виртуальные хосты вида {@code bucket.localhost} не резолвятся.
 *
 * <p><b>Про {@code publicEndpoint}.</b> Приложение обращается к хранилищу
 * по внутреннему адресу, а ссылку на загрузку получает телефон приёмщика —
 * ему нужен адрес, доступный извне. Один и тот же URL для обоих случаев
 * работает только на localhost, поэтому адреса разведены.
 */
@ConfigurationProperties(prefix = "app.s3")
public record S3Properties(
        String endpoint,
        String publicEndpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        boolean pathStyleAccess,
        /* Срок ссылки на загрузку. Короткий: приёмщик снимает и грузит сразу,
           а утёкшая долгоживущая ссылка — это право писать в чужое хранилище. */
        Duration uploadUrlTtl,
        /* Срок ссылки на просмотр. Длиннее: карточку открывают и листают. */
        Duration viewUrlTtl) {

    public S3Properties {
        region = region == null || region.isBlank() ? "us-east-1" : region;
        uploadUrlTtl = uploadUrlTtl == null ? Duration.ofMinutes(15) : uploadUrlTtl;
        viewUrlTtl = viewUrlTtl == null ? Duration.ofHours(6) : viewUrlTtl;
    }

    /** Адрес для ссылок, отдаваемых наружу. */
    public String externalEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    }
}
