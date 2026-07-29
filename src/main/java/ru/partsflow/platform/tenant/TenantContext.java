package ru.partsflow.platform.tenant;

/**
 * Текущий арендатор в рамках потока обработки запроса.
 *
 * <p>Хранится в ThreadLocal и обязателен для любой работы с данными: без него
 * {@link TenantConnectionProvider} не знает, какую схему подставлять, и намеренно
 * падает вместо того, чтобы обратиться к схеме по умолчанию. Молчаливый фолбэк
 * здесь опаснее исключения — он означал бы запрос не к тому клиенту.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String schemaName) {
        if (schemaName == null || !schemaName.matches("t_\\d{6,}")) {
            throw new IllegalArgumentException("Недопустимое имя схемы арендатора: " + schemaName);
        }
        CURRENT.set(schemaName);
    }

    public static String require() {
        String value = CURRENT.get();
        if (value == null) {
            throw new IllegalStateException(
                    "Арендатор не установлен. Работа с данными вне контекста арендатора запрещена.");
        }
        return value;
    }

    public static String getOrNull() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Имя схемы по идентификатору арендатора: 42 -> t_000042. */
    public static String schemaFor(long tenantId) {
        return "t_%06d".formatted(tenantId);
    }
}
