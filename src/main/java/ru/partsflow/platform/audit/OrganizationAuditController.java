package ru.partsflow.platform.audit;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Журнал действий организации.
 *
 * <p><b>Кому открыт.</b> Владельцу и роли «Ревизор» — и больше никому.
 * Решение владельца продукта от 8 сентября 2026 (tasks/0043): «все действия
 * внутри одной организации должны быть видны владельцу и тем, кому владелец
 * разрешил это», а на вопрос «роль или именное разрешение» ответ дословно —
 * «роль». Менеджера здесь нет намеренно: журнал заводят затем, чтобы
 * проверять в том числе и тех, кто распоряжается складом и деньгами,
 * и раздавать его вместе с полномочиями значит не раздавать вовсе.
 *
 * <p><b>Почему проверка на методе, а не в {@code SecurityConfig}.</b> Правило
 * читается там, где операция; в конфигурации стоит только свойство роли
 * целиком («Просмотр» ничего не меняет), а не права отдельного раздела.
 */
@RestController
@RequestMapping("/api/organization/audit")
public class OrganizationAuditController {

    private static final String READS_JOURNAL = "hasAnyRole('OWNER','AUDITOR')";

    private final OrganizationAuditService audit;

    public OrganizationAuditController(OrganizationAuditService audit) {
        this.audit = audit;
    }

    /**
     * Страница журнала: кто, что и когда.
     *
     * @param author  имя сотрудника из меню колонки
     * @param kind    вид вещи словом («Товар», «Сделка»)
     * @param field   название поля («Цена»)
     * @param q       поиск по названию вещи, публичному коду и номеру сделки
     * @param from    начало периода, метка времени ISO
     * @param to      конец периода, метка времени ISO
     * @param after   курсор: номер последней показанной записи
     */
    @GetMapping
    @PreAuthorize(READS_JOURNAL)
    public OrganizationAuditService.Journal journal(
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long after,
            @RequestParam(defaultValue = "50") int size) {

        // «Пусто» и «не пусто» приезжают теми же словами, что и на витрине
        // склада, — отдельного параметра под них нет: два способа сказать
        // «пусто» разошлись бы на первой же правке одного из них. Разбирает
        // их сервис, там же, где остальной отбор.
        return audit.page(author, kind, field, q,
                parseInstant(from), parseInstant(to), after, Math.min(Math.max(size, 1), 200));
    }

    /**
     * Значения для меню колонки — тем же механизмом, что на витрине склада
     * и на вкладке колёс. Свой способ отбирать разошёлся бы с первым.
     */
    @GetMapping("/values")
    @PreAuthorize(READS_JOURNAL)
    public List<String> values(@RequestParam String column) {
        return audit.values(column);
    }

    /**
     * Границы периода приходят строкой.
     *
     * <p>Неразобранная дата — ошибка запроса, а не поломка сервера: отвечать
     * на неё пятисоткой значит отправить человека искать сломанный сервер
     * там, где он сам ошибся.
     */
    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notADate) {
            throw new IllegalArgumentException("Неверный формат даты: " + raw);
        }
    }
}
