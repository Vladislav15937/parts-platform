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

    /**
     * Ключ снимка машины-донора.
     *
     * <p>Свой префикс {@code donors/}, а не {@code parts/}: снимок принадлежит
     * машине, а не позиции — в объявление он попадает сразу многим деталям,
     * снятым с неё. Положить его под {@code parts/} значило бы приписать
     * фотографию машины одной случайной запчасти, и удаление той позиции
     * унесло бы снимок у остальных ста сорока.
     *
     * <p>Арендатор в префиксе тот же и по той же причине: удаление и выгрузка
     * одного клиента остаются одной операцией по префиксу.
     */
    public String newDonorKey(long donorId, String contentType) {
        return "%s/donors/%d/%s%s".formatted(
                TenantContext.require(), donorId, UUID.randomUUID(), extensionFor(contentType));
    }

    /**
     * Кладёт файл в хранилище напрямую.
     *
     * <p>В обычной работе снимки идут мимо приложения — телефон пишет их
     * по подписанной ссылке. Здесь наоборот: фотографии переезжают с чужого
     * CDN, телефона в этой цепочке нет вовсе, и подписывать ссылку самому
     * себе означало бы лишний оборот.
     */
    public void put(String key, byte[] body, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(body));
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
     * Читает объект из хранилища прямо в приложение.
     *
     * <p>Единственное место, где файл идёт <b>через</b> приложение, и это
     * не отступление от правила выше, а его цена: архив снимков карточки
     * собирает сервер, потому что ссылки на просмотр подписанные
     * и короткоживущие, а девять параллельных скачиваний с телефона
     * по мобильной связи кончаются отказом на половине — та же причина,
     * по которой загрузка идёт по одному файлу.
     *
     * <p>Внутренним клиентом, а не подписанной ссылкой: подписывать ссылку
     * самому себе означало бы лишний оборот, как и при переносе снимков
     * с чужого CDN. Поток отдаётся как есть — вызывающий переливает его
     * в ответ и закрывает.
     */
    public java.io.InputStream open(String key) {
        return s3.getObject(GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
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

    /**
     * Типы снимков, которые система принимает: тип, расширение в хранилище
     * и слово для человека.
     *
     * <p><b>Список один, и читают его трое.</b> {@link #extensionFor} называет
     * по нему объект в хранилище, {@link #isSupportedImage} отвечает, принимать
     * ли заявленный тип, {@link #supportedImageLabels} называет форматы
     * в отказе. Второй список развёлся бы с первым на первом же новом
     * формате — в этом проекте так уже расходились белые списки колонок
     * и словари состояний сделки.
     *
     * <p><b>HEIC здесь намеренно.</b> Айфон снимает им, и снимок от поставщика
     * приходит именно таким — при том что уменьшить его умеет не всякий
     * браузер. «Не удалось раскодировать» и «это не картинка» — разные вещи,
     * и путать их значит отвергать законную фотографию.
     */
    private record ImageType(String contentType, String extension, String label) {
    }

    private static final java.util.List<ImageType> IMAGE_TYPES = java.util.List.of(
            new ImageType("image/jpeg", ".jpg", "JPEG"),
            // Нестандартный, но встречается у старых камер и конвертеров.
            new ImageType("image/jpg", ".jpg", "JPEG"),
            new ImageType("image/png", ".png", "PNG"),
            new ImageType("image/webp", ".webp", "WebP"),
            new ImageType("image/heic", ".heic", "HEIC"),
            new ImageType("image/heif", ".heic", "HEIC"));

    /** Заявленный тип — из списка принимаемых? */
    public static boolean isSupportedImage(String contentType) {
        return contentType != null && IMAGE_TYPES.stream()
                .anyMatch(type -> type.contentType().equals(contentType.trim().toLowerCase()));
    }

    /** Форматы словами, для отказа человеку: «JPEG, PNG, WebP, HEIC». */
    public static String supportedImageLabels() {
        return IMAGE_TYPES.stream().map(ImageType::label).distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Принимаемые типы — для проверки, сверяющей этот список с клиентским. */
    public static java.util.List<String> supportedImageTypes() {
        return IMAGE_TYPES.stream().map(ImageType::contentType).toList();
    }

    /**
     * Чем оказался приложенный файл — словами, которыми это назвал бы человек.
     *
     * <p>Порядок важен: сверка идёт началом строки, и более длинный префикс
     * обязан стоять раньше общего.
     */
    private static final java.util.LinkedHashMap<String, String> NON_IMAGE_KINDS =
            new java.util.LinkedHashMap<>();

    static {
        NON_IMAGE_KINDS.put("application/pdf", "PDF-документ");
        NON_IMAGE_KINDS.put("application/msword", "документ Word");
        NON_IMAGE_KINDS.put("application/vnd.openxmlformats-officedocument.wordprocessing",
                "документ Word");
        NON_IMAGE_KINDS.put("application/vnd.ms-excel", "таблица Excel");
        NON_IMAGE_KINDS.put("application/vnd.openxmlformats-officedocument.spreadsheet",
                "таблица Excel");
        NON_IMAGE_KINDS.put("application/zip", "архив");
        NON_IMAGE_KINDS.put("application/x-rar", "архив");
        NON_IMAGE_KINDS.put("application/x-7z", "архив");
        NON_IMAGE_KINDS.put("application/gzip", "архив");
        NON_IMAGE_KINDS.put("text/", "текстовый файл");
        NON_IMAGE_KINDS.put("video/", "видеозапись");
        NON_IMAGE_KINDS.put("audio/", "звукозапись");
    }

    /**
     * Отказ человеку: называет словами, что приложили и что годится.
     *
     * <p><b>MIME-типа в тексте нет ни в каком виде</b>, и это не придирка.
     * «application/pdf» на экране — внутреннее представление: человек выбирал
     * файл, а не тип, и по такому слову он не поймёт ни что случилось,
     * ни что делать. Класс в этом проекте уже оплачен — отчёт про деньги писал
     * «клиент 42» вместо имени человека (задача 0065), — и правило записано:
     * внутреннего представления на экране нет.
     *
     * <p>Незнакомый тип не называется <b>вовсе</b>: выдуманное слово хуже
     * молчания, как «Неизвестное устройство» в журнале входов и прочерк
     * у рестайлинга донора.
     */
    public static String refusalFor(String contentType) {
        String kind = humanKindOf(contentType);
        String canAttach = "Приложить можно фотографию: " + supportedImageLabels();
        return kind == null
                ? "Это не картинка. " + canAttach
                : "Это не картинка, а %s. %s".formatted(kind, canAttach);
    }

    private static String humanKindOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String normalized = contentType.trim().toLowerCase();
        return NON_IMAGE_KINDS.entrySet().stream()
                .filter(kind -> normalized.startsWith(kind.getKey()))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Расширение объекта в хранилище.
     *
     * <p>Умолчание {@code .jpg} оставлено для незнакомого типа намеренно:
     * этим путём идёт ещё перенос снимков с чужого CDN
     * ({@code PhotoMigration}), где тип берётся из заголовка чужого сервера
     * и бывает любым, а терять снимок клиента из-за нестандартного заголовка
     * нельзя. Для загрузки из кабинета и с телефона незнакомый тип до сюда
     * не доходит — его отбивает {@link #isSupportedImage} в
     * {@code PhotoService.requestUpload}.
     */
    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        String normalized = contentType.trim().toLowerCase();
        return IMAGE_TYPES.stream()
                .filter(type -> type.contentType().equals(normalized))
                .map(ImageType::extension)
                .findFirst()
                .orElse(".jpg");
    }
}
