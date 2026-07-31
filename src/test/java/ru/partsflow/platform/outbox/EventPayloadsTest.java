package ru.partsflow.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.partsflow.platform.outbox.contract.DealEvent;
import ru.partsflow.platform.outbox.contract.PartEvent;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт payload'а доменных событий.
 *
 * <p>До этого он собирался конкатенацией строк с самодельным экранированием,
 * и на обычном складском наименовании получался невалидный JSON. Никто
 * не замечал: тело события пока не разбирает ни один потребитель.
 */
class EventPayloadsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Наименование с кавычками, слешем и переводом строки переживает проводом")
    void hostileTitleSurvives() throws Exception {
        // Ровно то, что приезжает импортом из чужой таблицы: дробь в числе
        // ступеней, кавычки вокруг типа коробки, перевод строки из ячейки.
        String title = "КПП 5\\6 ступ. \"робот\"\nбез навесного";

        byte[] payload = EventPayloads.write(
                new PartEvent(7L, "P-7", title, new BigDecimal("15000.50"), "IN_STOCK"));

        // Прежняя сборка строкой давала здесь «\6» — недопустимую
        // escape-последовательность, то есть payload, который не разбирается.
        var parsed = mapper.readTree(payload);
        assertThat(parsed.get("title").asText())
                .as("наименование доехало искажённым — потребитель увидит не ту деталь")
                .isEqualTo(title);
    }

    @Test
    @DisplayName("Имена полей на проводе зафиксированы")
    void wireFieldNamesArePinned() throws Exception {
        // Переименование поля в record'е незаметно для компилятора и ломает
        // потребителя молча. Версия события живёт в имени типа (.v1),
        // и несовместимое изменение обязано быть .v2, а не правкой этого.
        var part = mapper.readTree(EventPayloads.write(
                new PartEvent(1L, "P-1", "Фара", new BigDecimal("100"), "IN_STOCK")));
        assertThat(part.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "publicCode", "title", "price", "status");

        var deal = mapper.readTree(EventPayloads.write(
                new DealEvent(1L, 42L, "ISSUED", new BigDecimal("100"), new BigDecimal("100"))));
        assertThat(deal.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "number", "status", "total", "paid");
    }

    @Test
    @DisplayName("Сумма едет числом, а не строкой")
    void amountIsANumber() throws Exception {
        var parsed = mapper.readTree(EventPayloads.write(
                new DealEvent(1L, 42L, "ISSUED", new BigDecimal("15000.50"), BigDecimal.ZERO)));

        // Строкой она вынудила бы каждого потребителя гадать про формат,
        // а первый же разбор через parseFloat потерял бы копейки.
        assertThat(parsed.get("total").isNumber()).isTrue();
        assertThat(parsed.get("total").decimalValue()).isEqualByComparingTo("15000.50");
    }

    @Test
    @DisplayName("Поле, добавленное издателем, не ломает потребителя старой версии")
    void unknownFieldIsIgnored() {
        byte[] payload = """
                {"id":7,"publicCode":"P-7","title":"Фара","price":100,
                 "status":"IN_STOCK","warrantyDays":30}"""
                .getBytes(StandardCharsets.UTF_8);

        // Иначе расширение контракта требует одновременной выкладки издателя
        // и всех потребителей, то есть контракта нет вовсе.
        PartEvent read = EventPayloads.read(
                new ConsumedEvent(1, "part", 7, "part.created.v1", payload), PartEvent.class);

        assertThat(read.title()).isEqualTo("Фара");
    }

    @Test
    @DisplayName("Нечитаемое тело — отказ, а не пустой объект")
    void brokenPayloadFails() {
        var event = new ConsumedEvent(9, "part", 7, "part.created.v1",
                "не json".getBytes(StandardCharsets.UTF_8));

        // Событие с мусором в теле обязано уехать в event_dead_letter
        // и дождаться человека: тихо обработанное «ничего» не отличить
        // от обработанного события.
        assertThatThrownBy(() -> EventPayloads.read(event, PartEvent.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9");
    }
}
