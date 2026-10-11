package ru.partsflow.platform.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.support.PostgresTestBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Очередь писем (задача 0192): отказ оператора не теряет письмо, повтор не
 * создаёт второго, «отправлено» не выдаётся за «доставлено».
 *
 * <p>Оператор подменён рукописной заглушкой (Mockito на этой JDK не работает):
 * она отвечает по сценарию теста и запоминает, что ей отправили. Настоящий
 * Unisender Go этим тестом не проверяется — ключа нет; форма его запроса
 * сверяется в {@code UnisenderGoTransportTest}.
 */
@SpringBootTest(properties = {
        "app.mail.max-attempts=3",
        "app.mail.retry-delay=0s",
        "app.mail.webhook-secret=hook-secret"})
@AutoConfigureMockMvc
class MailServiceTest extends PostgresTestBase {

    /** Заглушка оператора: ответы по очереди, а когда очередь пуста — «принято». */
    static class ScriptedTransport implements MailTransport {
        final Queue<Result> script = new LinkedList<>();
        final List<Outgoing> sent = Collections.synchronizedList(new ArrayList<>());
        int jobs;

        @Override
        public synchronized Result send(Outgoing m) {
            sent.add(m);
            Result next = script.poll();
            return next != null ? next : Result.accepted("job-" + (++jobs) + "-" + UUID.randomUUID());
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedTransport scriptedTransport() {
            return new ScriptedTransport();
        }
    }

    @Autowired
    private MailService mail;
    @Autowired
    private ScriptedTransport operator;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void clean() {
        // Таблица общая у всех контекстов и ни одной тестовой схеме не принадлежит.
        jdbc.update("DELETE FROM public.mail_message");
        jdbc.update("DELETE FROM public.mail_event_pending");
        operator.script.clear();
        operator.sent.clear();
    }

    private static String key() {
        return "confirm-email:" + UUID.randomUUID();
    }

    private static MailTemplates.Rendered letter() {
        return MailTemplates.confirmEmail("https://example.test/confirm?t=abc&x=1");
    }

    @Test
    @DisplayName("Принятое оператором письмо — SENT, и до отчёта оператора оно не DELIVERED")
    void acceptedIsSentNotDelivered() {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());

        mail.dispatchDue();

        assertThat(operator.sent).hasSize(1);
        assertThat(operator.sent.get(0).to()).isEqualTo("owner@example.test");
        assertThat(operator.sent.get(0).html()).contains("&amp;x=1");
        assertThat(mail.status(k).orElseThrow().status()).isEqualTo(MailService.SENT);
    }

    @Test
    @DisplayName("Отчёт оператора «delivered» переводит SENT в DELIVERED; чужой номер — ничего")
    void deliveryReportMakesItDelivered() throws Exception {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        mail.dispatchDue();
        String job = jdbc.queryForObject(
                "SELECT provider_id FROM public.mail_message WHERE idempotency_key = ?",
                String.class, k);

        String report = """
                {"auth":"x","events_by_user":[{"user_id":1,"events":[
                  {"event_name":"transactional_email_status",
                   "event_data":{"job_id":"%s","status":"delivered","email":"owner@example.test"}},
                  {"event_name":"transactional_email_status",
                   "event_data":{"job_id":"чужой","status":"delivered"}}]}]}""".formatted(job);

        // Без CSRF-токена и без сессии: у оператора их нет.
        mvc.perform(post("/api/mail/events/hook-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(report))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").value(2))
                .andExpect(jsonPath("$.applied").value(1));

        assertThat(mail.status(k).orElseThrow().status()).isEqualTo(MailService.DELIVERED);
    }

