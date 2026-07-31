package ru.partsflow.platform.outbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;

/**
 * Сериализация payload'ов доменных событий.
 *
 * <p><b>Заведено вместо сборки JSON конкатенацией.</b> Три модуля собирали
 * payload строкой с самодельным экранированием {@code title.replace("\"",
 * "\\\"")}, и оно неверно: наименование «КПП 5\6 ступ.» давало
 * {@code "КПП 5\6"}, где {@code \6} — недопустимая escape-последовательность.
 * Такие написания приезжают импортом из чужой таблицы пачками. Перевод
 * строки в наименовании ломал payload так же. Не замечали этого только
 * потому, что payload пока никто не разбирает: {@code DealIssuedHandler}
 * работает по идентификатору агрегата. Первый же потребитель, читающий тело,
 * наткнулся бы на это в бою — на данных, которые в базе уже лежат.
 *
 * <p><b>Почему не Protobuf со Schema Registry, как в архитектуре.</b> Их
 * ценность — согласование схемы между издателем и потребителем, которые
 * выкладываются порознь. Сейчас это один процесс и один артефакт: реестр схем
 * добавил бы контейнер, кодогенерацию в сборке и привязку к Confluent —
 * ровно то, от чего отгораживает {@link EventTransport}. Контракт при этом
 * нужен уже сейчас, и его даёт record: поле не переименуешь молча, а
 * {@code EventPayloadsTest} стережёт имена на проводе. К Protobuf возвращаться,
 * когда события начнёт читать что-то, выкладываемое отдельно от нас.
 *
 * <p>Неизвестные поля при чтении игнорируются: потребитель старой версии
 * обязан пережить добавленное издателем поле, иначе любое расширение
 * контракта требует одновременной выкладки обоих.
 */
public final class EventPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Числом, а не строкой: 1000.00 должно приезжать потребителю
            // числом, иначе разбор зависит от того, чем он написан.
            .disable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);

    private EventPayloads() {
    }

    public static byte[] write(Object payload) {
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (IOException e) {
            // Payload, который не сериализуется, — это ошибка в коде, а не
            // сбой: молча опубликовать событие без тела нельзя, его потом
            // не восстановить.
            throw new IllegalStateException("Не удалось собрать payload события", e);
        }
    }

    public static <T> T read(ConsumedEvent event, Class<T> type) {
        try {
            return MAPPER.readValue(event.payload(), type);
        } catch (IOException e) {
            // Событие с нечитаемым телом уедет в event_dead_letter и дождётся
            // человека — в отличие от разбора, съевшего исключение.
            throw new IllegalStateException(
                    "Событие %d (%s): payload не разобран".formatted(
                            event.eventId(), event.eventType()), e);
        }
    }
}
