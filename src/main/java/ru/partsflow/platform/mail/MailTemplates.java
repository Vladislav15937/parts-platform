package ru.partsflow.platform.mail;

/**
 * Все шаблоны писем — здесь и больше нигде (задача 0192).
 *
 * <p>Следующее письмо (сброс пароля, предупреждение) добавляется сюда же
 * методом, а не вторым способом собрать текст где-то ещё. Шаблоны — код, а не
 * ресурсы: два письма не стоят движка шаблонов, а имя шаблона попадает в журнал
 * ({@code mail_message.template}) и по нему видно, что человеку отправляли.
 *
 * <p>Слова письма — решение владельца продукта, не исполнителя. Нынешний текст
 * рабочий и подлежит замене на его формулировку.
 */
public final class MailTemplates {

    public static final String CONFIRM_EMAIL = "confirm-email";

    public record Rendered(String template, String subject, String text, String html) {}

    private MailTemplates() {}

    /** Подтверждение почты при регистрации компании. */
    public static Rendered confirmEmail(String link) {
        String subject = "Подтвердите почту";
        String text = "Чтобы завершить регистрацию, подтвердите почту — откройте ссылку:\n"
                + link + "\n\nЕсли вы не регистрировались, просто проигнорируйте это письмо.";
        String html = "<p>Чтобы завершить регистрацию, подтвердите почту.</p>"
                + "<p><a href=\"" + escape(link) + "\">Подтвердить почту</a></p>"
                + "<p>Если вы не регистрировались, просто проигнорируйте это письмо.</p>";
        return new Rendered(CONFIRM_EMAIL, subject, text, html);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
