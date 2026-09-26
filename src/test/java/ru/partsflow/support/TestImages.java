package ru.partsflow.support;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Образы для тестов — по адресу из {@code ops/images.yml}, и только оттуда.
 *
 * <p><b>Зачем это класс, а не литерал в тесте.</b> До 26 сентября 2026 адрес
 * хранилища был написан в пяти местах: здесь три теста и оба compose. В тот
 * день образ пропал из чужого реестра целиком, и правка пяти копий одного
 * значения неизбежно сделала бы шестую. Теперь адрес лежит в одном файле,
 * тот же файл читают оба compose через {@code extends}, и стережёт это
 * {@code tools/image-pin-guard.py}.
 *
 * <p><b>И зачем свой подъём вместо простого {@code container.start()}.</b>
 * Когда образа нет в реестре, Testcontainers повторяет попытку внутри своей
 * двухминутной выдержки, и наружу выходит {@code ConditionTimeoutException:
 * не выполнено за 2 минуты} — причём каждый из трёх тестов висел так
 * по 420 секунд, а настоящая причина («репозитория нет») лежала в глубине
 * стека. Двадцать минут прогона, чтобы узнать, что не скачался образ.
 * Поэтому наличие образа спрашивается заранее и один раз: локальный кэш,
 * затем одна попытка выкачать — и внятный отказ, называющий адрес и то,
 * чем его поправить.
 */
public final class TestImages {

    /**
     * Единственное место, где записан адрес. Путь от корня проекта: тесты
     * Maven гоняет из него же (там лежит и {@code db/changelog},
     * который читает {@link PostgresTestBase}).
     */
    private static final Path IMAGES = Path.of("ops", "images.yml");

    /** Сколько ждём выкачивания, прежде чем сказать «не смогли». */
    private static final int PULL_TIMEOUT_SECONDS = 180;

    private TestImages() {
    }

    /**
     * Хранилище снимков — настроенное так же, как в бою: тот же образ,
     * тот же {@code server /data}, тот же адрес проверки живости.
     *
     * <p>Контейнер возвращается не запущенным: бакет у каждого теста свой,
     * и ставится он в {@code @DynamicPropertySource} после {@link #start}.
     */
    @SuppressWarnings("resource")
    public static GenericContainer<?> minio() {
        return new GenericContainer<>(image("minio"))
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", "minioadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                .withCommand("server", "/data")
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
    }

    /**
     * Поднять хранилище, а при недоступном образе отказать сразу и словами.
     *
     * <p>Проверка стоит до {@code start()} намеренно: дальше отказ уже
     * не наш — он уходит в повторные попытки Testcontainers и возвращается
     * таймаутом, по которому про образ не догадаться.
     *
     * <p><b>Адрес берётся из пина, а не у контейнера — и это не вкусовщина.</b>
     * {@code container.getDockerImageName()} выглядит дешёвым получателем
     * имени, но внутри он <b>разрешает образ</b>, то есть выкачивает его
     * и бросает {@code ContainerFetchException} прямо оттуда. Первая редакция
     * этого класса спрашивала имя у контейнера — и вся проверка ниже
     * не выполнялась ни разу: отказ приходил строкой выше, прежним
     * невнятным способом. Поймано возвратом дефекта (пункт 8 задачи 0180),
     * а не рассуждением.
     */
    public static void startMinio(GenericContainer<?> container) {
        ensureAvailable(image("minio"));
        container.start();
    }

    /** Адрес образа сервиса из {@code ops/images.yml}. */
    public static String image(String service) {
        String text;
        try {
            text = Files.readString(IMAGES);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Не читается " + IMAGES.toAbsolutePath() + " — в нём записаны адреса образов "
                            + "(единственное место). Тесты гоняются из корня проекта.", e);
        }

        // Разбор строчный, без YAML-библиотеки: тащить её в тесты ради двух
        // строк незачем, а формат фрагмента наш и стережётся сторожем.
        Pattern serviceLine = Pattern.compile("^ {2}([A-Za-z0-9_.-]+):\\s*$");
        Pattern imageLine = Pattern.compile("^\\s+image:\\s*(\\S+)\\s*$");
        String current = null;
        for (String line : text.split("\n")) {
            Matcher svc = serviceLine.matcher(line);
            if (svc.matches()) {
                current = svc.group(1);
                continue;
            }
            Matcher img = imageLine.matcher(line);
            if (img.matches() && service.equals(current)) {
                return img.group(1);
            }
        }
        throw new IllegalStateException(
                "В " + IMAGES + " нет образа для сервиса «" + service + "» — "
                        + "добавьте его туда, а не литералом в тест.");
    }

    private static void ensureAvailable(String image) {
        DockerClient docker = DockerClientFactory.lazyClient();
        if (present(docker, image)) {
            return;
        }
        try {
            int colon = image.lastIndexOf(':');
            int slash = image.lastIndexOf('/');
            String repository = colon > slash ? image.substring(0, colon) : image;
            String tag = colon > slash ? image.substring(colon + 1) : "latest";
            docker.pullImageCmd(repository)
                    .withTag(tag)
                    .start()
                    .awaitCompletion(PULL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(cannotGet(image, e), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(cannotGet(image, e), e);
        }
        if (!present(docker, image)) {
            throw new IllegalStateException(cannotGet(image, null));
        }
    }

    private static boolean present(DockerClient docker, String image) {
        try {
            docker.inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException absent) {
            return false;
        }
    }

    private static String cannotGet(String image, Throwable cause) {
        return """
                Образ %s не получен ни из кэша, ни из реестра.

                Это зеркало в нашем GHCR (адрес — ops/images.yml, одно место на весь проект).
                Значит одно из трёх: пакет сняли, он стал приватным или реестр недоступен.

                Что посмотреть и чем поправить:
                  tools/mirror-images.sh --проверить      что лежит в зеркале (мимо кэша)
                  tools/mirror-images.sh --опубликовать   положить образ обратно
                А в прогоне это делает задача CI «Зеркало образов».

                Причина от Docker: %s"""
                .formatted(image, cause == null ? "образа нет и после выкачивания" : cause.getMessage());
    }
}
