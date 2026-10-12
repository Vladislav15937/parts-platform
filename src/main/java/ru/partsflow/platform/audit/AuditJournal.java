package ru.partsflow.platform.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.shared.AuditedColumns;

import java.util.Map;

/**
 * Запись в журнал изменений для того, что правится мимо Hibernate.
 *
 * <p>{@link AuditLogListener} видит только сущности. Выгрузка
 * ({@code marketplace_account}) сущностью не является и правится прямым SQL,
 * поэтому до задачи 0267 её правки в журнал не попадали вовсе — оставалась
 * строка в логе приложения без автора. Владелец продукта 12 октября 2026
 * велел вести журнал правки номера прайс-листа здесь же
 * ({@code tasks/0267}, раздел «Вопрос владельцу продукта — журнал правки
 * выгрузки»).
 *
 * <p><b>Запись того же вида, что у слушателя</b>: та же таблица, те же
 * колонки, автор и роль снимком из {@link CurrentAuthor}. Экран журнала
 * читает одну таблицу и второго формата не знает — запись в свою таблицу
 * была бы журналом, который никто не открывает.
 *
 * <p><b>Только в чужой транзакции</b> ({@code MANDATORY}): журнал и правка
 * обязаны попасть в базу вместе, иначе откат правки оставит запись о том,
 * чего не было. И без транзакции {@code JdbcTemplate} ушёл бы в {@code public}.
 *
 * <p><b>Пишется только то, что журнал умеет назвать.</b> Таблица без словаря
 * в {@link AuditedColumns} отвергается сразу: строку с неизвестной таблицей
 * экран журнала не покажет, то есть запись была бы, а найти её было бы нельзя.
 * Секретов сюда не передают — в снимок идут только переданные колонки,
 * а не строка таблицы целиком.
 */
@Component
public class AuditJournal {

    private final JdbcTemplate jdbc;

    public AuditJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Правка полей записи: было → стало.
     *
     * @param table    таблица, как её называет {@link AuditedColumns#of}
     * @param recordId запись
     * @param was      колонка → прежнее значение (значение может быть {@code null})
     * @param now      колонка → новое значение
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(String table, long recordId, Map<String, ?> was, Map<String, ?> now) {
        if (AuditedColumns.of(table).isEmpty()) {
            throw new IllegalStateException("Журнал не знает таблицы " + table);
        }
        jdbc.update(AuditLogListener.INSERT, table, recordId, "UPDATE",
                AuditLogListener.JsonRows.write(was), AuditLogListener.JsonRows.write(now),
                CurrentAuthor.get(), CurrentAuthor.role());
    }
}
