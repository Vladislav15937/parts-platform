package ru.partsflow.inventory;

import ru.partsflow.shared.PartNumberQuery;
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
 * 2007 лев. · б/у — нужно 1, свободно 0». Три сообщения об одном и том же
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
     * «Фара Toyota Camry 2007 лев. · б/у (номер товара 300 001)».
     *
     * <p>Заголовок и Номер товара вместе: по заголовку деталь узнают на экране,
     * по номеру — на этикетке, наклеенной на неё же (задача 0366: этикетка
     * кодирует Номер товара, а не публичный код).
     *
     * <p>Слово состояния — сразу за наименованием, тем же разделителем
     * и тем же словарём, что у остальных отказов ({@link PartNaming#format},
     * задача 0321): «Фара… новая» и «Фара… б/у» — две разные позиции, а после
     * 0315 пометки «(б/у)» в наименовании нет. Код в скобках остаётся последним:
     * слово стоит рядом с тем, что оно уточняет, а код — то, по чему ищут
     * этикетку. Пустого состояния (0322) — слова нет. Номера производителя
     * здесь нет: на складе позицию называет код, и его хватает (номер просили
     * в отказах продаж и приёмки, 0320).
     */
    @Transactional(readOnly = true)
    public String part(Long partId) {
        if (partId == null) {
            return "деталь не указана";
        }
        return jdbc.query("SELECT title, condition, number FROM part WHERE id = ?",
                rs -> rs.next()
                        ? "%s (номер товара %s)".formatted(
                                PartNaming.format(rs.getString("title"),
                                        conditionOf(rs.getString("condition")), null),
                                PartNumberQuery.format(rs.getLong("number")))
                        // Позиции нет — значит её удалили между проверкой
                        // и отказом. Номер строки человеку не говорит ничего,
                        // а искать по нему нечего: карточки больше нет.
                        : "удалённая позиция",
                partId);
    }

    private static PartCondition conditionOf(String code) {
        return code == null ? null : PartCondition.valueOf(code);
    }

    /** «Основной» — так склад зовут на экране, а не «склад 2». */
    @Transactional(readOnly = true)
    public String warehouse(Long warehouseId) {
        if (warehouseId == null) {
            return "склад не указан";
        }
        return jdbc.query("SELECT name FROM warehouse WHERE id = ?",
                rs -> rs.next() ? "«" + rs.getString("name") + "»" : "неизвестный склад",
                warehouseId);
    }
}
