package ru.partsflow.platform.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Управляющий контур ячейки: создание арендатора и накат миграций на схемы
 * уже заведённых.
 *
 * <p>Операции над ячейкой, а не клиента: первая создаёт схему в базе и заводит
 * владельца, вторая проходит по всем схемам сразу. Вошедшего пользователя тут
 * нет и быть не может — при создании арендатор ещё не существует, а миграции
 * идут от имени развёртывания, — поэтому запрос авторизуется секретом
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

    /** Тот же секрет, что в теле POST: заголовок нужен только там, где тела нет. */
    static final String TOKEN_HEADER = "X-Provisioning-Token";

    private final TenantProvisioning provisioning;
    private final TenantMigrations migrations;
    private final ru.partsflow.platform.config.TenantLoadMetrics tenantLoad;
    private final String token;

    public ProvisioningController(TenantProvisioning provisioning,
                                  TenantMigrations migrations,
                                  ru.partsflow.platform.config.TenantLoadMetrics tenantLoad,
                                  @Value("${app.provisioning-token:}") String token) {
        this.provisioning = provisioning;
        this.migrations = migrations;
        this.tenantLoad = tenantLoad;
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
     * Накатывает миграции на схемы уже заведённых арендаторов.
     *
     * <p>Тем же секретом и тем же контуром, что и создание арендатора: это
     * операция над всей ячейкой, вошедшего пользователя у неё нет и быть
     * не может. Шаг развёртывания вызывает её curl'ом — при старте приложения
     * такое делать нельзя, пятьсот схем это минуты недоступности ячейки.
     */
    @PostMapping("/migrations")
    public TenantMigrations.Report migrate(@Valid @RequestBody TokenRequest request) {
        requireToken(request.token());
        return migrations.migrateAll();
    }

    /**
     * Кто отстал от поставляемой схемы.
     *
     * <p>Проверка перед выкладкой: пустой {@code behind} означает, что код,
     * рассчитывающий на новую схему, выкладывать можно.
     *
     * <p><b>Секрет в заголовке, а не в параметре запроса.</b> Тела у GET нет,
     * и первым делом он уехал в адрес — а адреса пишут все: access-лог
     * терминатора, логи промежуточных прокси, история браузера, заголовок
     * {@code Referer} при переходе со страницы. Секрет, попавший в лог,
     * перестаёт быть секретом, и узнают об этом сильно позже.
     *
     * @param deep спросить каждую схему, а не поверить отметке в реестре.
     *             Дорого, поэтому не по умолчанию, — но перед выкладкой кода,
     *             рассчитывающего на новую схему, проверяют именно так:
     *             отметка врёт, если в схему лазили руками
     */
    @GetMapping("/migrations")
    public TenantMigrations.Status migrationStatus(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestParam(defaultValue = "false") boolean deep) {
        requireToken(token);
        return migrations.status(deep);
    }

    /**
     * Кто из клиентов греет ячейку.
     *
     * <p>Метрики очереди говорят, что ячейке плохо, но не говорят, из-за кого:
     * при пятистах арендаторах на общем комплекте это первый вопрос оператора.
     * Отдаётся десятка самых тяжёлых по суммарному времени за час — в Prometheus
     * такое не вынести, там метка-арендатор размножит ряды пятикратно
     * на каждое число.
     *
     * <p>Тем же секретом и тем же контуром, что и миграции: это взгляд на всю
     * ячейку, вошедшего пользователя у него нет и быть не может.
     */
    @GetMapping("/load")
    public java.util.List<ru.partsflow.platform.config.TenantLoadMetrics.TenantLoad> load(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        return tenantLoad.top();
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
                    "Управляющий контур выключен: app.provisioning-token не задан");
        }
        if (presented == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Неверный секрет");
        }
    }

    public record TokenRequest(@NotBlank String token) {
    }

    public record CreateTenantRequest(@NotBlank String token,
                                      @NotBlank String companyCode,
                                      @NotBlank String companyName,
                                      @NotBlank String ownerLogin,
                                      @NotBlank String ownerPassword,
                                      String ownerName) {
    }
}
