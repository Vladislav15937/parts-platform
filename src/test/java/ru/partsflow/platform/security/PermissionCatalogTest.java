package ru.partsflow.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Справочник действий в коде сверяется с раздачей {@code tenant/101}.
 *
 * <p>Ключ живёт в двух местах: строкой в наборах ролей у каждого клиента
 * и константой здесь. Переименованный только в коде, он молча отнял бы право
 * у всех, кому было роздано, — сервер спрашивал бы новый ключ, а в базе лежал
 * бы старый. Схема этого не видит (у неё только форма ключа), и тест на роли
 * через HTTP тоже не увидел бы: у владельца права есть всегда.
 */
class PermissionCatalogTest {

    private static final Path SEED = Path.of("db/changelog/tenant/101-access-roles-seed.sql");
    private static final Path SPLIT = Path.of("db/changelog/tenant/103-members-manage-split.sql");

    /**
     * Двенадцать ключей, которые до задачи 0044 мог только владелец: при
     * переносе их не получила ни одна роль (ответ владельца 23.10.2026,
     * «Остальное да»), но справочник обязан их знать — их раздаёт владелец.
     */
    private static final Set<String> OWNER_ONLY_BEFORE_0044 = Set.of(
            "stock_document.delete_done", "supply.expected", "applicability.bulk",
            "marketplace.manage", "deal_sources.manage", "payment_sources.manage",
            "org.branches", "org.availability", "admin_access.manage",
            "settings.company", "import.manage",
            // «Управлять сотрудниками и ролями» разделено на четыре (ответ владельца
            // 24.10.2026, «развёрнутые вопросы», 2а): их выдаёт tenant/103 тем, у кого
            // был прежний members.manage, а сам он кодом не читается.
            "members.accounts", "members.assign_roles", "members.roles", "members.grant_overrides");

    @Test
    @DisplayName("Каждый ключ, розданный ролям при переносе, есть в справочнике")
    void everySeededKeyIsKnown() throws IOException {
        Set<String> seeded = seededKeys();
        assertThat(seeded).as("раздача разобрана").hasSizeGreaterThan(30);

        assertThat(catalog()).as("ключ раздачи tenant/101, которого код не знает: право пропало бы")
                .containsAll(seeded);
    }

    @Test
    @DisplayName("Справочник — это раздача плюс ключи «только владельца», и ничего сверх")
    void catalogIsSeedPlusOwnerOnly() throws IOException {
        Set<String> expected = new TreeSet<>(seededKeys());
        expected.addAll(OWNER_ONLY_BEFORE_0044);

        assertThat(catalog()).as("новый ключ заводится вместе с решением, кому его раздать")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Перенос {@code tenant/103} выдаёт четыре права на раздачу вместо прежнего
     * {@code members.manage}. Ключ, написанный там иначе, чем в коде, выдал бы
     * право, которого код не знает, — то есть отнял бы раздачу у всех, кому она была.
     */
    @Test
    @DisplayName("Ключи переноса tenant/103 — ровно четыре права на раздачу из справочника, а прежний ключ кодом не знается")
    void membersSplitKeysAreKnown() throws IOException {
        Matcher value = Pattern.compile("\\('([a-z_]+\\.[a-z_.]+)'\\)")
                .matcher(Files.readString(SPLIT));
        Set<String> written = new TreeSet<>();
        while (value.find()) {
            written.add(value.group(1));
        }
        assertThat(written).as("ключи, которые выдаёт перенос")
                .containsExactlyInAnyOrder("members.accounts", "members.assign_roles",
                        "members.roles", "members.grant_overrides");
        assertThat(catalog()).containsAll(written).doesNotContain("members.manage");
    }

    @Test
    @DisplayName("Ключ справочника — той формы, которую принимает схема")
    void keysHaveSchemaForm() {
        Pattern form = Pattern.compile("^[a-z][a-z_]*(\\.[a-z][a-z_]*)+$");
        for (Permission permission : Permission.values()) {
            assertThat(permission.key()).as(permission.name()).matches(form);
            assertThat(permission.title()).as(permission.name() + ": слово для экрана").isNotBlank();
        }
    }

    private static Set<String> catalog() {
        return Arrays.stream(Permission.values()).map(Permission::key)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> seededKeys() throws IOException {
        Matcher pair = Pattern.compile("\\('([A-Z]+)',\\s*'([a-z_.]+)'\\)")
                .matcher(Files.readString(SEED));
        Set<String> keys = new TreeSet<>();
        while (pair.find()) {
            keys.add(pair.group(2));
        }
        return keys;
    }
}
