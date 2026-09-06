package ru.partsflow.sales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Источники платежей: способы приёма денег — «ККМ», «Карта Сбер», «В долг».
 *
 * <p>Таблица {@code payment_source} заведена ещё в {@code tenant/011-sales.sql}
 * вместе с типом и признаком архивности, {@code paymentSourceId} принимают
 * оплата, возврат и операции по лицевому счёту — а заводить сам источник или
 * снимать его с работы было нечем, кроме SQL. Ровно та ловушка из корневого
 * {@code CLAUDE.md}: поле есть в схеме и в контроллерах, а человеку недоступно.
 *
 * <p>Пишется через {@link JdbcTemplate}, а не через JPA-сущность, — тем же
 * приёмом, каким заведены филиалы, склады и кабинеты площадок
 * ({@code OrganizationService}, {@code MarketplaceAccountService}):
 * это справочник конфигурации, а не объект с инвариантом, который стоило бы
 * защищать методами сущности.
 */
@Service
public class PaymentSourceService {

    /**
     * Белый список типа, хотя в колонке и стоит {@code CHECK}: отказ базы
     * приезжает пятисоткой без объяснения, а свой список даёт понятный 400
     * до похода в базу.
     */
    static final Set<String> TYPES =
            Set.of("CASH", "BANK_ACCOUNT", "ACQUIRING", "CREDIT", "MARKETPLACE");

    private final JdbcTemplate jdbc;

    public PaymentSourceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<PaymentSourceView> list() {
        return jdbc.query("""
                SELECT id, name, source_type, is_archived
                  FROM payment_source
                 ORDER BY name""",
                (rs, i) -> new PaymentSourceView(
                        rs.getLong("id"), rs.getString("name"),
                        rs.getString("source_type"), rs.getBoolean("is_archived")));
    }

    /**
     * Заводит источник.
     *
     * <p>Название проверяется чтением ради текста: «Источник «ККМ» уже
     * заведён», а не «Операция нарушает целостность данных» — сторожем
     * остаётся уникальный индекс {@code payment_source_uk}. Проверка чтением
     * ловит обычный повтор и пропускает одновременный; на него отвечает
     * контроллер, поймав нарушение индекса и вернув тот же текст — сообщение
     * фиксировано и не зависит от того, кто именно название занял, поэтому
     * перечитывать конфликтующую строку не нужно.
     */
    @Transactional
    public PaymentSourceView create(String name, String sourceType) {
        String trimmed = requireName(name);
        String type = normalizeType(sourceType);

        Integer taken = jdbc.queryForObject(
                "SELECT count(*) FROM payment_source WHERE name = ?", Integer.class, trimmed);
        if (taken != null && taken > 0) {
            throw new IllegalArgumentException(duplicateMessage(trimmed));
        }

        Long id = jdbc.queryForObject("""
                INSERT INTO payment_source (name, source_type) VALUES (?, ?)
                RETURNING id""", Long.class, trimmed, type);
        return new PaymentSourceView(id, trimmed, type, false);
    }

    @Transactional
    public PaymentSourceView archive(Long id) {
        return setArchived(id, true);
    }

    @Transactional
    public PaymentSourceView unarchive(Long id) {
        return setArchived(id, false);
    }

    private PaymentSourceView setArchived(Long id, boolean archived) {
        int updated = jdbc.update(
                "UPDATE payment_source SET is_archived = ? WHERE id = ?", archived, id);
        if (updated == 0) {
            throw new IllegalArgumentException("Источник платежа не найден: " + id);
        }
        return list().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название источника обязательно");
        }
        return name.strip();
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Неизвестный тип источника: " + type);
        }
        return type;
    }

    static String duplicateMessage(String name) {
        return "Источник «%s» уже заведён".formatted(name);
    }

    public record PaymentSourceView(Long id, String name, String sourceType, boolean archived) {
    }
}
