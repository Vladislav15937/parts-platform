package ru.partsflow.migration.bazon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.inventory.PhotoStorage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Перенос фотографий из предыдущей системы.
 *
 * <p><b>Отдельным проходом, а не внутри импорта склада.</b> Тридцать шесть
 * тысяч позиций — это под сотню тысяч файлов с чужого CDN. Внутри HTTP-запроса
 * импорта это часы: соединение оборвётся, и владелец останется с непонятным
 * состоянием — часть склада есть, часть снимков есть, сколько именно, неизвестно.
 * Здесь же проход берёт пачку, отчитывается числом и продолжается следующим
 * вызовом; прерывать его можно в любой момент.
 *
 * <p><b>Отдельный сбой не останавливает проход.</b> Ссылка протухла, файл
 * удалён, CDN ответил пятисоткой — задание помечается неудачным с причиной,
 * и проход идёт дальше. Иначе одна битая ссылка из ста тысяч останавливает
 * перенос, и разбирать это придётся в день подключения.
 *
 * <p><b>Транзакция держится явно, а не аннотацией.</b> Шаги прохода зовут
 * друг друга внутри одного бина, а self-invocation идёт мимо прокси Spring:
 * {@code @Transactional} там молча не срабатывает. А без транзакции
 * {@code JdbcTemplate} берёт соединение из пула напрямую, и {@code search_path}
 * указывает в {@code public} — «relation part_photo_import does not exist».
 * Та же ловушка, что записана про отчёты и про метрики очереди.
 */
@Service
public class PhotoMigration {

    private static final Logger log = LoggerFactory.getLogger(PhotoMigration.class);

    /**
     * Предел размера снимка. Прежняя система отдаёт превью и оригиналы;
     * оригинал с зеркальной камеры бывает и двадцатимегабайтным, а карточке
     * нужен снимок детали, а не обои. Больше — значит по ссылке не фотография.
     */
    private static final long MAX_BYTES = 12L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final PhotoStorage storage;
    private final TransactionTemplate transactions;
    private final HttpClient http;

    public PhotoMigration(JdbcTemplate jdbc, PhotoStorage storage,
                          TransactionTemplate transactions,
                          @Value("${app.migration.photo-timeout:20s}") Duration timeout) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transactions = transactions;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                // Чужой CDN отвечает редиректом на своё зеркало, и без этого
                // перенос получил бы сто тысяч пустых ответов вместо снимков.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Переносит очередную пачку.
     *
     * <p>Транзакция на задание, а не на пачку: скачивание идёт минуты,
     * и держать всё это время одну транзакцию значит держать соединение
     * с базой ради ожидания сети.
     *
     * @return сколько перенесено, сколько не вышло и сколько осталось
     */
    public Progress migrateBatch(int limit) {
        List<Task> batch = transactions.execute(status -> pending(limit));
        int done = 0;
        int failed = 0;

        for (Task task : batch) {
            try {
                transfer(task);
                done++;
            } catch (Exception e) {
                markFailed(task.id(), reason(e));
                failed++;
            }
        }

        int transferred = done;
        int broken = failed;
        Progress progress = transactions.execute(status ->
                new Progress(transferred, broken, pendingCount(), doneCount(), failedCount()));
        log.info("Перенос фотографий: перенесено {}, не вышло {}, осталось {}",
                progress.done(), progress.failed(), progress.pending());
        return progress;
    }

    public Progress status() {
        return transactions.execute(status ->
                new Progress(0, 0, pendingCount(), doneCount(), failedCount()));
    }

    /**
     * Возвращает неудачные задания в очередь.
     *
     * <p>Отдельным действием, а не автоматически: CDN, лежавший десять минут,
     * чинится повтором, а удалённый файл — нет, и вечный повтор скрывал бы
     * второе за первым.
     */
    public int retryFailed() {
        return transactions.execute(status -> jdbc.update("""
                UPDATE part_photo_import SET status = 'PENDING', error = NULL
                 WHERE status = 'FAILED'"""));
    }

