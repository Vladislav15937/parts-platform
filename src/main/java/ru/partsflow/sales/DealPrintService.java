package ru.partsflow.sales;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.PartService;
import ru.partsflow.platform.settings.PrintSettingsService;
import ru.partsflow.shared.RetailCustomer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Документ сделки, готовый к печати: чек, накладная, счёт (задача 0051).
 *
 * <p><b>Собирает его сервер, а не экран.</b> Реквизиты продавца лежат
 * у склада, реквизиты юр. лица — в настройках компании, наименования —
 * в карточках позиций, названия услуг — в справочнике: экран, собирающий
 * это сам, сделал бы четыре запроса и повторил бы правило «какой склад
 * считается складом выдачи» своими словами. Одна форма — один ответ.
 *
 * <p><b>Четыре печатные формы — одни и те же данные.</b> Отличаются они
 * вёрсткой и тем, чьи реквизиты стоят в шапке (пункт 5 критерия приёмки:
 * счёт берёт реквизиты организации, а не блок склада), поэтому форм на
 * сервере нет вовсе — он отдаёт обе половины, а какую печатать, выбирает
 * человек в меню.
 */
@Service
public class DealPrintService {

    private final SalesService sales;
    private final PartService parts;
    private final PrintSettingsService printSettings;
    private final JdbcTemplate jdbc;

    public DealPrintService(SalesService sales, PartService parts,
                           PrintSettingsService printSettings, JdbcTemplate jdbc) {
        this.sales = sales;
        this.parts = parts;
        this.printSettings = printSettings;
        this.jdbc = jdbc;
    }

    /**
     * Всё, что печатается по этой сделке.
     *
     * <p>{@code @Transactional} обязателен: внутри — и JPA (сделка с позициями),
     * и {@code JdbcTemplate} (клиент, склад, настройки). Без транзакции
     * последний уходит в {@code public} — ловушка, на которую в этом проекте
     * наступали пять раз.
     */
    @Transactional(readOnly = true)
    public DealPrintView of(Long dealId) {
        Deal deal = sales.require(dealId);
        PrintSettingsService.PrintSettings settings = printSettings.read();

        return new DealPrintView(
                deal.getId(), deal.getNumber(), deal.getCreatedAt(), deal.getIssuedAt(),
                sellerOf(deal), buyerOf(deal.getCustomerId()), settings.legal(),
                settings.extraText(), settings.vatNote(),
                settings.clientSignature(), settings.issuerSignature(),
                linesOf(deal),
                deal.getTotalAmount(), deal.getPaidAmount(), deal.debt());
    }

    /**
     * Кто продал: реквизиты склада выдачи.
     *
     * <p><b>Склад выдачи считается по позициям, а не по {@code deal.warehouse_id},</b>
     * и это не выбор из двух равных: колонку сделки не пишет никто — у всех
     * сделок она {@code null} с самого начала (записано в
     * {@code frontend/CLAUDE.md} с задачи 0021). Печать по ней брала бы
     * реквизиты «никакого» склада всегда. Поэтому порядок тот же, что
     * у умолчания склада возврата ({@code returnWarehouseDefault}) и у отбора
     * «склад выдачи» на доске сделок: сначала колонка документа — если её
     * однажды начнут заполнять, — потом единственный склад его позиций.
     *
     * <p><b>Позиции с разных складов не дают реквизитов вовсе, и причина
     * называется словами.</b> Выбрать «первый попавшийся» склад значило бы
     * напечатать покупателю не того продавца — то есть чужое ИП в документе,
     * по которому он придёт по гарантии. То же правило, по которому
     * не подставляется склад в приёмке и в возврате: <b>подставлять можно то,
     * что система знает, а не то, что оказалось первым.</b>
     *
     * <p>Причину складывает сервер, а не экран: её читают обе формы (чек
     * и накладная), и два написания одного отказа разошлись бы на первой
     * правке.
     */
    private Seller sellerOf(Deal deal) {
        List<Long> warehouses = deal.getItems().stream()
                .map(DealItem::getWarehouseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Long chosen = deal.getWarehouseId();
        if (chosen == null && warehouses.size() == 1) {
            chosen = warehouses.get(0);
        }
        if (chosen == null) {
            return new Seller(null, null, null, warehouses.size() > 1
                    ? "Позиции сделки с разных складов — реквизиты продавца подставить "
                            + "нельзя. Напечатайте документ по позициям одного склада."
                    : "Склад выдачи у сделки не определён — реквизиты продавца "
                            + "подставить не из чего.");
        }

        PrintSettingsService.WarehouseDetails block = printSettings.warehouseOf(chosen);
        if (block == null) {
            return new Seller(chosen, null, null,
                    "Склад выдачи не найден — возможно, его убрали. Реквизиты продавца "
                            + "в документ не попали.");
        }
        if (block.details() == null || block.details().isBlank()) {
            return new Seller(block.id(), block.name(), null,
                    "Реквизиты склада «%s» не заполнены — задайте их в «Настройках» → "
                            .formatted(block.name())
                            + "«Печатные формы». Пока документ печатается без продавца.");
        }
        return new Seller(block.id(), block.name(), block.details(), null);
    }

    /**
     * Кому продали.
     *
     * <p>Читается своим запросом, а не {@code CustomerService.getDetail}:
     * тому нужны два агрегата по лицевому счёту и долгу, а в документе
     * их нет — и, главное, он отказывает словами на ненайденного клиента.
     * У сделки с площадки контрагента нет вовсе, и печать по этой причине
     * останавливаться не должна: покупатель зовётся тем же словом, что
     * и везде, — {@link RetailCustomer#NAME}.
     */
    private Buyer buyerOf(Long customerId) {
        if (customerId == null) {
            return new Buyer(RetailCustomer.NAME, null, null, null, null);
        }
        List<Buyer> found = jdbc.query("""
                SELECT name, phone, inn, company_name, public_note
                  FROM customer WHERE id = ?""",
                (rs, row) -> new Buyer(
                        rs.getString("name") == null || rs.getString("name").isBlank()
                                ? RetailCustomer.NAME : rs.getString("name"),
                        rs.getString("phone"), rs.getString("inn"),
                        rs.getString("company_name"), rs.getString("public_note")),
                customerId);

        return found.isEmpty()
                ? new Buyer(RetailCustomer.NAME, null, null, null, null)
                : found.get(0);
    }

    /**
     * Строки документа: сначала позиции, потом услуги.
     *
     * <p><b>Услуги печатаются наравне с деталями, иначе сумма строк
     * не сходится с итогом</b> — «итого 7 500» под деталями на 7 000.
     * Этот спор в проекте уже случался дважды (ссылка клиенту и экран
     * продавца), и начинается он в момент оплаты; в бумаге, которую
     * покупатель уносит с собой, он хуже всего.
     *
     * <p>Наименования и названия услуг читаются одним запросом на весь
     * документ — тем же {@code PartService.titlesOf}, что и у остальных
     * выходов сделки наружу.
     */
    private List<Line> linesOf(Deal deal) {
        Map<Long, String> titles = parts.titlesOf(deal.getItems().stream()
                .map(DealItem::getPartId)
                .distinct()
                .toList());
        Map<Long, String> serviceNames = sales.serviceKinds().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ServiceKind::getId, ServiceKind::getName));

