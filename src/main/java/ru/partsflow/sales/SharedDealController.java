package ru.partsflow.sales;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.partsflow.inventory.PartService;
import ru.partsflow.platform.tenant.TenantContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Сделка по ссылке: то, что видит клиент.
 *
 * <p>Без сессии и без CSRF: у покупателя нет учётной записи и не будет.
 * Права даёт секрет в самом адресе — как у прайса площадки, и с теми же
 * следствиями: ссылка живёт две недели и показывает ровно то, что клиент
 * и так знает про свою покупку.
 *
 * <p><b>Чего здесь нет и не должно быть.</b> Закупочной цены — это чужая
 * тайна; телефона и имени клиента — ссылку пересылают в переписке, и она
 * не должна раздавать чужие контакты; других сделок того же клиента —
 * по одной ссылке видно одну покупку.
 *
 * <p>Арендатор берётся из кода компании в адресе, а доступ открывает токен:
 * код сам по себе не даёт ничего. Это то же устройство, что у ссылки
 * на прайс, и не возврат убранного {@code X-Tenant-Id} — там номер схемы
 * подставлял кто угодно.
 */
@RestController
public class SharedDealController {

    private final JdbcTemplate jdbc;
    private final SalesService sales;
    private final PartService parts;
    private final ServiceKindRepository serviceKinds;

    public SharedDealController(JdbcTemplate jdbc, SalesService sales, PartService parts,
                                ServiceKindRepository serviceKinds) {
        this.jdbc = jdbc;
        this.sales = sales;
        this.parts = parts;
        this.serviceKinds = serviceKinds;
    }

    @GetMapping("/api/shared/{company}/{token}")
    public SharedView shared(@PathVariable String company, @PathVariable String token) {
        String schema = schemaOf(company);
        if (schema == null) {
            // Неверный код и неверный токен неразличимы: иначе по коду ответа
            // адрес работает справочником действующих компаний.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        TenantContext.set(schema);
        try {
            Deal deal = sales.byShareToken(token)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Map<Long, String> titles = parts.titlesOf(
                    deal.getItems().stream().map(DealItem::getPartId).toList());
            // Услуги идут в тот же список: без них итог не сходится с суммой
            // строк, и клиент видит «итого 4800» под деталью за 4500. Спор
            // об этом начнётся в момент оплаты.
            Map<Long, String> serviceNames = new java.util.HashMap<>();
            serviceKinds.findAllById(deal.getServices().stream()
                            .map(DealService::getServiceId).toList())
                    .forEach(kind -> serviceNames.put(kind.getId(), kind.getName()));

            return new SharedView(
                    deal.getNumber(),
                    deal.getStatus().name(),
                    deal.getReservedUntil(),
                    deal.getTotalAmount(),
                    deal.getPaidAmount(),
                    deal.debt(),
                    java.util.stream.Stream.concat(
                            deal.getItems().stream()
                                    .filter(item -> item.getStatus() != DealItemStatus.CANCELLED)
                                    .map(item -> new SharedItem(
                                            titles.get(item.getPartId()),
                                            item.getQuantity(),
                                            item.getPrice())),
                            deal.getServices().stream()
                                    .map(service -> new SharedItem(
                                            serviceNames.get(service.getServiceId()),
                                            service.getQuantity(),
                                            service.getPrice())))
                            .toList());
        } finally {
            TenantContext.clear();
        }
    }

    private String schemaOf(String company) {
        try {
            return jdbc.queryForObject("""
                    SELECT schema_name FROM public.tenant_registry
                     WHERE code = lower(btrim(?)) AND status = 'ACTIVE'""",
                    String.class, company);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** @param reservedUntil до какого числа товар отложен; клиент про это и спрашивает */
    public record SharedView(Long number, String status, Instant reservedUntil,
                             BigDecimal total, BigDecimal paid, BigDecimal debt,
                             List<SharedItem> items) {
    }

    public record SharedItem(String title, BigDecimal quantity, BigDecimal price) {
    }
}
