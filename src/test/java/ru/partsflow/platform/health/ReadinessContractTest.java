package ru.partsflow.platform.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Договор между ответом готовности и шагом выкладки — пиннится с двух сторон.
 *
 * <p><b>Зачем.</b> Третья самопроверка выкладки различает три исхода: «схем
 * позади нет», «схемы отстали» и «спросить не вышло» (задача 0202). Последний
 * она узнавала <b>по вхождению фразы</b> «не проверены» в текст ответа, и
 * пиннящего теста у фразы не было ни с одной стороны. Живой прогон настоящего
 * разбора показал, чем это кончается: та же ситуация с фразой в единственном
 * числе — «Версия схем арендаторов не проверена», как у трёх соседних проверок
 * того же ответа, — даёт исход «СХЕМЫ ОТСТАЛИ», то есть <b>худшее из двух</b>
 * состояний при схемах, про которые ничего не известно. Дефект 0202 стоил
 * 37 секунд заморозки записи у живого клиента, и возвращало его одно
 * переписанное слово.
 *
 * <p>Теперь исход несёт машинный признак, а этот тест держит договор с обеих
 * сторон: он читает {@code ops/deploy-checks.sh}, достаёт оттуда <b>имя поля
 * и имя проверки</b> и требует их у самого ответа. Краснеет поэтому и Java,
 * перестав отдавать поле, и шелл, вернувшийся к узнаванию исхода по слову.
 *
 * <p><b>Договора Java ↔ шелл в проекте до этого не было ни одного</b> — ни один
 * файл под {@code ops/} не читает {@code src/main/java}, и ни один Java-тест
 * не читал {@code ops/*.sh}. Форма взята у {@code WordingConsistencyTest}
 * («слова двух поверхностей сверяет один тест, читающий оба файла»): она
 * дешевле общего файла-источника ({@code ops/images.yml}) и не требует
 * тащить значение внутрь jar, а на ячейке из репозитория лежит только
 * {@code ops/} — общий файл пришлось бы кому-то из двоих читать не оттуда.
 *
 * <p><b>Чего этот тест не покрывает, названо вслух.</b> Смысл поля: проверка,
 * поставившая признак наугад, тестом по тексту не ловится — её стерегут
 * утверждения о <b>живом</b> ответе ({@code AppReadinessTest} про отставшую
 * схему, {@code StartOnFrozenDatabaseTest} про непроверенную запись). Он
 * не сверяет остальные фразы ответа — машинно их не читает никто. И не
 * покрывает сборку старше признака, которая переименовала бы свою фразу:
 * такой не бывает, фраза уезжает вместе с jar.
 */
class ReadinessContractTest {

    private static final Path CHECKS = Path.of("ops", "deploy-checks.sh");

    /** Поля {@code Check}, которые полем исхода быть не могут. */
    private static final Set<String> NOT_THE_FLAG = Set.of("check", "ok", "detail");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("Исход «спросить не вышло» шаг выкладки узнаёт по полю, а не по фразе")
    void outcomeIsCarriedByField() throws IOException {
        String verdict = verdictSchemas();
        String field = flagField(verdict);

        assertThat(serialized(false))
                .as("ответ готовности не несёт поля «%s», которое читает "
                        + "ops/deploy-checks.sh: шаг выкладки молча скатится к разбору "
                        + "по фразе — то есть к дефекту 0202, где «спросить не вышло» "
                        + "объявляется отставанием схем", field)
                .contains("\"" + field + "\":false");
        assertThat(serialized(true))
                .as("поле «%s» не отличает выясненное состояние от невыясненного: "
                        + "по такому ответу три исхода снова сводятся к двум", field)
                .contains("\"" + field + "\":true");
    }

    @Test
    @DisplayName("Разбор по фразе — только запасной путь, и он под признаком")
    void phraseIsOnlyTheFallback() throws IOException {
        String verdict = verdictSchemas();
        String field = flagField(verdict);

        List<String> phraseLines = new ArrayList<>();
        for (String line : verdict.split("\n")) {
            if (line.contains("не проверен") && !line.strip().startsWith("#")) {
                phraseLines.add(line);
            }
        }

        assertThat(phraseLines)
                .as("фразу «не проверен…» в разборе исхода читают в %d местах: "
                        + "запасной путь один, и второй разошёлся бы с ним",
                        phraseLines.size())
                .hasSize(1);
        assertThat(phraseLines.get(0))
                .as("исход по фразе решается БЕЗ признака «%s» — то есть фраза "
                        + "не запасной путь, а по-прежнему главный: переименование "
                        + "слова в Java снова уводит исход в «схемы отстали». Строка: %s",
                        field, phraseLines.get(0))
                .contains(field);
    }

    @Test
    @DisplayName("Имя проверки у готовности и у шага выкладки одно и то же")
    void checkNameMatches() throws IOException {
        String name = checkName(verdictSchemas());

        assertThat(read(endpointSource()))
                .as("шаг выкладки ищет в ответе проверку «%s», а готовность такой "
                        + "не отдаёт: третья самопроверка уйдёт в «СПРОСИТЬ НЕ ВЫШЛО» "
                        + "на исправной ячейке — и останется там навсегда", name)
                .contains("\"" + name + "\"");
    }

    /** Тело {@code verdict_schemas} — только оно, чтобы не поймать соседний разбор. */
    private static String verdictSchemas() throws IOException {
        String text = read(CHECKS);
        int from = text.indexOf("verdict_schemas()");
        assertThat(from)
                .as("в %s нет функции verdict_schemas — договор читать негде", CHECKS)
                .isNotNegative();
        int to = text.indexOf("check_schemas()", from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
    }

    /**
     * Имя поля, по которому шелл узнаёт исход.
     *
     * <p>Достаётся из самого файла, а не пишется здесь литералом: тест,
     * знающий имя поля по памяти, зеленел бы на шелле, который это поле
     * больше не читает, — то есть охранял бы одну сторону договора из двух.
     */
    private static String flagField(String verdict) {
        Matcher reads = Pattern.compile("check\\.get\\('([A-Za-z_]+)'\\)").matcher(verdict);
        List<String> fields = new ArrayList<>();
        while (reads.find()) {
            String field = reads.group(1);
            if (!NOT_THE_FLAG.contains(field) && !fields.contains(field)) {
                fields.add(field);
            }
        }
        assertThat(fields)
                .as("ops/deploy-checks.sh не читает у проверки schemas ни одного поля, "
                        + "кроме check/ok/detail, — значит исход он снова узнаёт "
                        + "по слову в тексте, и одно переименование в Java возвращает "
                        + "дефект 0202 целиком")
                .hasSize(1);
        return fields.get(0);
    }

    /** Имя проверки, которую шелл ищет в ответе. */
    private static String checkName(String verdict) {
        Matcher looksFor = Pattern.compile("check\\.get\\('check'\\) == '([a-z]+)'")
                .matcher(verdict);
        assertThat(looksFor.find())
                .as("в verdict_schemas не видно, какую проверку ответа он ищет")
                .isTrue();
        return looksFor.group(1);
    }

    private String serialized(boolean known) throws JsonProcessingException {
        return json.writeValueAsString(new ReadinessEndpoint.Check(
                "schemas", false, "Версии схем арендаторов не проверены: причина", known));
    }

    /** Путь от корня репозитория: тесты Maven гоняет из него же. */
    private static Path endpointSource() {
        return Path.of("src", "main", "java", "ru", "partsflow", "platform", "health",
                "ReadinessEndpoint.java");
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("файл, по которому сверяется договор, исчез").exists();
        return Files.readString(path);
    }
}