    @Test
    @DisplayName("Жёсткий отказ доставки — BOUNCED с причиной; неверный секрет в адресе — 404")
    void bounceIsRecordedAndWrongSecretIsRefused() throws Exception {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        mail.dispatchDue();
        String job = jdbc.queryForObject(
                "SELECT provider_id FROM public.mail_message WHERE idempotency_key = ?",
                String.class, k);
        String report = """
                {"events_by_user":[{"events":[{"event_name":"transactional_email_status",
                  "event_data":{"job_id":"%s","status":"hard_bounced"}}]}]}""".formatted(job);

        mvc.perform(post("/api/mail/events/not-the-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(report))
                .andExpect(status().isNotFound());
        assertThat(mail.status(k).orElseThrow().status()).isEqualTo(MailService.SENT);

        mvc.perform(post("/api/mail/events/hook-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(report))
                .andExpect(status().isOk());
        MailService.MailStatus after = mail.status(k).orElseThrow();
        assertThat(after.status()).isEqualTo(MailService.BOUNCED);
        assertThat(after.lastError()).contains("hard_bounced");
    }

    @Test
    @DisplayName("Временный отказ оператора не теряет письмо: оно ждёт и уходит со второй попытки")
    void temporaryFailureKeepsTheLetter() {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        operator.script.add(MailTransport.Result.temporary("оператор ответил 503"));

        mail.dispatchDue();
        MailService.MailStatus waiting = mail.status(k).orElseThrow();
        assertThat(waiting.status()).isEqualTo(MailService.QUEUED);
        assertThat(waiting.lastError()).contains("503");

        mail.dispatchDue();
        assertThat(mail.status(k).orElseThrow().status()).isEqualTo(MailService.SENT);
        assertThat(operator.sent).hasSize(2);
        // Ушло то же самое письмо, а не перерисованное заново.
        assertThat(operator.sent.get(1).html()).isEqualTo(operator.sent.get(0).html());
    }

    @Test
    @DisplayName("Попытки кончились — FAILED со словами, а не вечный повтор")
    void attemptsRunOut() {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        for (int i = 0; i < 3; i++) {
            operator.script.add(MailTransport.Result.temporary("сеть"));
        }

        for (int i = 0; i < 5; i++) {
            mail.dispatchDue();
        }

        MailService.MailStatus s = mail.status(k).orElseThrow();
        assertThat(s.status()).isEqualTo(MailService.FAILED);
        assertThat(s.lastError()).contains("попытки кончились").contains("сеть");
        assertThat(operator.sent).hasSize(3);
    }

    @Test
    @DisplayName("Отказ по существу — FAILED сразу, без повторов; запись о человеке на месте")
    void rejectionIsFinal() {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        operator.script.add(MailTransport.Result.rejected("адрес не принят"));

        mail.dispatchDue();
        mail.dispatchDue();

        MailService.MailStatus s = mail.status(k).orElseThrow();
        assertThat(s.status()).isEqualTo(MailService.FAILED);
        assertThat(s.lastError()).contains("адрес не принят");
        assertThat(operator.sent).hasSize(1);
    }

    @Test
    @DisplayName("Сбой самой заглушки не роняет проход: письмо ждёт, как при временном отказе")
    void transportExceptionIsTemporary() {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        MailTransport broken = m -> { throw new IllegalStateException("boom"); };
        MailService withBroken = new MailService(jdbc, broken,
                new MailProperties(null, null, null, null, null, null, 3,
                        java.time.Duration.ZERO, null, null, null));

        withBroken.dispatchDue();

        assertThat(mail.status(k).orElseThrow().status()).isEqualTo(MailService.QUEUED);
    }

    @Test
    @DisplayName("Отчёт «delivered» пришёл по HTTP, пока запрос к оператору ещё шёл: письмо DELIVERED, а не SENT")
    void deliveryReportBeforeResultIsRecordedIsNotLost() {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        // Боевой порядок: оператор уже ответил на отправку и сразу прислал отчёт,
        // а мы ещё не успели записать номер задания в строку. Номера в строке
        // нет — отчёт приходит тем же HTTP-путём, что и настоящий.
        MailService racing = new MailService(jdbc, m -> {
            try {
                mvc.perform(post("/api/mail/events/hook-secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"events_by_user":[{"events":[{"event_name":"transactional_email_status",
                                          "event_data":{"job_id":"job-early","status":"delivered"}}]}]}"""))
                        .andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return MailTransport.Result.accepted("job-early");
        }, new MailProperties(null, null, null, null, null, null, 3,
                java.time.Duration.ZERO, null, null, null));

        racing.dispatchDue();

        assertThat(mail.status(k).orElseThrow().status()).isEqualTo(MailService.DELIVERED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.mail_event_pending", Integer.class))
                .as("ожидавший отчёт разобран и убран").isZero();
    }

    @Test
    @DisplayName("Отчёт по неизвестному номеру ждёт в буфере и письма не трогает; сутки спустя убирается")
    void unknownJobWaitsAndIsCleanedUp() throws Exception {
        String k = key();
        mail.enqueue(k, "owner@example.test", letter());
        mvc.perform(post("/api/mail/events/hook-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"events_by_user":[{"events":[{"event_name":"transactional_email_status",
                                  "event_data":{"job_id":"чужой-номер","status":"delivered"}}]}]}"""))
                .andExpect(jsonPath("$.applied").value(0));

        assertThat(mail.status(k).orElseThrow().status()).isEqualTo(MailService.QUEUED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.mail_event_pending", Integer.class))
                .isEqualTo(1);

        jdbc.update("UPDATE public.mail_event_pending SET received_at = now() - interval '2 days'");
        mail.dispatchDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.mail_event_pending", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("Повтор с тем же ключом письма не добавляет и отдаёт состояние первого")
    void repeatDoesNotCreateSecondLetter() {
        String k = key();
        MailService.MailStatus first = mail.enqueue(k, "owner@example.test", letter());
        mail.dispatchDue();
        MailService.MailStatus again = mail.enqueue(k, "owner@example.test", letter());

        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.status()).isEqualTo(MailService.SENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.mail_message", Integer.class))
                .isEqualTo(1);
        mail.dispatchDue();
        assertThat(operator.sent).hasSize(1);
    }

    @Test
    @DisplayName("Одновременный повтор: одна строка, ни одной ошибки целостности")
    void concurrentRepeatIsHarmless() throws Exception {
        String k = key();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MailService.MailStatus>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return mail.enqueue(k, "owner@example.test", letter());
            }));
        }
        go.countDown();
        int replayed = 0;
        for (Future<MailService.MailStatus> f : results) {
            if (f.get().replayed()) {
                replayed++;
            }
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.mail_message", Integer.class))
                .isEqualTo(1);
        assertThat(replayed).isEqualTo(n - 1);
    }

    @Test
    @DisplayName("Неверный адрес получателя — отказ словами, в очередь не попадает")
    void badAddressIsRefusedBeforeQueueing() {
        assertThatThrownBy(() -> mail.enqueue(key(), "не-адрес", letter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Адрес почты");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.mail_message", Integer.class))
                .isZero();
    }
}
