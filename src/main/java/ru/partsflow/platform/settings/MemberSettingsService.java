package ru.partsflow.platform.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Настройки экрана за сотрудником: как он разложил себе таблицу.
 *
 * <p><b>На сервере, а не в браузере.</b> Решение владельца продукта
 * от 11 сентября 2026 (задача 0060) названо дословно: «для каждого отдельного
 * пользователя всегда. Даже если он закрыл браузер, выключил компьютер, открыл
 * в другом браузере или из другого места». {@code localStorage} не переживает
 * ни другой браузер, ни другое устройство, ни приватное окно — то есть
 * выполняет это требование в одном случае из четырёх.
 *
 * <p><b>Что здесь лежит и чего здесь не лежит.</b> Состояние экрана: порядок
 * сортировки, отборы, состав колонок. Ничего, что касалось бы кого-то, кроме
 * самого сотрудника, — иначе это были бы данные предприятия, а им место
 * в своей таблице со своими колонками и своими проверками.
 *
 * <p>Отсюда и свободный jsonb: колонками под каждую настройку это означало бы
 * миграцию на каждую новую колонку витрины (их сорок шесть) и на каждый экран,
 * который захочет что-то помнить. Взамен приложение стережёт две вещи —
 * имя экрана и размер записи: без них поле превращается в место, куда клиент
 * кладёт что угодно и сколько угодно.
 */
@Service
public class MemberSettingsService {

    /**
     * Имя экрана: латиница, цифры и дефис.
     *
     * <p>Белый список по форме, а не перечень экранов: перечень пришлось бы
     * править сервером на каждый новый экран, который захотел что-то помнить,
     * — а имя здесь ключ строки, и вреда от незнакомого нет. Форма нужна
     * затем, чтобы ключом не стал случайный мусор или чужой идентификатор.
     */
    private static final Pattern SCREEN = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    /**
     * Предел размера записи.
     *
     * <p>Настройка витрины со всеми отборами и сорока шестью колонками — около
     * килобайта. Восемь даёт запас на порядок и при этом не даёт сложить сюда
     * выгрузку склада: таблица не хранилище, а строка читается при каждом
     * открытии экрана.
     */
    private static final int MAX_BYTES = 8 * 1024;

    private final JdbcTemplate jdbc;

    private final ObjectMapper json;

    public MemberSettingsService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return пусто, если сотрудник этот экран ещё не настраивал */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> read(long memberId, String screen) {
        requireScreen(screen);
        List<String> found = jdbc.queryForList(
                "SELECT value::text FROM member_setting WHERE member_id = ? AND screen = ?",
                String.class, memberId, screen);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(found.get(0), new MapType()));
        } catch (JsonProcessingException e) {
            // Своё же записанное не разобралось — это не повод не открыть
            // экран: настройка вернётся к умолчанию, а не уронит витрину.
            return Optional.empty();
        }
    }

    @Transactional
    public void write(long memberId, String screen, Map<String, Object> value) {
        requireScreen(screen);
        if (value == null) {
            throw new IllegalArgumentException("Настройка экрана не передана");
        }
        String text;
        try {
            text = json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Настройка экрана не разбирается как JSON");
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Настройка экрана длиннее допустимого: " + MAX_BYTES + " байт");
        }
        jdbc.update("""
                INSERT INTO member_setting (member_id, screen, value)
                VALUES (?, ?, ?::jsonb)
                ON CONFLICT (member_id, screen)
                DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                memberId, screen, text);
    }

    private static void requireScreen(String screen) {
        if (screen == null || !SCREEN.matcher(screen).matches()) {
            throw new IllegalArgumentException("Недопустимое имя экрана: " + screen);
        }
    }

    /** Отдельным типом, чтобы не писать generic-каст на каждом чтении. */
    private static final class MapType
            extends com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> {
    }
}
