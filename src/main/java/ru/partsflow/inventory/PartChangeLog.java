package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * «Эта позиция изменилась» — журнал, из которого выгрузки узнают, что обновить.
 *
 * <p>До 3 августа 2026 отметку ставил триггер базы на пять таблиц. Он был
 * полнее — через него проходил и прямой SQL, — но нарушал правило, ради
 * которого всё и переписано: логика живёт в Java, база хранит данные и связи.
 * Триггер невидим с того места, откуда наблюдают его последствия, и цена
 * этого — часы на вопрос «почему оно поменялось само».
 *
 * <p><b>Чем платим, названо заранее.</b> Отметка ставится там, где вызвали,
 * значит новая операция над позицией должна её поставить, и забыть это
 * возможно. Ловится тремя способами: методов-источников немного и они
 * перечислены в {@code docs/triggers-to-java.md}; забытая отметка не теряет
 * данные, а лишь откладывает обновление площадки до полного прайса; и
 * {@code PartChangeLogTest} проходит по операциям и требует отметку от каждой.
 *
 * <p><b>Массовые операции сюда не ходят намеренно.</b> Перенос из прежней
 * системы, доводка справочника после него, перенос фотографий — это десятки
 * тысяч позиций за раз, и отметить их значило бы отправить площадке семьдесят
 * пачек дельт подряд. Их состояние она узнаёт полным прайсом, который
 * собирается в момент запроса. Триггер этого различить не умел и как раз
 * поэтому был хуже.
 *
 * <p>Запись идёт в транзакции вызвавшего: отметка и правка обязаны попасть
 * в базу вместе. Отдельная транзакция дала бы отметку по правке, которую
 * потом откатили, — то есть дельту с прежним состоянием.
 */
@Component
public class PartChangeLog {

    private final JdbcTemplate jdbc;

    public PartChangeLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void changed(Long partId) {
        if (partId != null) {
            changed(List.of(partId));
        }
    }

    /**
     * Отмечает позиции изменившимися.
     *
     * <p>Ключ — сама позиция: сто правок одной детали дают одну строку и одну
     * дельту. Момент освежается, а заявка снимается — изменение, случившееся
     * после того, как пачку забрали на отправку, обязано уехать следующей,
     * иначе площадка останется с состоянием, которого уже нет.
     */
    public void changed(Collection<Long> partIds) {
        if (partIds == null || partIds.isEmpty()) {
            return;
        }
        List<Long> ids = partIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        String places = String.join(",", java.util.Collections.nCopies(ids.size(), "(?)"));
        jdbc.update("""
                INSERT INTO part_change (part_id) VALUES %s
                ON CONFLICT (part_id) DO UPDATE SET marked_at = now(), claimed_at = NULL"""
                .formatted(places), ids.toArray());
    }
}
