package ru.partsflow.platform.tenant;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Потолок числа компаний в ячейке (задача 0184, решение владельца: 200).
 *
 * <p>Потолок здесь нулевой, и это не подгонка: проверяется само правило
 * «достигнут — честный отказ», а чтобы упереться в двести, тест завёл бы
 * двести схем, то есть двести накатов Liquibase. Нуль — это «ячейка уже полна»
 * при любом числе схем в базе, и он же выключатель регистрации.
 *
 * <p>Свой класс (а значит, свой контекст Spring), потому что потолок —
 * настройка, а другой набор свойств в одном классе с открытой регистрацией
 * не поднять.
 */
@SpringBootTest(properties = {
        "app.registration-max-tenants=0",
        "app.registration-pow-bits=8"})
@AutoConfigureMockMvc
class RegistrationClosedTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RegistrationChallenge challenge;

    @Autowired
    private MeterRegistry metrics;

    @Test
    @DisplayName("На потолке форма говорит «регистрация временно закрыта» — 409 со словами, а не 500")
    void atTheCeilingTheAnswerIsAClearRefusal() throws Exception {
        // Форма узнаёт об этом при загрузке, а не после заполнения и вычисления.
        MvcResult closed = mvc.perform(get("/api/registration/challenge"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(bodyOf(closed)).contains("Регистрация временно закрыта");

        // И прямой запрос, минуя форму, — тоже: задание берём у бина, как взял
        // бы тот, кто вычислил его заранее.
        RegistrationChallenge.Issued issued = challenge.issue();
        String solution = solve(issued.challenge(), issued.bits());
        long before = count();
        double rejectedBefore = rejectedAsClosed();

        MvcResult refused = mvc.perform(post("/api/registration/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","challenge":"%s","solution":"%s",
                                 "companyName":"Закрыто","companyCode":"zakryto%s",
                                 "email":"zakryto%s@example.ru","ownerLogin":"hozyain",
                                 "ownerPassword":"пароль-8симв"}"""
                                .formatted(UUID.randomUUID(), issued.challenge(), solution,
                                        suffix(), suffix())))
                .andExpect(status().isConflict()).andReturn();

        assertThat(bodyOf(refused)).contains("Регистрация временно закрыта");
        assertThat(count()).as("на потолке компания не заводится").isEqualTo(before);
        // Отказ оставляет след: «регистрация закрылась» — то, о чём владелец
        // обязан узнавать, а не догадываться по жалобам.
        assertThat(rejectedAsClosed()).isEqualTo(rejectedBefore + 1);
    }

    private double rejectedAsClosed() {
        var counter = metrics.find("partsflow.registration.rejected")
                .tag("reason", RegistrationLimits.REASON_CLOSED).counter();
        return counter == null ? 0 : counter.count();
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM public.tenant_registry", Long.class);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String solve(String challenge, int bits) throws Exception {
        for (long n = 0; ; n++) {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((challenge + ":" + n).getBytes(StandardCharsets.UTF_8));
            if (RegistrationChallenge.leadingZeroBits(hash) >= bits) {
                return Long.toString(n);
            }
        }
    }

    private static String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
