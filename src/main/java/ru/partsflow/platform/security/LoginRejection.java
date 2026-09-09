package ru.partsflow.platform.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

/**
 * Отказ во входе, о котором есть что записать в журнал.
 *
 * <p><b>Зачем это вообще есть.</b> «Писать неудачные попытки входа: пять
 * отказов подряд ценнее ста успешных входов» (ответы владельца продукта
 * от 9 сентября 2026, задача 0043). Записать их можно только в схему той
 * компании, куда ломились, а знает эту схему один
 * {@link MemberAuthenticationProvider}: вход происходит до того, как арендатор
 * известен, и превращает код компании в схему именно он.
 *
 * <p><b>Наружу при этом ничего не меняется.</b> Классы остаются подтипами
 * {@code BadCredentialsException} и {@code DisabledException}, ответ на вход
 * по-прежнему один и тот же 401 с пустым телом: «нет компании», «нет логина»
 * и «неверный пароль» обязаны быть неразличимы, иначе форма входа работает
 * справочником действующих компаний и сотрудников, а первые десять клиентов —
 * конкуренты из одного города. Различает их только журнал, и только внутри
 * той организации, куда стучались.
 *
 * <p><b>Отказ без схемы сюда не попадает.</b> Неизвестный код компании — это
 * попытка, не принадлежащая ни одной организации: записывать её некуда
 * и показывать некому.
 */
public interface LoginRejection {

    /** Схема арендатора, в журнал которого пишется попытка. */
    String tenantSchema();

    /** Сотрудник, если такой логин нашёлся; иначе {@code null}. */
    Long memberId();

    /** Роль сотрудника на момент попытки; {@code null} у неизвестного логина. */
    String memberRole();

    /** {@code BAD_CREDENTIALS} или {@code DISABLED}. */
    String reason();

    /** Неверный пароль либо неизвестный логин в существующей компании. */
    class BadCredentials extends BadCredentialsException implements LoginRejection {

        private final String tenantSchema;
        private final transient Long memberId;
        private final String memberRole;

        public BadCredentials(String message, String tenantSchema, Long memberId,
                              String memberRole) {
            super(message);
            this.tenantSchema = tenantSchema;
            this.memberId = memberId;
            this.memberRole = memberRole;
        }

        @Override
        public String tenantSchema() {
            return tenantSchema;
        }

        @Override
        public Long memberId() {
            return memberId;
        }

        @Override
        public String memberRole() {
            return memberRole;
        }

        @Override
        public String reason() {
            return "BAD_CREDENTIALS";
        }
    }

    /**
     * Выключенная учётная запись.
     *
     * <p>Владельцу своей организации это видно отдельной причиной, и не зря:
     * «уволенный ломится в кабинет» — другой разговор, чем «подбирают пароль».
     */
    class Disabled extends DisabledException implements LoginRejection {

        private final String tenantSchema;
        private final transient Long memberId;
        private final String memberRole;

        public Disabled(String message, String tenantSchema, Long memberId, String memberRole) {
            super(message);
            this.tenantSchema = tenantSchema;
            this.memberId = memberId;
            this.memberRole = memberRole;
        }

        @Override
        public String tenantSchema() {
            return tenantSchema;
        }

        @Override
        public Long memberId() {
            return memberId;
        }

        @Override
        public String memberRole() {
            return memberRole;
        }

        @Override
        public String reason() {
            return "DISABLED";
        }
    }
}
