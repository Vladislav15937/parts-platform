package ru.partsflow.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Как назвать деталь и склад человеку, которому отказали.
 *
 * <p><b>Зачем.</b> Отказ «На складе свободно 0, а требуется 1: деталь 6»
 * называет позицию внутренним номером, которого владелец никогда не видел,
 * а склад — либо номером, либо никак. Кладовщик, увозящий пять позиций одним
 * документом, по такому ответу не поймёт даже, на какой строке споткнулся.
 * Продажа то же самое говорит правильно с самого начала: «Фара Toyota Camry
 * 2007 лев. (б/у) — нужно 1, свободно 0». Три сообщения об одном и том же
 * факте были написаны в трёх стилях; здесь один.
 *
 * <p>Запрос делается только на пути отказа, поэтому лишним он не бывает:
 * успешная перевозка в базу за именами не ходит.
 *
 * <p>Внутри транзакции — как и всё, что трогает схему арендатора: снаружи
 * {@code JdbcTemplate} берёт соединение из пула напрямую и уходит в
 * {@code public}. Оба вызывающих работают внутри своей транзакции.
 */
@Service
public class StockNaming {

    private final JdbcTemplate jdbc;

    public StockNaming(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * «Фара Toyota Camry 2007 лев. (б/у) (55747F0F91CD)».
     *
     * <p>Заголовок и код товара вместе: по заголовку деталь узнают на экране,
     * по коду — на этикетке, наклеенной на неё же.
     */
    @Transactional(readOnly = true)
    public String part(Long partId) {
        if (partId == null) {
            return "деталь не указана";
        }
        return jdbc.query("SELECT title, public_code FROM part WHERE id = ?",
                rs -> rs.next()
                        ? "%s (%s)".formatted(rs.getString("title"), rs.getString("public_code"))
                        // Позиции нет — значит её удалили между проверкой
                        // и отказом; номер тут единственное, что осталось.
                        : "деталь " + partId,
                partId);
    }

    /** «Основной» — так склад зовут на экране, а не «склад 2». */
    @Transactional(readOnly = true)
    public String warehouse(Long warehouseId) {
        if (warehouseId == null) {
            return "склад не указан";
        }
        return jdbc.query("SELECT name FROM warehouse WHERE id = ?",
                rs -> rs.next() ? "«" + rs.getString("name") + "»" : "склад " + warehouseId,
                warehouseId);
    }
}
