package ru.partsflow.platform.session;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Журнал сессий организации: входы, выходы, устройства, неудачные попытки.
 *
 * <p><b>Кому открыт.</b> Владельцу и роли «Ревизор» — тот же список, что
 * у журнала изменений, и по той же причине: «все действия внутри одной
 * организации должны быть видны владельцу и тем, кому владелец разрешил это»
 * (решение владельца продукта от 8 сентября 2026, задача 0043), а доступ даёт
 * роль. Два журнала — две вкладки одного экрана, и разные права у них
 * означали бы, что вкладка есть, а содержимого нет.
 *
 * <p><b>Чего здесь нет и почему.</b> Сотрудник своих сессий не видит: «видит
 * ли сотрудник свои сессии сам, или только владелец и ревизор» — вопрос,
 * на который владелец продукта не отвечал (docs/sessions.md, §10). Показать
 * ему чужой механизм доступа проще, чем отобрать потом.
 */
@RestController
@RequestMapping("/api/organization/sessions")
public class OrganizationSessionController {

    private static final String READS_JOURNAL = "hasAnyRole('OWNER','AUDITOR')";

    private final SessionJournalService sessions;

    public OrganizationSessionController(SessionJournalService sessions) {
        this.sessions = sessions;
    }

    /**
     * Страница журнала сессий.
     *
     * @param member  имя сотрудника либо введённый при отказе логин
     * @param outcome {@code SUCCESS} — только входы, {@code FAILED} — только
     *                отказы, пусто — всё подряд
     * @param from    начало периода, метка времени ISO
     * @param to      конец периода, метка времени ISO
     */
    @GetMapping
    @PreAuthorize(READS_JOURNAL)
    public SessionJournalService.Journal journal(
            @RequestParam(required = false) String member,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int size) {

        return sessions.page(member, outcome, parseInstant(from), parseInstant(to),
                Math.min(Math.max(size, 1), 200));
    }

    /** Значения для отбора: кто вообще заходил. */
    @GetMapping("/values")
    @PreAuthorize(READS_JOURNAL)
    public List<String> values(@RequestParam String column) {
        return sessions.values(column);
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
