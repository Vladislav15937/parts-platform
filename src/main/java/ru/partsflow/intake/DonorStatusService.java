package ru.partsflow.intake;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.platform.audit.AuditJournal;
import ru.partsflow.shared.NotFound;

import java.util.List;
import java.util.Map;

/**
 * Список статусов машины, который ведёт клиент (tenant/110, задача 0364).
 *
 * <p>У Bazon статус машины — список клиента («В Японии», «В [город]»),
 * а не закрытый перечень; решение владельца 19.10.2026 — так же. Первые
 * значения — прежние стадии, их завела миграция.
 *
 * <p><b>Правится прямым SQL, поэтому журнал пишет сервис</b> через
 * {@link AuditJournal} — тем же видом записи, что у источников платежа.
 *
 * <p><b>Удалить выбранный у машин статус нельзя, и сказано почему.</b>
 * Ссылка {@code donor.status_id} без {@code ON DELETE}: тихо снять статус
 * у сотни машин одним нажатием в настройках — потеря, которую не видно
 * до следующего открытия карточки. Проверка и удаление — одной инструкцией
 * ({@code NOT EXISTS} в {@code WHERE}); выбранный между ними статус отобьёт
 * внешний ключ.
 */
@Service
public class DonorStatusService {

    /** Длиннее в выпадающем списке формы машины не прочитать. */
    static final int NAME_LIMIT = 60;

    private final JdbcTemplate jdbc;
    private final AuditJournal journal;

    public DonorStatusService(JdbcTemplate jdbc, AuditJournal journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    /** Список по порядку, с числом машин у каждого статуса. */
    @Transactional(readOnly = true)
    public List<StatusView> list() {
        return jdbc.query("""
                SELECT s.id, s.name,
                       (SELECT count(*) FROM donor d WHERE d.status_id = s.id) AS donors
                  FROM donor_status s
                 ORDER BY s.sort_order, s.id""",
                (rs, i) -> new StatusView(rs.getLong("id"), rs.getString("name"), rs.getInt("donors")));
    }

    @Transactional
    public List<StatusView> create(String name) {
        String trimmed = nameOf(name);
        List<Long> created = jdbc.queryForList("""
                INSERT INTO donor_status (name, sort_order)
                VALUES (?, coalesce((SELECT max(sort_order) FROM donor_status), 0) + 1)
                ON CONFLICT ((lower(btrim(name)))) DO NOTHING
                RETURNING id""", Long.class, trimmed);
        if (created.isEmpty()) {
            throw taken(trimmed);
        }
        journal.created("donor_status", created.get(0), Map.of("name", trimmed));
        return list();
    }

    @Transactional
    public List<StatusView> rename(long id, String name) {
        String trimmed = nameOf(name);
        String was = nameById(id);
        if (!was.equals(trimmed)) {
            try {
                jdbc.update("UPDATE donor_status SET name = ? WHERE id = ?", trimmed, id);
            } catch (DuplicateKeyException e) {
                throw taken(trimmed);
            }
            journal.changed("donor_status", id, Map.of("name", was), Map.of("name", trimmed));
        }
        return list();
    }

    @Transactional
    public List<StatusView> delete(long id) {
        String name = nameById(id);
        int removed = jdbc.update("""
                DELETE FROM donor_status s
                 WHERE s.id = ? AND NOT EXISTS (SELECT 1 FROM donor d WHERE d.status_id = s.id)""", id);
        if (removed == 0) {
            Integer donors = jdbc.queryForObject(
                    "SELECT count(*) FROM donor WHERE status_id = ?", Integer.class, id);
            throw new IllegalStateException("Статус «" + name + "» выбран у машин: " + donors
                    + ". Сначала смените им статус, потом удаляйте");
        }
        journal.deleted("donor_status", id, Map.of("name", name));
        return list();
    }

    /** Есть ли такой статус — для формы машины. */
    @Transactional(readOnly = true)
    public void require(long id) {
        nameById(id);
    }

    private String nameById(long id) {
        List<String> names = jdbc.queryForList(
                "SELECT name FROM donor_status WHERE id = ?", String.class, id);
        if (names.isEmpty()) {
            throw NotFound.DONOR_STATUS.error(id);
        }
        return names.get(0);
    }

    private static String nameOf(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Назовите статус — пустой в списке не выбрать");
        }
        if (trimmed.length() > NAME_LIMIT) {
            throw new IllegalArgumentException(
                    "Название статуса длиннее " + NAME_LIMIT + " знаков — сократите");
        }
        return trimmed;
    }

    private static IllegalStateException taken(String name) {
        return new IllegalStateException("Статус «" + name + "» уже есть в списке");
    }

    /** @param donors у скольких машин выбран — удалить можно только невыбранный */
    public record StatusView(long id, String name, int donors) {
    }
}
