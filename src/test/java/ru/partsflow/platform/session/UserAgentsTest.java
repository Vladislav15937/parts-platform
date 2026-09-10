package ru.partsflow.platform.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор строки браузера — на показе.
 *
 * <p>Без Spring: разбор строки контекста не требует, а каждый лишний
 * {@code @SpringBootTest} — это ещё один поднятый Spring и ещё один пул
 * соединений.
 */
class UserAgentsTest {

    /**
     * Порядок проверок в разборе обратный привычному, и это не вкусовщина.
     *
     * <p>Все производные Chromium пишут в строку и {@code Chrome}, и себя.
     * Спросив про Chrome первым, мы назовём Chrome'ом все четыре — и владелец,
     * работающий в Яндекс.Браузере, не узнает собственный вход, то есть
     * журнал перестанет отвечать на тот единственный вопрос, ради которого
     * устройство и показывают.
     */
    @Test
    @DisplayName("Производные Chromium называются собой, а не Chrome")
    void chromiumForksAreNotChrome() {
        assertThat(UserAgents.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 YaBrowser/24.7.0.0"
                + " Safari/537.36")).isEqualTo("Яндекс.Браузер · Windows");

        assertThat(UserAgents.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
                + " Edg/127.0.0.0")).isEqualTo("Edge · Windows");

        assertThat(UserAgents.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"))
                .isEqualTo("Chrome · Windows");
    }

    /**
     * Телефон отличается от настольной машины, и это первое, что смотрят:
     * приёмщик работает с телефона, владелец — из кабинета.
     */
    @Test
    @DisplayName("Телефон виден телефоном, а не Linux'ом")
    void androidIsNotLinux() {
        assertThat(UserAgents.describe("Mozilla/5.0 (Linux; Android 14; SM-A536E)"
                + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"))
                .isEqualTo("Chrome · Android");

        assertThat(UserAgents.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X)"
                + " AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148"
                + " Safari/604.1")).isEqualTo("Safari · iOS");
    }

    /**
     * Не разобрали — так и говорим.
     *
     * <p>Придуманный «Chrome» на незнакомой строке хуже честного незнания:
     * по устройству узнают чужой вход, и подделка тут стоит дороже пустоты.
     * Сырая строка при этом остаётся в базе и видна рядом.
     */
    @Test
    @DisplayName("Незнакомая строка не выдаётся за известный браузер")
    void unknownStaysUnknown() {
        assertThat(UserAgents.describe("curl/8.4.0")).isNull();
        assertThat(UserAgents.describe(null)).isNull();
        assertThat(UserAgents.describe("  ")).isNull();
    }
}
