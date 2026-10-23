package ru.partsflow.platform.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Подписи сотрудников по идентификаторам — одним запросом на всю выдачу
 * (задача 0373, «подпись везде»).
 *
 * <p>Замена {@code MemberService.namesOf} для всего, что показывает человеку
 * автора: история сделки, лицевой счёт, ответственный в карточке сделки.
 * Решение владельца 23.10.2026 (`tasks/0373`, раздел «после слияния PR 499,
 * 503», пункт 5а): автор подписан «Имя (логин, ID N)» везде, где назван.
 * Подпись собирает {@link MemberSignature#of} — здесь только поиск строк.
 *
 * <p>Своим классом, а не методом {@code MemberService}: тот делит
 * с сотрудниками экран «Сотрудники» и его отказы, и правка в нём шла бы
 * рядом с чужой работой (задача 0044). Здесь ничего, кроме подписи, нет.
 */
@Service
public class MemberSignatures {

    private final JdbcTemplate jdbc;

    public MemberSignatures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return номер строки → подпись; сотрудника, которого нет, в карте нет.
     *         Карта изменяемая и {@code null}-ключ на чтении не бросает: у сделки
     *         с площадки ответственного нет, и {@code get(null)} у {@code Map.of()}
     *         ушёл бы пятисоткой (запись в {@code SalesController.DealView.nameOf})
     */
    @Transactional(readOnly = true)
    public Map<Long, String> of(Collection<Long> memberIds) {
        List<Long> ids = memberIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, String> signatures = new HashMap<>();
        if (ids.isEmpty()) {
            return signatures;
        }
        jdbc.query("SELECT id, display_name, login, number FROM tenant_member WHERE id = ANY (?)",
                rs -> {
                    signatures.put(rs.getLong("id"), MemberSignature.of(rs.getString("display_name"),
                            rs.getString("login"), (Long) rs.getObject("number")));
                },
                (Object) ids.toArray(Long[]::new));
        return signatures;
    }
}
