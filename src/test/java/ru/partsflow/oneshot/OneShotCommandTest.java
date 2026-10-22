package ru.partsflow.oneshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разбор команды разового запуска (задача 0317): без ключа — проверка,
 * правка — только явным {@code --apply}.
 */
class OneShotCommandTest {

    @Test
    @DisplayName("Без ключа — проверка, а не правка")
    void defaultIsDryRun() {
        OneShotCommand.Plan plan = OneShotCommand.planOf(new String[]{"--one-shot=oem-recount"});
        assertThat(plan.apply()).isFalse();
        assertThat(plan.tenant()).isNull();
        assertThat(plan.feedMarks()).as("отметки для площадки — только явно").isFalse();
        assertThat(OneShotCommand.planOf(new String[]{"--one-shot=oem-recount", "--dry-run"}).apply()).isFalse();
    }

    @Test
    @DisplayName("Пересчёт названий (0336) — своя работа, тоже проверкой по умолчанию")
    void titleRecountIsKnown() {
        OneShotCommand.Plan plan = OneShotCommand.planOf(new String[]{"--one-shot=title-recount"});
        assertThat(plan.job()).isEqualTo("title-recount");
        assertThat(plan.apply()).isFalse();
        assertThat(plan.feedMarks()).isFalse();
    }

    @Test
    @DisplayName("--apply, одна схема и отметки для площадки по ключу")
    void keysAreRead() {
        OneShotCommand.Plan plan = OneShotCommand.planOf(new String[]{
                "--one-shot=oem-recount", "--apply", "--tenant=t_000042", "--feed-marks",
                "--spring.datasource.url=jdbc:postgresql://x/y"});
        assertThat(plan.apply()).isTrue();
        assertThat(plan.tenant()).isEqualTo("t_000042");
        assertThat(plan.feedMarks()).isTrue();
    }

    @Test
    @DisplayName("Опечатка в ключе, чужая работа и «и проверка, и правка» — отказ словами")
    void mistakesAreRefused() {
        assertThatThrownBy(() -> OneShotCommand.planOf(new String[]{"--one-shot=oem-recount", "--aply"}))
                .hasMessageContaining("Непонятный аргумент --aply");
        assertThatThrownBy(() -> OneShotCommand.planOf(new String[]{"--one-shot=oem-recount", "--no-feed-marks"}))
                .as("прежний ключ снят: молча принятый, он обещал бы отметки по умолчанию")
                .hasMessageContaining("Непонятный аргумент --no-feed-marks");
        assertThatThrownBy(() -> OneShotCommand.planOf(new String[]{"--one-shot=nothing"}))
                .hasMessageContaining("Неизвестная разовая работа");
        assertThatThrownBy(() -> OneShotCommand.planOf(
                new String[]{"--one-shot=oem-recount", "--apply", "--dry-run"}))
                .hasMessageContaining("выберите одно");
        assertThatThrownBy(() -> OneShotCommand.planOf(new String[]{"--one-shot=oem-recount", "--tenant="}))
                .hasMessageContaining("без схемы");
    }

    @Test
    @DisplayName("Флаг режима узнаётся; обычный запуск приложения — нет")
    void requested() {
        assertThat(OneShotCommand.requested(new String[]{"--one-shot=oem-recount"})).isTrue();
        assertThat(OneShotCommand.requested(new String[]{"--schema-sync"})).isFalse();
        assertThat(OneShotCommand.requested(new String[]{})).isFalse();
    }
}
