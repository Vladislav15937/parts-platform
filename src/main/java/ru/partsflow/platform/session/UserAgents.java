package ru.partsflow.platform.session;

/**
 * Строка браузера — словами, на показе.
 *
 * <p><b>Разбор здесь, а не при записи.</b> Решение владельца продукта
 * от 9 сентября 2026 (задача 0043): «хранить сырое, показывать разобранное».
 * Браузеры меняют формат строки, а Chrome с 2023 года отдаёт «замороженный»
 * {@code User-Agent} с фиктивной версией — разобрав при записи, через год
 * получим журнал с неверными данными и без возможности переразобрать. Сырое
 * не портится, и оно остаётся в базе навсегда.
 *
 * <p><b>Версии не показываются намеренно.</b> «Chrome 127.0.6533.120
 * на Windows 10.0» — это то, чего человек не спрашивал: журнал открывают,
 * чтобы узнать, свой это вход или чужой, и отвечает на это связка
 * «браузер и система», а не номер сборки.
 *
 * <p><b>Не разобрали — так и говорим.</b> Придуманное «Chrome» на незнакомой
 * строке хуже честного незнания: по устройству узнают чужой вход, и подделка
 * тут стоит дороже пустоты. Сырая строка при этом никуда не девается — она
 * уезжает на экран рядом и видна по наведению.
 */
final class UserAgents {

    private UserAgents() {
    }

    /**
     * @return «Chrome · Windows», либо только браузер, либо только система,
     *         либо {@code null} — не разобрали
     */
    static String describe(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String browser = browser(raw);
        String os = os(raw);
        if (browser == null && os == null) {
            return null;
        }
        if (browser == null) {
            return os;
        }
        return os == null ? browser : browser + " · " + os;
    }

    /**
     * Порядок проверок обязателен и обратный привычному.
     *
     * <p>Все производные Chromium пишут в строку и {@code Chrome}, и себя:
     * Edge — {@code Edg/}, Opera — {@code OPR/}, Яндекс — {@code YaBrowser}.
     * Спросив про Chrome первым, мы назовём Chrome'ом все четыре, и владелец,
     * работающий в Яндекс.Браузере, не узнает собственный вход.
     */
    private static String browser(String raw) {
        if (raw.contains("YaBrowser")) {
            return "Яндекс.Браузер";
        }
        if (raw.contains("Edg/") || raw.contains("Edge/")) {
            return "Edge";
        }
        if (raw.contains("OPR/") || raw.contains("Opera")) {
            return "Opera";
        }
        if (raw.contains("Firefox/")) {
            return "Firefox";
        }
        if (raw.contains("Chrome/")) {
            return "Chrome";
        }
        // Safari пишет Safari в строке последним, а Chromium — тоже пишет.
        // Значит Safari — это то, что называет себя Safari и не назвало себя
        // ничем из перечисленного выше.
        if (raw.contains("Safari/")) {
            return "Safari";
        }
        return null;
    }

    /**
     * Телефон отличается от настольной машины, и это первое, что смотрят.
     *
     * <p>Приёмщик работает с телефона, владелец — из кабинета: вход владельца
     * с Android — это как раз то, ради чего журнал и открывают.
     */
    private static String os(String raw) {
        if (raw.contains("Android")) {
            return "Android";
        }
        if (raw.contains("iPhone") || raw.contains("iPad") || raw.contains("iOS")) {
            return "iOS";
        }
        if (raw.contains("Windows")) {
            return "Windows";
        }
        if (raw.contains("Mac OS X") || raw.contains("Macintosh")) {
            return "macOS";
        }
        if (raw.contains("Linux")) {
            return "Linux";
        }
        return null;
    }
}
