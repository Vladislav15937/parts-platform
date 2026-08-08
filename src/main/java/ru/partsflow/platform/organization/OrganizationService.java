package ru.partsflow.platform.organization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Филиалы, склады и ячейки хранения.
 *
 * <p>Появилось последним, а нужно было первым: провижининг заводил клиента,
 * но склада у него не было, и создать склад можно было только запросом в базу.
 * То есть каждый новый клиент требовал руки с доступом к Postgres — при том
 * что весь остальной путь давно проходился через API.
 *
 * <p><b>Ячейки заводятся списком, а не по одной.</b> Стеллаж — это два-три
 * десятка адресов подряд, и двадцать запросов на его заведение означают,
 * что заводить его никто не станет: коды пойдут в примечание, а поиск детали
 * на полке вернётся к памяти кладовщика.
 */
@Service
public class OrganizationService {

    private final JdbcTemplate jdbc;

    public OrganizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Branch> branches() {
        return jdbc.query("SELECT id, name FROM branch ORDER BY name",
                (rs, i) -> new Branch(rs.getLong("id"), rs.getString("name")));
    }

    @Transactional
    public Branch createBranch(String name) {
        requireName(name, "Название филиала");
        return jdbc.queryForObject(
                "INSERT INTO branch (name) VALUES (?) RETURNING id, name",
                (rs, i) -> new Branch(rs.getLong("id"), rs.getString("name")), name.strip());
    }

    @Transactional(readOnly = true)
    public List<Warehouse> warehouses() {
        return jdbc.query("""
                SELECT w.id, w.branch_id, w.name, b.name AS branch_name,
                       (SELECT count(*) FROM storage_cell c
                         WHERE c.warehouse_id = w.id AND c.is_active) AS cells
                  FROM warehouse w
                  JOIN branch b ON b.id = w.branch_id
                 ORDER BY b.name, w.name""", OrganizationService::warehouse);
    }

    @Transactional
    public Warehouse createWarehouse(Long branchId, String name) {
        requireName(name, "Название склада");
        Long branch = branchId != null ? branchId : soleBranch();
        requireExists("branch", branch, "Филиал не найден: ");

        Long id = jdbc.queryForObject(
                "INSERT INTO warehouse (branch_id, name) VALUES (?, ?) RETURNING id",
                Long.class, branch, name.strip());

        return warehouses().stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Филиал, если он один.
     *
     * <p>У девяти клиентов из десяти он один и есть, и заставлять их выбирать
     * его при каждом создании склада незачем. Если филиалов несколько —
     * выбирать обязан человек: склад, приписанный не туда, всплывёт в отчётах
     * через месяц.
     */
    private Long soleBranch() {
        List<Branch> found = branches();
        if (found.size() == 1) {
            return found.get(0).id();
        }
        throw new IllegalArgumentException(found.isEmpty()
                ? "Нет ни одного филиала: сначала заведите его"
                : "Филиалов несколько — укажите, к какому относится склад");
    }

    @Transactional(readOnly = true)
    public List<Cell> cells(long warehouseId) {
        return jdbc.query("""
                SELECT id, code, zone, is_active FROM storage_cell
                 WHERE warehouse_id = ? ORDER BY code""",
                OrganizationService::cell, warehouseId);
    }

    /**
     * Заводит ячейки списком.
     *
     * <p>Уже существующие пропускаются, а не ломают запрос целиком: список
     * адресов набирают руками, и одна повторённая строка не повод отменять
     * заведение стеллажа.
     *
     * @return только те, что появились
     */
    @Transactional
    public List<Cell> createCells(long warehouseId, List<String> codes, String zone) {
        if (codes == null || codes.isEmpty()) {
            throw new IllegalArgumentException("Не указано ни одной ячейки");
        }
        requireExists("warehouse", warehouseId, "Склад не найден: ");
        List<Cell> created = new ArrayList<>();

        for (String raw : codes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String code = raw.strip();
            List<Cell> inserted = jdbc.query("""
                    INSERT INTO storage_cell (warehouse_id, code, zone)
                    VALUES (?, ?, ?)
                    ON CONFLICT (warehouse_id, code) DO NOTHING
                    RETURNING id, code, zone, is_active""",
                    OrganizationService::cell, warehouseId, code,
                    zone == null || zone.isBlank() ? null : zone.strip());
            created.addAll(inserted);
        }
        return created;
    }

    private static Warehouse warehouse(ResultSet rs, int row) throws SQLException {
        return new Warehouse(rs.getLong("id"), rs.getLong("branch_id"),
                rs.getString("name"), rs.getString("branch_name"), rs.getInt("cells"));
    }

    private static Cell cell(ResultSet rs, int row) throws SQLException {
        return new Cell(rs.getLong("id"), rs.getString("code"),
                rs.getString("zone"), rs.getBoolean("is_active"));
    }

    /**
     * Ссылка обязана существовать, и сказать об этом надо словами.
     *
     * <p>Иначе чужой номер доезжает до внешнего ключа и возвращается как
     * «Операция нарушает целостность данных»: владелец, заводящий склад
     * или стеллаж, идёт искать поломку сервера вместо того, чтобы
     * посмотреть, что он выбрал.
     *
     * <p>Имя таблицы подставляется текстом, и это безопасно: оно приходит
     * из этого же класса, а не из запроса. Параметром таблицу не задать.
     */
    private void requireExists(String table, Long id, String complaint) {
        if (id == null) {
            return;
        }
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (found == null || found == 0) {
            throw new IllegalArgumentException(complaint + id);
        }
    }

    private static void requireName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(what + " обязательно");
        }
    }

    public record Branch(Long id, String name) {
    }

    public record Warehouse(Long id, Long branchId, String name, String branchName, int cells) {
    }

    public record Cell(Long id, String code, String zone, boolean active) {
    }
}
