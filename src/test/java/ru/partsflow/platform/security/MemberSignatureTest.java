package ru.partsflow.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Подпись автора записи (задача 0373): имя, логин и видимый номер.
 *
 * <p>Сквозной путь через историю карточки стережёт {@code BulkEditHistoryTest};
 * здесь — края, которых в той фикстуре нет: сотрудник без логина
 * (участник администратора сервиса заводится без него) и пустое имя.
 */
class MemberSignatureTest {

    @Test
    @DisplayName("Имя, логин и номер — в порядке Bazon: имя, затем «ID»")
    void nameLoginAndNumber() {
        assertThat(MemberSignature.of("Пётр Массов", "vladelec", 3L))
                .isEqualTo("Пётр Массов (vladelec, ID 3)");
    }

    @Test
    @DisplayName("Без логина — имя и номер, без пустой запятой")
    void noLogin() {
        assertThat(MemberSignature.of("Администратор сервиса", null, 12L))
                .isEqualTo("Администратор сервиса (ID 12)");
    }

    @Test
    @DisplayName("Без имени — логин вместо имени, и не дважды")
    void noName() {
        assertThat(MemberSignature.of(null, "prodavec", 5L))
                .isEqualTo("prodavec (ID 5)");
    }

    @Test
    @DisplayName("Сотрудника нет — прочерк, а не выдуманный автор")
    void noMember() {
        assertThat(MemberSignature.of(null, null, null)).isNull();
    }
}
