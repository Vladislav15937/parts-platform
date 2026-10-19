package ru.partsflow.platform.audit;

/**
 * Пометка «эта правка — часть массовой» для журнала изменений (задача 0373).
 *
 * <p><b>Зачем.</b> Вопрос «почему у детали вдруг другая цена» задают после
 * правки списком, а в истории позиции такая правка выглядела правкой руками
 * по одной: «Заметка: → …» и автор. Что её применили к сорока позициям разом,
 * не говорило ничто, и разбор уходил в журнал действий.
 *
 * <p><b>Пометка ложится в снимок строки, а не в колонку.</b> Своего поля
 * под «чем правили» у {@code audit_log} нет, а снимок — jsonb, и имена в нём
 * уже бывают не колонками ({@code oem} и {@code crosses} у {@code AuditJournal}).
 * Читают снимок по закрытым спискам полей ({@code AuditedColumns}), поэтому
 * лишние имена не превращаются в мнимую правку. Ключи — {@link #SIZE_KEY}
 * и {@link #SCOPE_KEY}; у таблиц позиции колонок с такими именами нет.
 *
 * <p><b>Действует, пока открыт, и только в своём потоке</b> — так же, как
 * вошедший у {@link CurrentAuthor}. Открывает его тот, кто знает размер
 * пачки, вокруг сброса правок в базу: слушатель пишет журнал при сбросе,
 * а не при вызове сеттера.
 */
public final class AuditBatch implements AutoCloseable {

    /** Ключ снимка: сколько позиций изменила массовая правка. */
    public static final String SIZE_KEY = "bulk_size";

    /** Ключ снимка: чем выбраны позиции — {@link Scope}. */
    public static final String SCOPE_KEY = "bulk_scope";

    /** Чем выбраны позиции массовой правки. */
    public enum Scope {
        /** Отмеченные строки. */
        SELECTED,
        /** Всё, что попало в отбор витрины. */
        FILTER
    }

    private static final ThreadLocal<AuditBatch> CURRENT = new ThreadLocal<>();

    private final int size;
    private final Scope scope;
    private final AuditBatch outer;

    private AuditBatch(int size, Scope scope, AuditBatch outer) {
        this.size = size;
        this.scope = scope;
        this.outer = outer;
    }

    /** Открывает пометку; закрыть — {@link #close()}, лучше через try-with-resources. */
    public static AuditBatch open(int size, Scope scope) {
        AuditBatch batch = new AuditBatch(size, scope, CURRENT.get());
        CURRENT.set(batch);
        return batch;
    }

    /** @return открытая пометка либо {@code null} — правка не массовая */
    static AuditBatch current() {
        return CURRENT.get();
    }

    int size() {
        return size;
    }

    Scope scope() {
        return scope;
    }

    @Override
    public void close() {
        if (outer == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(outer);
        }
    }
}
