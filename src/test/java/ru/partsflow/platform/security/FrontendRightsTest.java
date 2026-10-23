package ru.partsflow.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ключи прав на экране — те же, что у сервера (задача 0044).
 *
 * <p>Экран спрашивает {@code rights.has('deal.sell')}, и ключ в этом вопросе
 * типизирован перечнем {@code PermissionKey} в {@code frontend/src/access/rights.ts}.
 * Перечень — вторая запись справочника рядом с {@link Permission}, и разойтись
 * они могут молча: ключ, переименованный только на сервере, гасит кнопку у всех,
 * кому право дано. Второе место рядом — наборы прежних ролей
 * ({@code LEGACY_ROLE_PERMISSIONS}) для личности, запомненной телефоном до 0044:
 * они обязаны совпадать с раздачей {@code tenant/101}.
 *
 * <p>Тест на стороне сервера: фронтенд Java-исходник не читает, а vitest файлы
 * читать не умеет (типов Node в сборке нет) — тот же довод, что у
 * {@code WordingConsistencyTest}.
 */
class FrontendRightsTest {

    private static final Path RIGHTS = Path.of("frontend/src/access/rights.ts");
    private static final Path SEED = Path.of("db/changelog/tenant/101-access-roles-seed.sql");

    @Test
    @DisplayName("PermissionKey на экране — ровно справочник сервера")
    void keysMatchServerCatalog() throws IOException {
        String text = Files.readString(RIGHTS);
        String union = text.substring(text.indexOf("export type PermissionKey ="),
                text.indexOf(';', text.indexOf("export type PermissionKey =")));
        Set<String> screen = quoted(union);
        Set<String> server = Arrays.stream(Permission.values()).map(Permission::key)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(screen).as("ключ, которого сервер не знает или экран не назвал")
                .containsExactlyInAnyOrderElementsOf(server);
    }

    @Test
    @DisplayName("Наборы прежних ролей на экране — раздача tenant/101")
    void legacySetsMatchSeed() throws IOException {
        String text = Files.readString(RIGHTS);
        String block = text.substring(text.indexOf("LEGACY_ROLE_PERMISSIONS"),
                text.indexOf("};", text.indexOf("LEGACY_ROLE_PERMISSIONS")));
        Map<String, Set<String>> screen = new TreeMap<>();
        Matcher role = Pattern.compile("([A-Z]+): \\[([^\\]]*)\\]").matcher(block);
        while (role.find()) {
            screen.put(role.group(1), quoted(role.group(2)));
        }

        Map<String, Set<String>> seed = new TreeMap<>();
        Matcher pair = Pattern.compile("\\('([A-Z]+)',\\s*'([a-z_.]+)'\\)")
                .matcher(Files.readString(SEED));
        while (pair.find()) {
            seed.computeIfAbsent(pair.group(1), r -> new TreeSet<>()).add(pair.group(2));
        }
        assertThat(seed).as("раздача разобрана").containsKeys("MANAGER", "SELLER", "AUDITOR");
        assertThat(screen).as("личность до 0044 получила бы на телефоне не те права")
                .isEqualTo(seed);
    }

    private static Set<String> quoted(String text) {
        return Stream.of(text.split("'")).filter(part -> part.matches("[a-z][a-z_.]*"))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
