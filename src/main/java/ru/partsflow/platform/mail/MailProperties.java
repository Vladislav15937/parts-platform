package ru.partsflow.platform.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки отправки писем (задача 0192).
 *
 * <p>{@code provider}: {@code unisender} — настоящий оператор (Unisender Go,
 * решение владельца продукта от 11 октября 2026), {@code log} — письма
 * печатаются в лог и никуда не уходят. Умолчание {@code log}, а не оператор:
 * ключа и договора на день заведения нет, а ячейка без них обязана подниматься.
 * Но «не уходят» должно быть сказано словами — это делают {@link MailConfigCheck}
 * при старте и {@code ops/config-guard.sh} перед подъёмом.
 *
 * <p>{@code baseUrl} настройкой, а не константой: адрес API зависит от
 * дата-центра, где заведён аккаунт (go1 или go2), и «User with id … not found»
 * от оператора означает как раз не тот адрес. Узнать верный можно только
 * из кабинета, то есть у человека с договором, — поэтому умолчания нет:
 * угаданный адрес превращал бы настройку, которой не хватает, в отказы
 * оператора по существу. Вид адреса: {@code https://go1.unisender.ru/ru/transactional/api/v1}.
 *
 * <p>{@code webhookSecret} — часть адреса, по которому оператор присылает
 * статусы доставки; пустой секрет выключает приём (404), а не оставляет
 * его открытым.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        String provider,
        String apiKey,
        String baseUrl,
        String fromEmail,
        String fromName,
        String webhookSecret,
        Integer maxAttempts,
        Duration retryDelay,
        Duration httpTimeout,
        Duration claimTtl,
        Integer batchSize) {

    public MailProperties {
        provider = blank(provider) ? "log" : provider.trim().toLowerCase();
        maxAttempts = maxAttempts == null ? 8 : maxAttempts;
        retryDelay = retryDelay == null ? Duration.ofMinutes(2) : retryDelay;
        httpTimeout = httpTimeout == null ? Duration.ofSeconds(15) : httpTimeout;
        // Заявка на строку живёт дольше таймаута запроса к оператору: иначе
        // второй заход отправит то же письмо, пока первое ещё в пути.
        claimTtl = claimTtl == null ? Duration.ofMinutes(5) : claimTtl;
        batchSize = batchSize == null ? 20 : batchSize;
    }

    public boolean unisender() {
        return "unisender".equals(provider);
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
