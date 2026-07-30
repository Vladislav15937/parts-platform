package ru.partsflow.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.platform.tenant.TenantContext;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Создание первого владельца арендатора.
 *
 * <p>Разрывает замкнутый круг: сотрудников создаёт владелец, а первого владельца
 * создать некому.
 *
 * <p><b>Три условия, и все обязательны.</b> Секрет настроен в конфигурации;
 * секрет в запросе с ним совпал; у арендатора нет ни одной учётной записи.
 * Последнее закрывает лазейку навсегда после первого успеха — свежий арендатор
 * иначе достался бы тому, кто первым угадает его код.
 *
 * <p>Без настроенного секрета механизм выключен целиком. Это важнее удобства:
 * забытый включённым и оставленный со значением по умолчанию, он отдаёт любую
 * ещё не заполненную компанию первому желающему.
 *
 * <p>Правильное место для этой операции — провижининг арендатора, который
 * создаёт схему и сразу владельца. Провижининга в коде нет, схемы создаются
 * скриптом; когда появится, этот класс уйдёт вместе с эндпоинтом.
 */
@Component
public class MemberBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MemberBootstrap.class);

    private final JdbcTemplate jdbc;
    private final MemberService members;
    private final String configuredToken;

    public MemberBootstrap(JdbcTemplate jdbc,
                           MemberService members,
                           @Value("${app.bootstrap-token:}") String configuredToken) {
        this.jdbc = jdbc;
        this.members = members;
        this.configuredToken = configuredToken;
    }

    /**
     * @return созданный владелец либо пусто, если хоть одно условие не выполнено.
     *         Причина наружу не сообщается: «неверный секрет» и «компания уже
     *         занята» — подсказки тому, кто перебирает коды компаний
     */
    public Optional<MemberService.Member> createFirstOwner(
            MemberController.BootstrapRequest request) {

        if (configuredToken == null || configuredToken.isBlank()) {
            log.warn("Попытка создать первого владельца при выключенном механизме: "
                    + "app.bootstrap-token не задан");
            return Optional.empty();
        }
        if (!constantTimeEquals(configuredToken, request.token())) {
            log.warn("Попытка создать первого владельца с неверным секретом, компания {}",
                    request.company());
            return Optional.empty();
        }

        String schema = schemaOf(request.company());
        if (schema == null) {
            return Optional.empty();
        }

        // Контекст выставляется ДО транзакции: search_path ставит провайдер
        // соединений при её открытии, и установка контекста внутри транзакции
        // опоздала бы — соединение уже указывало бы на public.
        try {
            TenantContext.set(schema);
            Optional<MemberService.Member> owner = members.createFirstOwnerIfEmpty(
                    request.login(), request.password(), request.displayName());

            if (owner.isEmpty()) {
                log.warn("Попытка создать первого владельца у заполненной компании {}",
                        request.company());
            } else {
                log.info("Создан первый владелец {} у компании {}",
                        owner.get().login(), request.company());
            }
            return owner;
        } finally {
            TenantContext.clear();
        }
    }

    private String schemaOf(String companyCode) {
        try {
            return jdbc.queryForObject("""
                    SELECT schema_name FROM public.tenant_registry
                     WHERE code = lower(btrim(?)) AND status = 'ACTIVE'""",
                    String.class, companyCode);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Сравнение за постоянное время: обычное {@code equals} выходит на первом
     * несовпавшем символе, и по времени ответа секрет подбирается посимвольно.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