        List<Line> lines = new ArrayList<>();
        for (DealItem item : deal.getItems()) {
            String title = titles.get(item.getPartId());
            lines.add(new Line(
                    title == null ? "Товар без наименования" : title,
                    item.getQuantity(), item.getPrice(),
                    item.getPrice().multiply(item.getQuantity())));
        }
        for (var service : deal.getServices()) {
            String name = serviceNames.get(service.getServiceId());
            lines.add(new Line(
                    name == null ? "Услуга" : name,
                    service.getQuantity(), service.getPrice(),
                    service.getPrice().multiply(service.getQuantity())));
        }
        return lines;
    }

    /**
     * @param details реквизиты склада одной многострочной записью. Пусто —
     *                в документе продавца нет, и {@code problem} говорит почему
     * @param problem почему реквизитов нет: словами, которые понятны продавцу,
     *                стоящему у принтера. Пусто — всё на месте
     */
    public record Seller(Long warehouseId, String warehouseName, String details,
                         String problem) {
    }

    /**
     * @param name      имя покупателя. У розничной продажи и у заказа
     *                  с площадки это «Частное лицо» — то же слово, что
     *                  на всех экранах (решение владельца от 12 сентября 2026)
     * @param note      примечание клиенту ({@code customer.public_note}).
     *                  Подпись к полю в карточке клиента обещает, что оно
     *                  «выводится при печати накладных и других документов», —
     *                  и до задачи 0051 печати, в которую оно выводилось бы,
     *                  не существовало вовсе
     */
    public record Buyer(String name, String phone, String inn, String companyName,
                        String note) {
    }

    /** @param amount цена, умноженная на количество: его считает сервер, а не экран */
    public record Line(String title, BigDecimal quantity, BigDecimal price,
                       BigDecimal amount) {
    }

    /**
     * @param legal           реквизиты организации — для счёта на юр. лицо.
     *                        В чек и накладную они не идут: там продавец это
     *                        склад
     * @param vatNote         пометка об НДС. Пусто — строки в документе нет
     *                        вовсе (пункт 6 критерия приёмки)
     * @param clientSignature печатать поле подписи клиента
     * @param issuerSignature печатать поле подписи выдавшего товар
     */
    public record DealPrintView(Long dealId, Long number,
                                Instant createdAt, Instant issuedAt,
                                Seller seller, Buyer buyer,
                                PrintSettingsService.Legal legal,
                                String extraText, String vatNote,
                                boolean clientSignature, boolean issuerSignature,
                                List<Line> lines,
                                BigDecimal total, BigDecimal paid, BigDecimal debt) {
    }
}
