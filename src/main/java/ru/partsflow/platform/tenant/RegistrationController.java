package ru.partsflow.platform.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Регистрация компании клиентом без человека в цикле (задача 0184).
 *
 * <p>Открыта без входа и без секрета — в этом весь смысл, и поэтому это
 * <b>не</b> часть управляющего контура {@code /api/provisioning}: тот остаётся
 * закрытым секретом и сетью, а его секрет эта ручка не обходит и не использует
 * ни для чего. Сторож настроек и признак «контур включён» её не касаются.
 *
 * <p>CSRF здесь отключён по тому же соображению, что у контура: подделывать
 * нечего — запрос не использует cookie, сессии не создаёт и ничего не делает
 * от имени вошедшего. Тот, кто хочет завести компанию, может прислать этот
 * запрос напрямую, и от подделки браузером ему ничего не добавляется.
 *
 * <p>Тело разбирается руками ({@link RegistrationService}), а не аннотациями
 * {@code @NotBlank}: отказ валидатора Spring приезжает английским
 * «must not be blank» с именем поля, а эту форму читает владелец разборки.
 *
 * <p>Эндпоинт имеет экран — {@code RegistrationScreen}; эндпоинт без экрана
 * здесь не заводят ({@code tools/endpoint-coverage.py}).
 */
@RestController
@RequestMapping("/api/registration")
public class RegistrationController {

    private final RegistrationService service;
    private final RegistrationChallenge challenge;
    private final RegistrationLimits limits;

    public RegistrationController(RegistrationService service, RegistrationChallenge challenge,
                                  RegistrationLimits limits) {
        this.service = service;
        this.challenge = challenge;
        this.limits = limits;
    }

    /**
     * Задание браузеру. Если регистрация закрыта потолком, отвечает отказом сразу:
     * человеку незачем заполнять форму и считать проверку, чтобы услышать «закрыто»
     * в конце.
     */
    @GetMapping("/challenge")
    public RegistrationChallenge.Issued challenge() {
        limits.requireOpen();
        return challenge.issue();
    }

    @PostMapping("/companies")
    public ResponseEntity<RegistrationService.Registered> register(
            @RequestBody(required = false) RegistrationService.Form form,
            HttpServletRequest http) {
        RegistrationService.Registered registered =
                service.register(form, ProvisioningLimits.addressOf(http));
        // 201 — завели, 200 — это результат прежнего нажатия: повтор не создаёт
        // ничего, и говорить «создано» дважды означало бы соврать.
        return ResponseEntity.status(registered.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(registered);
    }
}
