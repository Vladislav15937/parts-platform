package ru.partsflow.platform.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Клиенты S3.
 *
 * <p>Их два, и это не дублирование. {@link S3Client} ходит по внутреннему
 * адресу — им приложение проверяет, что файл действительно загрузился.
 * {@link S3Presigner} подписывает ссылки внешним адресом: подпись включает
 * хост, и ссылка, подписанная внутренним именем, снаружи не откроется.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        // MinIO не умеет виртуальные хосты вида bucket.host.
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(S3Properties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.externalEndpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build())
                .build();
    }

    private static StaticCredentialsProvider credentials(S3Properties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    }
}
