package ru.partsflow.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Списание: деталь ушла со склада, но не покупателю.
 *
 * <p><b>Без этого пересчёт оставлял петлю открытой.</b> Недостача обнуляет
 * остаток движением-корректировкой, а статус карточки не меняет — триггер
 * различает продажу и списание, а корректировка не то и не другое. Карточка
 * с нулём числилась «в наличии», и закрыть её было нечем: {@code writeOff}
 * был написан и в документе, и в движении, ветка {@code WRITTEN_OFF} в триггере
 * стояла с самого начала, а звать это не умел никто. То же и с битой деталью:
 * снять её со склада было можно только запросом в базу.
 *
 * <p><b>Одним запросом, как перемещение.</b> Черновик не нужен: обходить
 * со списком нечего, деталь уже в руках. Промежуточное состояние означало бы
 * деталь, которой нет ни на складе, ни в списанных.
 *
 * <p><b>Причина обязательна.</b> Списание — единственная операция, уносящая
 * товар без покупателя и без денег, и «почему» через месяц не восстановить
 * ни по журналу, ни по документу. Разбитая при разборе, украденная, сгнившая
 * на улице — это разные разговоры с кладовщиком.
 *
 * <p><b>Роль — владелец или менеджер, не кладовщик.</b> Недостачу находит
 * кладовщик, а списывает тот, кто отвечает за деньги: списанная деталь —
 * это убыток, а не запись в журнале. Пересчёт при этом остаётся за кладовщиком
 * целиком: он сообщает факт, решение принимают на его основании.
 */
@RestController
@RequestMapping("/api/stock/write-offs")
public class WriteOffController {

    private final StockDocumentService documents;

    public WriteOffController(StockDocumentService documents) {
        this.documents = documents;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<WriteOffView> writeOff(@Valid @RequestBody WriteOffRequest request) {
        StockDocument document = StockDocument.writeOff(request.warehouseId());
        document.setCreatedBy(CurrentUser.memberId());
        document.setNote(request.reason());

        for (WriteOffItem item : request.items()) {
            document.addLine(item.partId(), item.quantity(), null);
        }

        StockDocument saved = documents.save(document);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WriteOffView.of(documents.complete(saved.getId())));
    }

    public record WriteOffItem(@NotNull Long partId,
                               @NotNull @Positive BigDecimal quantity) {
    }

    /** @param reason почему списали: через месяц по журналу это не восстановить */
    public record WriteOffRequest(@NotNull Long warehouseId,
                                  @NotBlank String reason,
                                  @NotEmpty List<WriteOffItem> items) {
    }

    public record WriteOffView(Long id, Long number, Long warehouseId, String status,
                               Instant completedAt, int items) {

        static WriteOffView of(StockDocument document) {
            return new WriteOffView(document.getId(), document.getNumber(),
                    document.getWarehouseId(), document.getStatus().name(),
                    document.getCompletedAt(), document.getLines().size());
        }
    }
}
