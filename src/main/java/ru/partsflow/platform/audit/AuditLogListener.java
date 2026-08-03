package ru.partsflow.platform.audit;

import jakarta.persistence.Table;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Журнал изменений: кто и что поправил в карточке, сделке, платеже.
 *
 * <p>До 4 августа 2026 снимок строки клал триггер {@code audit_trigger}.
 * Правило «логика только в приложении» его сняло, и это самая дорогая потеря
 * из всех: триггер не обходил никто — ни прямой SQL, ни перенос, ни починка
 * руками, — а «кто уронил цену» спрашивают ровно тогда, когда подозревают
 * правку мимо интерфейса. Теперь журнал видит только то, что прошло через
 * Hibernate.
 *
 * <p><b>Слушатель, а не вызов из сервисов.</b> Иначе список мест, где надо
 * не забыть записать в журнал, растёт с каждым новым методом, а забытая
 * запись обнаруживается через месяц при разбирательстве. Слушатель ловит
 * любое сохранение сущности, откуда бы оно ни пришло.
 *
 * <p><b>Формат снимка сохранён дословно.</b> Триггер писал {@code to_jsonb}
 * строки — то есть имена колонок базы, а не свойств класса. Читает журнал
 * {@code PartHistoryService} по этим именам, поэтому здесь берутся колонки
 * из описания сущности у Hibernate, а не имена полей.
 *
 * <p>Пишется через то же соединение, что и сама правка: журнал и правка
 * обязаны попасть в базу одной транзакцией, иначе откат оставит запись
 * о том, чего не было.
 */
public class AuditLogListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditLogListener.class);

    /**
     * Таблицы, за которыми следим.
     *
     * <p>Тот же список, что был у триггеров: карточка, сделка с позициями,
     * платёж и затраты по машине. Не «всё подряд» — снимок строки на каждое
     * изменение это мегабайты jsonb, и у переехавшего клиента их 141 955
     * на 35 841 позицию даже при этом списке.
     */
    private static final Set<String> AUDITED = Set.of(
            "part", "deal", "deal_item", "payment", "donor_cost");

    private static final String INSERT = """
            INSERT INTO audit_log
                (table_name, record_id, operation, old_value, new_value, changed_by)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)""";

    @Override
    public void onPostInsert(PostInsertEvent event) {
        write(event.getPersister(), event.getId(), "INSERT",
                null, snapshot(event.getPersister(), event.getState()), event);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        // getOldState бывает пустым, если сущность пришла отсоединённой:
        // Hibernate тогда не знает, какой она была. Пишем что есть — «стало»
        // важнее, чем «было», а врать про «было» нельзя.
        String was = event.getOldState() == null
                ? null : snapshot(event.getPersister(), event.getOldState());
        write(event.getPersister(), event.getId(), "UPDATE",
                was, snapshot(event.getPersister(), event.getState()), event);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        write(event.getPersister(), event.getId(), "DELETE",
                snapshot(event.getPersister(), event.getDeletedState()), null, event);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        // Нет: запись обязана идти в той же транзакции, что и правка.
        return false;
    }

    private void write(EntityPersister persister, Object id, String operation,
                       String oldValue, String newValue, Object event) {
        String table = tableOf(persister);
        if (table == null || !AUDITED.contains(table)) {
            return;
        }

        var session = ((org.hibernate.event.spi.AbstractEvent) event).getSession();
        session.doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, table);
                statement.setObject(2, id instanceof Number number ? number.longValue() : null);
                statement.setString(3, operation);
                statement.setString(4, oldValue);
                statement.setString(5, newValue);
                Long author = CurrentAuthor.get();
                if (author == null) {
                    statement.setNull(6, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(6, author);
                }
                statement.executeUpdate();
            }
        });
    }

    /**
     * Имя таблицы сущности.
     *
     * <p>Из аннотации {@code @Table}, а не из имени класса: они расходятся
     * (у {@code deal_item} класс {@code DealItem}), и по классу список
     * отслеживаемых таблиц пришлось бы вести вторым.
     */
    private String tableOf(EntityPersister persister) {
        Class<?> type = persister.getMappedClass();
        Table table = type.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        log.debug("У {} нет @Table — в журнал изменений не попадёт", type.getSimpleName());
        return null;
    }

    /**
     * Снимок строки: колонка базы → значение, как это делал {@code to_jsonb}.
     *
     * <p>Идентификатор в снимок не попадает — его же не попадало и у триггера
     * (он есть отдельной колонкой {@code record_id}), а вот значения полей
     * нужны все: {@code PartHistoryService} сравнивает снимки между собой
     * и без поля не увидит правку.
     */
    private String snapshot(EntityPersister persister, Object[] state) {
        if (state == null) {
            return null;
        }
        String[] names = persister.getPropertyNames();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int at = 0; at < names.length && at < state.length; at++) {
            String column = columnOf(persister, at);
            if (column == null) {
                // Составные и коллекции в снимок не идут: триггер писал
                // строку таблицы, а они лежат в других.
                continue;
            }
            row.put(column, state[at]);
        }
        return JsonRows.write(row);
    }

    /**
     * Имя колонки свойства.
     *
     * <p>Через {@code AbstractEntityPersister}: у интерфейса персистера этого
     * метода нет, а имена колонок нужны — журнал читают по ним, а не
     * по именам полей.
     */
    private String columnOf(EntityPersister persister, int index) {
        if (!(persister instanceof org.hibernate.persister.entity.AbstractEntityPersister able)) {
            return null;
        }
        String[] columns = able.getPropertyColumnNames(index);
        return columns == null || columns.length != 1 ? null : columns[0];
    }

    /** Сериализация снимка в jsonb — без Jackson, значений тут пять видов. */
    static final class JsonRows {

        private JsonRows() {
        }

        static String write(Map<String, Object> row) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (var entry : row.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(escape(entry.getKey())).append("\":");
                json.append(value(entry.getValue()));
            }
            return json.append('}').toString();
        }

        private static String value(Object value) {
            return switch (value) {
                case null -> "null";
                case Number number -> number instanceof BigDecimal decimal
                        ? decimal.toPlainString() : number.toString();
                case Boolean flag -> flag.toString();
                case Instant instant -> '"' + instant.toString() + '"';
                case LocalDate date -> '"' + date.toString() + '"';
                case Enum<?> constant -> '"' + constant.name() + '"';
                case Serializable other -> '"' + escape(String.valueOf(other)) + '"';
                default -> '"' + escape(String.valueOf(value)) + '"';
            };
        }

        private static String escape(String text) {
            StringBuilder escaped = new StringBuilder(text.length() + 8);
            for (int at = 0; at < text.length(); at++) {
                char symbol = text.charAt(at);
                switch (symbol) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (symbol < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) symbol));
                        } else {
                            escaped.append(symbol);
                        }
                    }
                }
            }
            return escaped.toString();
        }
    }
}
