package ru.partsflow.platform.settings;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Настройки печатных форм — экран «Настройки» → «Печатные формы», владелец.
 *
 * <p><b>Правит и читает только владелец</b>, в отличие от самой печати
 * ({@code GET /api/deals/{id}/print}, роли как у сделки): это пункт 7 критерия
 * приёмки задачи 0051 — «экран настройки доступен владельцу; менеджер
 * и продавец печатают, но настройку не меняют». Здесь лежат реквизиты
 * юридических лиц и банковские счёта, и показывать их продавцу незачем:
 * в документ они попадают уже собранными.
 *
 * <p>PUT, а не PATCH: форма отправляется целиком той же формой, которая её
 * показала, — и целиком же пишется, одной транзакцией. Наполовину сохранённая
 * настройка оставила бы владельца уверенным, что реквизиты заданы.
 */
@RestController
@RequestMapping("/api/company/print-settings")
public class PrintSettingsController {

    private static final String OWNER = "hasRole('OWNER')";

    private final PrintSettingsService settings;

    public PrintSettingsController(PrintSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize(OWNER)
    public PrintSettingsService.PrintSettings read() {
        return settings.read();
    }

    @PutMapping
    @PreAuthorize(OWNER)
    public PrintSettingsService.PrintSettings write(@Valid @RequestBody UpdateRequest request) {
        return settings.update(request.extraText(), request.vatNote(),
                request.clientSignature(), request.issuerSignature(),
                request.legal(), request.warehouses());
    }

    /**
     * Поля необязательны: пустой текст гарантии и незаданная пометка об НДС —
     * законное состояние, а не ошибка ввода. Границ у них нет никаких, и
     * {@code @NotBlank} здесь означал бы, что настройку нельзя снять.
     *
     * @param warehouses блоки реквизитов по складам; склад, которого в списке
     *                   нет, остаётся как был — форма присылает ровно те, что
     *                   показала
     */
    public record UpdateRequest(String extraText, String vatNote,
                                boolean clientSignature, boolean issuerSignature,
                                PrintSettingsService.Legal legal,
                                List<PrintSettingsService.WarehouseDetails> warehouses) {
    }
}
