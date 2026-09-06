package ru.partsflow.platform.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Говорит при старте, что код рассчитывает на схему новее накатанной.
 *
 * <p>Поймано живьём 6 сентября 2026. Схемы девяти арендаторов отстали
 * на одну-две миграции — колонки {@code feed_file_name} в них не было, —
 * и прайс Дрома отдавался пустым файлом. Для площадки пустой прайс не ошибка,
 * а команда «товаров нет»: она снимает все объявления вместе с накопленными
 * просмотрами, за которые клиент платит. Приложение при этом поднялось молча,
 * а знание у него было: {@code status(false)} отвечает за один запрос.
 *
 * <p><b>Отметка в реестре, а не глубокий опрос.</b> Глубокая проверка ходит
 * в каждую схему через Liquibase; при пятистах арендаторах это минуты на старте
 * — то есть ячейка недоступна ради диагностики. Отметка врёт только там, где
 * в схему лазили руками, а поднять тревогу её хватает.
 *
 * <p><b>Уровень WARN, запуск не валится</b> — тот же довод, что
 * у {@link JournalProtectionCheck}: разработчик работает как раз между
 * накатами, и падение на старте означало бы, что при отставшей схеме локально
 * ничего не поднять. В бою накат схем — шаг развёртывания
 * ({@code ops/migrate-tenants.sh}), и по замыслу отставания быть не должно;
 * эта строка нужна ровно для тех случаев, когда шаг пропустили, он упал
 * на одном арендаторе из пятисот или код выложили раньше наката.
 *
 * <p><b>Отставших ноль — не пишем ничего.</b> Строка «всё в порядке» на старте
 * теряется среди прочих и приучает не читать те, что рядом.
 *
 * <p>Транзакция тут не нужна и не помогла бы: запрос идёт в {@code public},
 * к реестру, а не в схему арендатора — {@code search_path} ни при чём.
 */
@Component
public class SchemaVersionCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaVersionCheck.class);

    /**
     * Сколько имён называть. Девять схем в строку — это строка, которую
     * не читают; первых трёх хватает, чтобы понять, кого смотреть, а число
     * остальных — чтобы понять масштаб.
     */
    private static final int NAMED = 3;

    private final TenantMigrations migrations;

    public SchemaVersionCheck(TenantMigrations migrations) {
        this.migrations = migrations;
    }

    /**
     * Всё тело здесь, а не в отдельном методе, и тест зовёт именно {@code run}.
     * Пока проверка жила в своём {@code report()}, тест доказывал её, а не связку
     * со стартом: опустошённый {@code run()} оставлял все проверки зелёными —
     * то есть отвались связка, никто бы не заметил. Через подъём контекста
     * доказать нельзя (реестр надо портить до старта), а свойство-маркер ради
     * этого — ещё один кэшированный контекст Spring и ещё один пул соединений.
     */
    @Override
    public void run(ApplicationArguments args) {
        TenantMigrations.Status status;
        try {
            status = migrations.status(false);
        } catch (RuntimeException e) {
            // Реестра ещё нет — ячейка поднимается впервые, сверять не с чем.
            // Молчим по той же причине, по какой не валим запуск. Порядок
            // с CatalogMigrations, которая реестр и создаёт, не задан — и это
            // ничего не меняет: на ячейке без реестра нет и арендаторов,
            // а там, где они есть, таблица существует задолго до старта.
            log.debug("Отставание схем арендаторов не проверено", e);
            return;
        }

        List<TenantMigrations.TenantView> behind = status.behind();
        if (behind.isEmpty()) {
            return;
        }

        log.warn("Схемы арендаторов отстали от кода: {} из {} ({}). "
                        + "Ожидается {}. Накатите: ops/migrate-tenants.sh",
                behind.size(), status.tenants(), names(behind), status.expectedVersion());
    }

    private static String names(List<TenantMigrations.TenantView> behind) {
        String first = behind.stream()
                .limit(NAMED)
                .map(TenantMigrations.TenantView::schema)
                .collect(Collectors.joining(", "));
        int rest = behind.size() - Math.min(NAMED, behind.size());
        return rest == 0 ? first : first + " и ещё " + rest;
    }
}