    private List<Task> pending(int limit) {
        return jdbc.query("""
                SELECT id, part_id, url, sort_order FROM part_photo_import
                 WHERE status = 'PENDING' ORDER BY id LIMIT ?""",
                (rs, i) -> new Task(rs.getLong("id"), rs.getLong("part_id"),
                        rs.getString("url"), rs.getInt("sort_order")),
                limit);
    }

    private void transfer(Task task) throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(task.url())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("ответ " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length == 0) {
            throw new IllegalStateException("пустой файл");
        }
        if (body.length > MAX_BYTES) {
            throw new IllegalStateException("файл больше " + (MAX_BYTES / 1024 / 1024) + " МБ");
        }

        String contentType = response.headers().firstValue("content-type")
                .filter(t -> t.startsWith("image/"))
                .orElse("image/jpeg");

        transactions.executeWithoutResult(status -> store(task, body, contentType));
    }

    /**
     * Кладёт файл и отмечает задание — в одной транзакции.
     *
     * <p>Порядок важен: сначала объект в хранилище, потом запись. Обратный
     * даёт карточку со ссылкой в никуда, а лишний файл в хранилище — это
     * место на диске, и только.
     */
    private void store(Task task, byte[] body, String contentType) {
        String key = storage.newKey(task.partId(), contentType);
        storage.put(key, body, contentType);

        // Главной становится первая по порядку — тот же снимок, что был
        // главным в прежней системе. Частичный индекс не даст завести вторую.
        Long photoId = jdbc.queryForObject("""
                INSERT INTO part_photo (part_id, s3_key, sort_order, is_main, bytes, status)
                VALUES (?, ?, ?, ?, ?, 'PROCESSED')
                RETURNING id""",
                Long.class, task.partId(), key, task.sortOrder(),
                task.sortOrder() == 0 && !hasMain(task.partId()), (long) body.length);

        jdbc.update("""
                UPDATE part_photo_import
                   SET status = 'DONE', photo_id = ?, error = NULL, done_at = now()
                 WHERE id = ?""", photoId, task.id());
    }

    private boolean hasMain(long partId) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM part_photo WHERE part_id = ? AND is_main)",
                Boolean.class, partId);
        return Boolean.TRUE.equals(found);
    }

    private void markFailed(long id, String reason) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE part_photo_import SET status = 'FAILED', error = ?, done_at = now()
                 WHERE id = ?""", reason, id));
    }

    /**
     * Причина отказа словами.
     *
     * <p><b>Зачем.</b> Соседняя кнопка возвращает неудачные в очередь, и решение
     * «нажимать или не нажимать» целиком зависит от причины: лежавший CDN
     * чинится повтором, удалённый файл — нет, а закрытая наружу сеть означает,
     * что чинить надо не здесь. Пока причина бралась из {@code getMessage()},
     * этого решения принять было нельзя: у отказа соединения сообщение пустое,
     * и все двести заданий получали «неизвестная ошибка».
     *
     * <p>Поймано прогоном подключения: CDN прежней системы перестал отвечать
     * на обоих портах, и перенос двухсот снимков отчитался ровно ничем.
     */
    static String reason(Throwable e) {
        if (e instanceof java.net.UnknownHostException) {
            return "источник не найден по имени: " + e.getMessage();
        }
        if (e instanceof java.net.ConnectException
                || e instanceof java.net.http.HttpConnectTimeoutException) {
            // Сообщения у этих исключений может не быть вовсе, а сказать надо:
            // «не отвечает» — это про повтор, а не про новые ссылки.
            return "источник не отвечает (" + e.getClass().getSimpleName() + ")";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "источник не ответил вовремя";
        }
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : message;
    }

    private long countBy(String status) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM part_photo_import WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    private long pendingCount() {
        return countBy("PENDING");
    }

    private long doneCount() {
        return countBy("DONE");
    }

    private long failedCount() {
        return countBy("FAILED");
    }

    public record Task(long id, long partId, String url, int sortOrder) {
    }

    /**
     * @param done      перенесено этой пачкой
     * @param failed    не вышло этой пачкой
     * @param pending   осталось в очереди
     * @param total     перенесено всего
     * @param broken    не вышло всего — их разбирают отдельно
     */
    public record Progress(int done, int failed, long pending, long total, long broken) {
    }
}
