package ru.partsflow.platform.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Создание арендатора.
 *
 * <p>Операция управляющего контура, а не клиента: она создаёт схему в базе
 * и заводит владельца. Вошедшего пользователя тут нет и быть не может —
 * арендатор ещё не существует, — поэтому запрос авторизуется секретом
 * из конфигурации.
 *
 * <p><b>Пустой секрет выключает эндпоинт.</b> Верное значение по умолчанию:
 * забытый включённым, он даёт любому желающему заводить схемы в базе, пока
 * не кончится диск. Тот же приём, что у ключа шифрования и у прежнего
 * {@code bootstrap-token}, который этот эндпоинт и заменяет.
 *
 * <p>CSRF здесь не участвует по той же причине, что и в прежнем bootstrap:
 * cookie в запросе нет, авторизует его секрет в теле. Кто секрет знает —
 * вызовет напрямую, кто не знает — подделкой ничего не добьётся.
 */
@RestController
@RequestMapping("/api/provisioning")
public class ProvisioningController {

    private final TenantProvisioning provisioning;
    private final String token;

    public ProvisioningController(TenantProvisioning provisioning,
                                  @Value("${app.provisioning-token:}") String token) {
        this.provisioning = provisioning;
        this.token = token;
    }

    @PostMapping("/tenants")
    public ResponseEntity<TenantProvisioning.Result> createTenant(
            @Valid @RequestBody CreateTenantRequest request) {

        requireToken(request.token());

        TenantProvisioning.Result created = provisioning.provision(
                new TenantProvisioning.Request(request.companyCode(), request.companyName(),
                        request.ownerLogin(), request.ownerPassword(), request.ownerName()));

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Сверяет секрет за постоянное время.
     *
     * <p>Обычное {@code equals} прекращает сравнивать на первом несовпавшем
     * байте, и по времени ответа секрет подбирается посимвольно.
     */
    private void requireToken(String presented) {
        if (token == null || token.isBlank()) {
            throw new AccessDeniedException(
                    "Создание арендаторов выключено: app.provisioning-token не задан");
        }
        if (presented == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Неверный секрет");
        }
    }

    public record CreateTenantRequest(@NotBlank String token,
                                      @NotBlank String companyCode,
                                      @NotBlank String companyName,
                                      @NotBlank String ownerLogin,
                                      @NotBlank String ownerPassword,
                                      String ownerName) {
    }
}
