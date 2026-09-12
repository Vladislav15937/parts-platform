package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Говорит при старте, действует ли защита журналов.
 *
 * <p>Приложение спрашивает у базы прямо: могу ли я переписать движение
 * склада? Если да — говорит об этом громко. Почему спрашивается база,
 * а не настройка, и почему смотрится один арендатор — в
 * {@link JournalProtection}; здесь только вывод человеку в лог.
 *
 * <p>Запуск при этом не валится. Разработка и первые прогоны идут одной
 * ролью намеренно, и падение на старте означало бы, что локально ничего
 * не поднять без двух ролей. Для выкладки этот же ответ отдаётся машинно —
 * {@code /actuator/readiness}: «не готов для выкладки» и «не запускаться» —
 * разные вещи.
 */
@Component
public class JournalProtectionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JournalProtectionCheck.class);

    private final JournalProtection protection;

    public JournalProtectionCheck(JournalProtection protection) {
        this.protection = protection;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        JournalProtection.Status status = protection.status();

        if (!status.checked()) {
            // Ячейка без арендаторов или без реестра: проверять нечего,
            // и молчание тут честнее строки «всё в порядке».
            return;
        }

        if (status.locked()) {
            log.info("Журналы защищены: рабочая роль {} не может править {} (проверено на {})",
                    status.role(), JournalProtection.JOURNALS, status.schema());
            return;
        }

        if (!status.reachable()) {
            // Прав нет вовсе — журналы «защищены» заодно со складом. Сказать
            // об этом надо именно так: тут не защита, тут неработающая ячейка,
            // и лечится она накатом, который права и выдаёт.
            log.warn("""
                    Рабочая роль {} не дотягивается до схемы {}: прав нет ни на журналы, \
                    ни на обычные таблицы. Ячейка в таком виде не работает — права на схемы \
                    выдаёт накат: ops/migrate-tenants.sh""",
                    status.role(), status.schema());
            return;
        }

        log.warn("""
                Журналы правятся прямым SQL из приложения: {} доступны на UPDATE \
                роли {} (проверено на {}). Это законно в разработке и опасно в бою: \
                «кто уронил цену» и «куда делась деталь» перестают быть \
                доказательством. Включается разделение ролей — ops/create-roles.sh \
                и переменные DB_USER, APP_DDL_*, APP_RUNTIME_ROLE""",
                status.writable(), status.role(), status.schema());
    }
}
