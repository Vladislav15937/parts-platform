package ru.partsflow.reports;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Отчёты владельца.
 *
 * <p>Роль — владелец или менеджер, и это не осторожность ради осторожности:
 * в продажах по менеджерам лежит зарплатная база всей смены, а в окупаемости
 * доноров — себестоимость. Продавцу, который видит выработку соседа,
 * и приёмщику, который знает закупочную цену машины, эти цифры дают
 * ровно то, ради чего их обычно и смотрят украдкой.
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class ReportController {

    /** Полсотни машин — это уже год работы разборки, дальше листают. */
    /** Столько строк расчётов показываем: остальное — работа отбора, а не списка. */
    private static final int SETTLEMENT_LIMIT = 100;

    private static final int DONOR_LIMIT = 50;

    private final ReportService reports;

    private final OriginReportService origins;

    public ReportController(ReportService reports, OriginReportService origins) {
        this.reports = reports;
        this.origins = origins;
    }

    /**
     * @param month месяц вида {@code 2026-07}. Пусто — текущий: зарплату
     *              считают за него, и заставлять владельца набирать сегодняшний
     *              месяц руками незачем
     */
    @GetMapping("/managers")
    public ManagerReport managers(@RequestParam(required = false) String month) {
        YearMonth period = parseMonth(month);
        return new ManagerReport(period.toString(), reports.managerSales(period));
    }

    /**
     * Продажи по каналам за месяц.
     *
     * <p>Месяц тот же, что и у отчёта по менеджерам: владелец смотрит их
     * рядом, и разные периоды на соседних вкладках сравнивать нельзя.
     */
    @GetMapping("/sources")
    public SourceReport sources(@RequestParam(required = false) String month) {
        YearMonth period = month == null ? YearMonth.now() : YearMonth.parse(month);
        return new SourceReport(period.toString(), reports.salesBySource(period));
    }

    /**
     * Сводка: сколько лежит на складе и сколько висит в незакрытых сделках.
     *
     * <p>Шесть чисел без единой настройки — намеренно. Это первое, что владелец
     * спрашивает с утра, и период тут не при чём: остаток и незакрытые сделки
     * существуют «сейчас», а не за месяц.
     */
    @GetMapping("/summary")
    public ReportService.Summary summary() {
        return reports.summary();
    }

    @GetMapping("/donors")
    public DonorReport donors() {
        return new DonorReport(reports.donorProfitability(DONOR_LIMIT), reports.donorTotals());
    }

    /**
     * Позиции одной машины: что поступило, что продано, что списано,
     * что лежит до сих пор.
     *
     * <p>Числа по машине были и раньше — «продано на 331 716, лежит
     * на 835 600», — а спросить «что именно лежит» было нельзя: владелец
     * уходил в склад и собирал отбор руками.
     *
     * @param after позиция, после которой продолжать. Курсором, а не номером
     *              страницы: у контейнера бывает несколько тысяч позиций
     */
    @GetMapping("/donors/{donorId}/items")
    public OriginReportService.Page donorItems(
            @PathVariable long donorId,
            @RequestParam(defaultValue = "received") String tab,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) Integer size) {
        return origins.donorItems(donorId, OriginReportService.Tab.of(tab), after, size);
    }

    /**
     * То же по партии.
     *
     * @param supplyId пусто — товар без поставки: у переехавшего клиента это
     *                 всё, что заводили руками, и такой разрез он спрашивает
     *                 наравне с остальными
     */
    @GetMapping("/supplies/items")
    public OriginReportService.Page supplyItems(
            @RequestParam(required = false) Long supplyId,
            @RequestParam(defaultValue = "received") String tab,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) Integer size) {
        return origins.supplyItems(supplyId, OriginReportService.Tab.of(tab), after, size);
    }

    /** Партии для выбора — все, включая закрытые: про закрытую и спрашивают. */
    @GetMapping("/supplies")
    public SupplyList supplies() {
        return new SupplyList(origins.supplies());
    }

    public record SupplyList(List<OriginReportService.SupplyOption> rows) {
    }

    /**
     * Расчёты с клиентами: у кого наши деньги, кто должен нам и сходится ли.
     *
     * <p>Сверка едет вместе с итогом намеренно: число обязательств, рядом
     * с которым не сказано, сходится ли оно, — спокойствие без основания.
     */
    @GetMapping("/customers")
    public SettlementReport customers() {
        return new SettlementReport(
                reports.customerSettlements(SETTLEMENT_LIMIT), reports.settlementTotals());
    }

    public record SettlementReport(List<ReportService.SettlementRow> rows,
                                   ReportService.SettlementTotals totals) {
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException cause) {
            // 400, а не 500: это ошибка запроса, и клиент должен её различать.
            throw new IllegalArgumentException("Месяц указывается как 2026-07, а не «%s»"
                    .formatted(month));
        }
    }

    public record ManagerReport(String month, List<ReportService.ManagerRow> rows) {
    }

    public record SourceReport(String month, List<ReportService.SourceRow> rows) {
    }

    public record DonorReport(List<ReportService.DonorRow> rows,
                              ReportService.DonorTotals totals) {
    }
}
