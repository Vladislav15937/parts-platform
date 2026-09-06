package ru.partsflow.sales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Управление справочником источников сделок — экран «Настройки».
 *
 * <p>Таблица {@code deal_source} и чтение активных строк
 * (см. {@link DealSourceRepository}) существовали с самого начала —
 * продавец выбирает источник при каждой продаже, — а завести новый
 * источник или снять лишний с работы можно было только SQL. Тот же пробел,
 * что и у источников платежей, только тут справочник без типа.
 *
 * <p>Отдельный сервис на {@link JdbcTemplate}, а не правка
 * {@link DealSourceRepository}: тот отдаёт JPA-сущность для чтения
 * в сделке, а здесь нужны запись, архивация и текст на повтор имени —
 * ровно то же самое разделение, что у {@code MarketplaceAccountService}
 * и {@code OrganizationService}.
 */
@Service
public class DealSourceService {

    private final JdbcTemplate jdbc;

    public DealSourceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DealSourceEntryView> list() {
        return jdbc.query("""
                SELECT id, name, is_archived
                  FROM deal_source
                 ORDER BY name""",
                (rs, i) -> new DealSourceEntryView(
                        rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_archived")));
    }

    /**
     * Заводит источник.
     *
     * <p>Тот же приём, что у {@link PaymentSourceService#create}: проверка
     * чтением ради текста «Источник «Авито» уже заведён», сторож —
     * уникальный индекс {@code deal_source_uk}, а одновременный повтор
     * ловит контроллер по нарушению индекса тем же фиксированным текстом.
     */
    @Transactional
    public DealSourceEntryView create(String name) {
        String trimmed = requireName(name);

        Integer taken = jdbc.queryForObject(
                "SELECT count(*) FROM deal_source WHERE name = ?", Integer.class, trimmed);
        if (taken != null && taken > 0) {
            throw new IllegalArgumentException(duplicateMessage(trimmed));
        }

        Long id = jdbc.queryForObject(
                "INSERT INTO deal_source (name) VALUES (?) RETURNING id", Long.class, trimmed);
        return new DealSourceEntryView(id, trimmed, false);
    }

    @Transactional
    public DealSourceEntryView archive(Long id) {
        return setArchived(id, true);
    }

    @Transactional
    public DealSourceEntryView unarchive(Long id) {
        return setArchived(id, false);
    }

    private DealSourceEntryView setArchived(Long id, boolean archived) {
        int updated = jdbc.update(
                "UPDATE deal_source SET is_archived = ? WHERE id = ?", archived, id);
        if (updated == 0) {
            // Без номера строки: см. ту же правку в PaymentSourceService.
            throw new IllegalArgumentException(
                    "Источник сделки не найден — обновите страницу, список устарел");
        }
        return list().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название источника обязательно");
        }
        return name.strip();
    }

    static String duplicateMessage(String name) {
        return "Источник «%s» уже заведён".formatted(name);
    }

    public record DealSourceEntryView(Long id, String name, boolean archived) {
    }
}
