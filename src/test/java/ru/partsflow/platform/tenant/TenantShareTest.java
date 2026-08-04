package ru.partsflow.platform.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.partsflow.support.PostgresTestBase;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Раздача арендаторов между экземплярами приложения.
 *
 * <p>Фоновые обходы идут в один поток по всем арендаторам ячейки, и как только
 * в обходе появляется разговор с чужим сервером, один медленный клиент
 * задерживает остальных. Доля делит их между экземплярами без координатора:
 * каждый знает свой номер, и деление на непересекающиеся остатки гарантирует,
 * что дважды за одного никто не возьмётся.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
class TenantShareTest extends PostgresTestBase {

    /**
     * Свои записи реестра, а не настоящие схемы: доля читает только реестр,
     * и заводить ради проверки деления семь схем — это семь накатов миграций.
     * Номера отодвинуты в свой диапазон, чтобы не мешать соседним тестам.
     */
    private static final long FIRST = 950_001L;
    private static final int COUNT = 7;

    @Autowired
    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void registerTenants() {
        for (int i = 0; i < COUNT; i++) {
            jdbc.update("""
                    INSERT INTO public.tenant_registry
                        (tenant_id, schema_name, company_name, code, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT (tenant_id) DO NOTHING""",
                    FIRST + i, "t_%06d".formatted(FIRST + i),
                    "Доля " + i, "share-%d".formatted(FIRST + i));
        }
    }

    @org.junit.jupiter.api.AfterEach
    void forgetTenants() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id BETWEEN ? AND ?",
                FIRST, FIRST + COUNT);
    }

    @Test
    @DisplayName("Доли не пересекаются и вместе дают всех")
    void sharesCoverEveryTenantExactlyOnce() {
        List<String> all = new TenantShare(jdbc, 0, 1).schemas();
        assertThat(all).isNotEmpty();

        for (int instances = 2; instances <= 4; instances++) {
            List<String> collected = new ArrayList<>();
            for (int index = 0; index < instances; index++) {
                collected.addAll(new TenantShare(jdbc, index, instances).schemas());
            }
            // Ни одного дважды — иначе два экземпляра возьмут одно событие;
            // ни одного пропущенного — иначе клиент навсегда без дельт.
            assertThat(collected)
                    .as("при %d экземплярах арендаторы разошлись неверно", instances)
                    .containsExactlyInAnyOrderElementsOf(all);
        }
    }

    @Test
    @DisplayName("Один экземпляр берёт всех — это умолчание")
    void singleInstanceTakesEverything() {
        assertThat(new TenantShare(jdbc, 0, 1).schemas())
                .isEqualTo(jdbc.queryForList(
                        "SELECT schema_name FROM public.tenant_registry "
                                + "WHERE status = 'ACTIVE' ORDER BY tenant_id", String.class));
    }

    @Test
    @DisplayName("Номер вне диапазона — отказ при запуске, а не тихая пустая доля")
    void wrongIndexIsRejected() {
        // Опечатка в настройке экземпляра иначе означала бы, что его доля
        // пуста и события этих клиентов не отправляет никто.
        assertThatThrownBy(() -> new TenantShare(jdbc, 3, 3))
                .hasMessageContaining("вне диапазона");
        assertThatThrownBy(() -> new TenantShare(jdbc, 0, 0))
                .hasMessageContaining("меньше одного");
    }
}
