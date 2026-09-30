package ru.partsflow.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Белый список форматов снимка — один, и оба его края сведены.
 *
 * <p><b>Зачем.</b> Сервер сверяет <b>заявленный</b> тип (байтов у него нет —
 * снимок идёт в S3 мимо приложения), а содержимое смотрит клиент по подписи
 * формата. Это разные проверки в разных языках, и разойтись они могут молча:
 * формат, добавленный на сервере и неизвестный клиенту, будет отбит у человека
 * при исправном сервере, а обратное пропустит файл, которому хранилище даст
 * чужое расширение. В этом проекте так уже расходились белые списки колонок
 * и словари состояний сделки.
 *
 * <p>Проверка на стороне сервера, а не фронтенда, по той же причине, что
 * у {@link WordingConsistencyTest}: читать файлы из сборки фронтенда нечем —
 * типов Node там нет, — а сверка нужна на каждой правке списка, с какой бы
 * стороны её ни сделали.
 *
 * <p>Контекст Spring не поднимается: проверяются чистые функции и текст
 * файлов. Схема арендатора здесь не нужна вовсе.
 */
class PhotoTypeListTest {

    private static final Path SERVICE = Path.of("src/main/java/ru/partsflow/inventory/PhotoService.java");
    private static final Path SNIFFER = Path.of("frontend/src/photos/imageFile.ts");
    private static final Path CARD_UPLOAD = Path.of("frontend/src/inventory/photos.ts");
    private static final Path RESIZE = Path.of("frontend/src/photos/resize.ts");

    @Test
    @DisplayName("Принимаемые типы приняты, а не-картинка отбита")
    void whitelistAnswersBothWays() {
        for (String contentType : PhotoStorage.supportedImageTypes()) {
            assertThat(PhotoStorage.isSupportedImage(contentType))
                    .as("законный снимок «%s» отбит", contentType)
                    .isTrue();
        }

        // HEIC — то, чем снимает айфон: снимок от поставщика приходит таким,
        // и отбить его значило бы сломать сам сценарий предзаказа.
        assertThat(PhotoStorage.isSupportedImage("image/heic")).isTrue();
        assertThat(PhotoStorage.isSupportedImage("IMAGE/JPEG")).isTrue();

        for (String alien : List.of("application/pdf", "text/plain", "application/octet-stream",
                "", "   ")) {
            assertThat(PhotoStorage.isSupportedImage(alien))
                    .as("«%s» принят как картинка", alien)
                    .isFalse();
        }
        assertThat(PhotoStorage.isSupportedImage(null)).isFalse();
    }

    @Test
    @DisplayName("Отказ называет форматы словами, а не MIME-типами")
    void refusalNamesFormats() {
        // Человек выбирал файл, а не MIME: «image/webp» в отказе — внутреннее
        // представление на экране.
        assertThat(PhotoStorage.supportedImageLabels())
                .contains("JPEG", "PNG", "WebP", "HEIC")
                .doesNotContain("image/");
    }

    /**
     * Отказ читает человек, и MIME-типа в нём нет ни в каком виде.
     *
     * <p>Проверка привязана к <b>самому тексту</b>, а не к списку форматов:
     * привязанная к списку, она зеленела бы при вернувшемся в текст
     * «application/pdf» — то есть стерегла бы не то, что сломается.
     */
    @Test
    @DisplayName("Отказ называет файл словами, а не MIME-типом")
    void refusalNamesTheFileInWords() {
        String pdf = PhotoStorage.refusalFor("application/pdf");

        assertThat(pdf)
                .as("MIME-тип уехал на экран: человек выбирал файл, а не тип, "
                        + "и по «application/pdf» не поймёт ни что случилось, ни что делать")
                .doesNotContain("application/pdf")
                // Косая черта есть в любом MIME и ни в одном человеческом слове:
                // так проверка ловит и тот тип, о котором мы не подумали.
                .doesNotContain("/")
                .contains("не картинка")
                .contains("PDF-документ")
                .contains("JPEG");

        for (String alien : List.of("text/plain", "video/mp4", "audio/mpeg",
                "application/zip", "application/msword")) {
            assertThat(PhotoStorage.refusalFor(alien))
                    .as("отказ на «%s» показал человеку MIME-тип", alien)
                    .doesNotContain("/")
                    .contains("не картинка");
        }

        // Незнакомый тип не называется вовсе: выдуманное слово хуже молчания.
        assertThat(PhotoStorage.refusalFor("application/x-nechto-strannoe"))
                .doesNotContain("/")
                .doesNotContain("nechto")
                .contains("не картинка")
                .contains("JPEG");
    }

    /**
     * Текст собирает {@link PhotoStorage}, а не сервис на месте.
     *
     * <p>Без этой привязки MIME вернётся на экран одной строкой в
     * {@code requestUpload}, и проверка выше останется зелёной: она спрашивает
     * {@code refusalFor}, а человек увидит то, что бросили.
     */
    @Test
    @DisplayName("Сервис не собирает текст отказа сам")
    void serviceDoesNotBuildItsOwnText() throws IOException {
        String service = read(SERVICE);

        assertThat(service)
                .as("отказ собирается не там, где лежат слова о форматах")
                .contains("PhotoStorage.refusalFor(");
        assertThat(service)
                .as("тип снова подставляется в текст на месте — MIME вернулся на экран")
                .doesNotContain(".formatted(contentType");
    }

    @Test
    @DisplayName("Клиент знает подпись каждого формата, который принимает сервер")
    void clientKnowsEveryServerFormat() throws IOException {
        String sniffer = read(SNIFFER);

        for (String label : PhotoStorage.supportedImageLabels().split(", ")) {
            assertThat(sniffer)
                    .as("сервер принимает %s, а клиент такой подписи не знает — "
                            + "законный снимок будет отбит у человека", label)
                    .contains(label);
        }
    }

    @Test
    @DisplayName("Загрузка из карточки смотрит содержимое до обращения к серверу")
    void cardUploadChecksContent() throws IOException {
        assertThat(read(CARD_UPLOAD))
                .as("проверка содержимого снята — PDF снова уедет в объявление под видом .jpg")
                .contains("looksLikeImage");
    }

    @Test
    @DisplayName("Уменьшение по-прежнему уступает дорогу, а не отбивает")
    void resizeStillYields() throws IOException {
        String resize = read(RESIZE);

        // Снимок с камеры, который не удалось уменьшить, уходит как есть:
        // иначе приёмщик теряет фотографию. Отбивается не-картинка,
        // а не «не удалось уменьшить», и знает об этом слой, которому
        // известно, откуда файл.
        assertThat(resize)
                .as("ветка «уходит как есть» снята — приёмщик потеряет снимок")
                .contains("blob: file");
        assertThat(resize)
                .as("отказ не-картинке переехал в уменьшение — теперь он накрыл и камеру")
                .doesNotContain("looksLikeImage");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
