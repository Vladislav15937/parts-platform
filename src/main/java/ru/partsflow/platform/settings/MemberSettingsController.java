package ru.partsflow.platform.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.util.Map;

/**
 * Настройки экрана вошедшего сотрудника.
 *
 * <p>Своего идентификатора в адресе нет намеренно: настройка всегда своя.
 * Приняв {@code memberId} параметром, мы завели бы способ прочитать и переписать
 * чужой экран — и проверку «а свой ли он», которую можно забыть.
 */
@RestController
@RequestMapping("/api/me/settings")
public class MemberSettingsController {

    private final MemberSettingsService settings;

    public MemberSettingsController(MemberSettingsService settings) {
        this.settings = settings;
    }

    /** @return {@code value} пуст, если экран ещё не настраивали */
    @GetMapping("/{screen}")
    public Settings read(@PathVariable String screen) {
        return new Settings(screen,
                settings.read(memberId(), screen).orElse(null));
    }

    /**
     * Записывает настройку целиком, а не по полю.
     *
     * <p>PUT, а не PATCH, и по той же причине, что у формы карточки: снятый
     * отбор — это отсутствие ключа, и в патче его не отличить от «не трогали».
     * Настройку всё равно отправляет тот же экран, который её и собрал.
     */
    @PutMapping("/{screen}")
    public Settings write(@PathVariable String screen,
                          @RequestBody Map<String, Object> value) {
        settings.write(memberId(), screen, value);
        return new Settings(screen, value);
    }

    /**
     * Сотрудник берётся из сессии, а не из запроса.
     *
     * <p>Учётная запись без {@code tenant_member} (владелец, заведённый
     * провижинингом до появления сотрудников) настройку не сохранит — но и
     * привязать её было бы не к кому.
     */
    private long memberId() {
        Long id = CurrentUser.memberId();
        if (id == null) {
            throw new IllegalStateException(
                    "У вошедшего нет записи сотрудника — настройку не к кому привязать");
        }
        return id;
    }

    public record Settings(String screen, Map<String, Object> value) {
    }
}
