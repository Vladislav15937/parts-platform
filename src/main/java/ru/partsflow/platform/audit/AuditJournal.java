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
        requireKnown(table);
        jdbc.update(AuditLogListener.INSERT, table, recordId, "UPDATE",
                AuditLogListener.JsonRows.write(was), AuditLogListener.JsonRows.write(now),
                CurrentAuthor.get(), CurrentAuthor.role());
    }

    /**
     * Заведение записи: «было» нет, «стало» — то, что журнал умеет назвать.
     *
     * <p>Экран показывает такую строку событием («Выгрузка заведена»), а не
     * перечнем полей, как и у записей слушателя. Что войдёт в «стало» —
     * решает вызывающий; секрета там быть не должно (задача 0270).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(String table, long recordId, Map<String, ?> now) {
        requireKnown(table);
        jdbc.update(AuditLogListener.INSERT, table, recordId, "INSERT",
                null, AuditLogListener.JsonRows.write(now),
                CurrentAuthor.get(), CurrentAuthor.role());
    }

    /**
     * Удаление записи: «стало» нет, «было» — название и прочее, что нужно,
     * чтобы строку журнала можно было узнать, когда вещи в списках уже нет.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(String table, long recordId, Map<String, ?> was) {
        requireKnown(table);
        jdbc.update(AuditLogListener.INSERT, table, recordId, "DELETE",
                AuditLogListener.JsonRows.write(was), null,
                CurrentAuthor.get(), CurrentAuthor.role());
    }

    private static void requireKnown(String table) {
        if (AuditedColumns.of(table).isEmpty()) {
            throw new IllegalStateException("Журнал не знает таблицы " + table);
        }
    }
}
