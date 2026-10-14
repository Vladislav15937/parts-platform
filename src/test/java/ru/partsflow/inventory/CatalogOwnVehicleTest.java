package ru.partsflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Колонки машины на витрине у позиции без донора — своя машина позиции
 * из её применимости, и подбор по машине видит такие позиции (задача 0290).
 *
 * <p>Склад устроен как у клиента, перенесённого с Дрома: донора нет ни у одной
 * позиции, машина названа в наименовании, а применимость — наименование
 * плюс блок «Подходит». «Карданный вал … Prado 120» подходит к 4Runner
 * и Hilux Surf, и в колонке «Марка / Модель» обязан стоять Prado, а не первая
 * машина блока: решение владельца продукта 14.10.2026 («Только ту марку
 * модель которой и является запчасть. Для всего остального есть
 * применимость»).
 *
 * <p>Всё через HTTP, как экран: колонки — {@code GET /api/parts/catalog},
 * список отбора — {@code /values}, подбор — {@code /vehicles}, файл —
 * {@code /export}. Справочник машин настоящий ({@code catalog/009}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogOwnVehicleTest extends PostgresTestBase {

    private static final String TENANT = "t_000992";
    private static final int REGISTRY_ID = 992;
    private static final String COMPANY = "ownvehicle";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();
    private MockHttpSession session;

    private long toyota;
    private long prado;
    private long cruiser;
    private long runner;
    private long surf;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = ?", REGISTRY_ID);
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (?, ?, 'Разборка с Дрома: своя машина', ?)""", REGISTRY_ID, TENANT, COMPANY);
        toyota = jdbc.queryForObject("SELECT id FROM catalog.brand WHERE name = 'Toyota'", Long.class);
        prado = model("Land Cruiser Prado");
        cruiser = model("Land Cruiser");
        runner = model("4Runner");
        surf = model("Hilux Surf");
        inTenant(() -> {
            jdbc.update("DELETE FROM part_applicability");
            jdbc.update("DELETE FROM part_change");
            jdbc.update("DELETE FROM part");
            jdbc.update("DELETE FROM donor");
            if (jdbc.queryForList("SELECT id FROM tenant_member WHERE login = 'owner'").isEmpty()) {
                jdbc.update("""
                        INSERT INTO tenant_member (display_name, role, login, password_hash)
                        VALUES ('Владелец', 'OWNER', 'owner', ?)""", passwordEncoder.encode("пароль"));
            }
            return null;
        });
        session = login();
    }

    // --- 1, 2: колонки показывают свою машину, источник назван ---

    @Test
    @DisplayName("Без донора: марка и модель — машина из наименования, а не первая машина блока")
    void ownCarIsTheOneNamedInTheTitle() throws Exception {
        long part = part("Карданный вал передний (Пробег 130т. км) Toyota Land Cruiser Prado 120");
        // Порядок строк — как кладёт разбор: сначала машины блока «Подходит»,
        // потом строка из наименования. Первая по номеру — 4Runner.
        row(part, runner, generation(runner, 2009), false);
        row(part, surf, generation(surf, 2002), false);
        // «Land Cruiser» тоже стоит в наименовании — внутри «Land Cruiser Prado».
        row(part, cruiser, null, false);
        // Кузова того же ряда, что «120» в наименовании: без них поколение
        // не подтверждено (0299), а тест не про это.
        row(part, prado, generation(prado, 2002), false, "Кузова: GRJ120W, KDJ121W");

        JsonNode row = only(page(""));
        assertThat(row.path("brand").asText()).isEqualTo("Toyota");
        assertThat(row.path("model").asText())
                .as("своя машина — названная в наименовании, длиннейшая на одном месте")
                .isEqualTo("Land Cruiser Prado");
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2002);
        assertThat(row.path("generation").isNull()).isFalse();
        assertThat(row.path("vehicleSource").asText()).isEqualTo("PARSED");
        // Кузова и двигателя у применимости нет — пусто, как и было.
        assertThat(row.path("body").isNull()).isTrue();
        assertThat(row.path("engine").isNull()).isTrue();
    }

    @Test
    @DisplayName("Несколько поколений своей машины — поколение и годы пусты, марка и модель есть")
    void severalGenerationsAreNotGuessed() throws Exception {
        long part = part("Суппорт задний правый Toyota Land Cruiser Prado 120");
        row(part, prado, generation(prado, 2009), false);
        row(part, prado, generation(prado, 2002), false);

        JsonNode row = only(page(""));
        assertThat(row.path("model").asText()).isEqualTo("Land Cruiser Prado");
        assertThat(row.path("generation").isNull())
                .as("первое поколение блока у «Prado 120» было бы догадкой").isTrue();
        assertThat(row.path("yearFrom").isNull()).isTrue();
        assertThat(row.path("vehicleSpans").asInt()).isEqualTo(2);
    }

    // --- задача 0294: номер кузова в наименовании против кузовов блока ---

    /**
     * «Втулка амортизатора … Prado 120» (строка 924 файла клиента): блок
     * «Подходит» назвал у Prado одно поколение — 2009 года, кузова 150-го
     * ряда. Правило 0290 «одно в блоке — значит своё» показало бы его
     * в колонке как факт; деталь же от 120-го.
     */
    @Test
    @DisplayName("«Prado 120», а блок назвал одно поколение кузовов 150 — поколение пусто, причина названа")
    void titleBodyDisagreesWithTheOnlyGeneration() throws Exception {
        long part = part("Втулка амортизатора зад (TEMS)Toyota Land Cruiser Prado 120");
        row(part, prado, generation(prado, 2009), false,
                "Кузова: GRJ150W, GRJ150L, KDJ150L, KDJ150, GRJ150, GRJ151W");
        row(part, prado, null, false, null);

        JsonNode row = only(page(""));
        assertThat(row.path("model").asText()).isEqualTo("Land Cruiser Prado");
        assertThat(row.path("vehicleSpans").asInt()).isEqualTo(1);
        assertThat(row.path("generation").isNull())
                .as("поколение 150-го ряда у детали от 120-го — чужое").isTrue();
        assertThat(row.path("yearFrom").isNull()).isTrue();
        assertThat(row.path("yearTo").isNull()).isTrue();
        assertThat(row.path("vehicleTitleBody").asText()).isEqualTo("120");
        assertThat(row.path("vehicleBlockBodies").asText()).isEqualTo("150, 151");

        // Все поверхности — одно выражение: отбор, список значений, файл.
        String shown = inTenant(() -> jdbc.queryForObject(
                "SELECT name FROM catalog.generation WHERE id = ?", String.class,
                generation(prado, 2009)));
        assertThat(titles(page("&filter=generation:" + shown))).isEmpty();
        assertThat(values("generation")).doesNotContain(shown);
        assertThat(export()).contains("\"Toyota\";\"Land Cruiser Prado\";\"\";\"\"")
                .doesNotContain("2009");
    }

    @Test
    @DisplayName("«Prado 200», а блок назвал одно поколение кузовов 250 — поколение пусто")
    void prado200IsNot250() throws Exception {
        long part = part("Направляющая суппорта переднего Toyota Land Cruiser Prado 200");
        row(part, prado, generation(prado, 2023), false, "Кузова: TJA250, GDJ250, TRJ250");

        JsonNode row = only(page(""));
        assertThat(row.path("generation").isNull()).isTrue();
        assertThat(row.path("yearFrom").isNull()).isTrue();
        assertThat(row.path("vehicleTitleBody").asText()).isEqualTo("200");
        assertThat(row.path("vehicleBlockBodies").asText()).isEqualTo("250");
    }

    /**
     * Положительный контроль (строки 354, 1521, 1564 файла): «Prado 120»,
     * поколение 2002 с кузовами 121 — тот же ряд «12». Правило не должно
     * потерять верное.
     */
    @Test
    @DisplayName("«Prado 120» и кузова 121 того же ряда — поколение показано, как было")
    void sameSeriesKeepsTheGeneration() throws Exception {
        long part = part("Мотор печки Toyota Land Cruiser Prado 120");
        row(part, prado, generation(prado, 2002), false, "Кузова: GRJ121W, VZJ121W, KDJ121W");

        JsonNode row = only(page(""));
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2002);
        assertThat(row.path("generation").isNull()).isFalse();
        assertThat(row.path("vehicleTitleBody").isNull()).isTrue();
        assertThat(row.path("vehicleBlockBodies").isNull()).isTrue();
        assertThat(titles(page("&filter=generation:" + row.path("generation").asText())))
                .containsExactly("Мотор печки Toyota Land Cruiser Prado 120");
    }

    /**
     * «Накладка кнопки стеклоподъемника … Prado 12» (строка 1675 файла).
     * PR 419 читал «12» как сокращение 120-го ряда и показывал 2002. Решение
     * владельца 14.10.2026 (задача 0299): поколение — только при уверенности,
     * а «12» сокращением не считается. Ряд двузначного номера — «1»
     * (правило 0294 «число без последней цифры»), с кузовами 120 он
     * не совпадает: пусто, и подсказка называет, что с чем не сошлось.
     */
    @Test
    @DisplayName("«Prado 12» — не сокращение 120-го: поколение пусто и при кузовах 120, и при 150")
    void twoDigitNumberIsNotAShorthand() throws Exception {
        long own = part("Накладка кнопки стеклоподъемника заднее R Toyota Land Cruiser Prado 12");
        row(own, prado, generation(prado, 2002), false, "Кузова: GRJ120W, KDJ121W");
        long alien = part("Молдинг двери Toyota Land Cruiser Prado 12");
        row(alien, prado, generation(prado, 2009), false, "Кузова: GRJ150W");

        JsonNode rows = page("&sort=number").path("rows");
        JsonNode first = rows.get(0);
        assertThat(first.path("yearFrom").isNull())
                .as("«Prado 12» при кузовах 120 — не подтверждено, значит пусто").isTrue();
        assertThat(first.path("generation").isNull()).isTrue();
        assertThat(first.path("vehicleTitleBody").asText()).isEqualTo("12");
        assertThat(first.path("vehicleBlockBodies").asText()).isEqualTo("120, 121");
        assertThat(rows.get(1).path("yearFrom").isNull()).isTrue();
    }

    @Test
    @DisplayName("«Lexus RX350» — число вплотную к модели и есть модель: правило не применяется")
    void numberGluedToTheModelIsTheModel() throws Exception {
        long lexus = brand("Lexus");
        long rx = model(lexus, "RX350");
        long generation = firstGeneration(rx);
        long part = part("Фара левая Lexus RX350");
        inTenant(() -> jdbc.update("""
                INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id,
                                                is_verified, note)
                VALUES (?, ?, ?, ?, false, 'Кузова: GGL15W, GGL10W')""",
                part, lexus, rx, generation));

        JsonNode row = only(page(""));
        assertThat(row.path("model").asText()).isEqualTo("RX350");
        assertThat(row.path("generation").isNull())
                .as("у «RX350» нет номера кузова после модели — сверять не с чем").isFalse();
        assertThat(row.path("vehicleTitleBody").isNull()).isTrue();

        // В справочнике «RX350» — целая модель, и до числа дело не доходит.
        // Вплотную к модели, у которой в справочнике номера нет, число тоже
        // не отдельный токен: правило 0294 его не читает.
        long glued = part("Фара правая Toyota Land Cruiser Prado150");
        row(glued, prado, generation(prado, 2009), false, "Кузова: GRJ150W");
        long gluedOther = part("Фара левая Toyota Land Cruiser Prado120");
        row(gluedOther, prado, generation(prado, 2009), false, "Кузова: GRJ150W");
        JsonNode pradoRows = page("&filter=model:Land Cruiser Prado").path("rows");
        assertThat(pradoRows).hasSize(2);
        for (JsonNode found : pradoRows) {
            assertThat(found.path("yearFrom").asInt()).as(found.path("title").asText())
                    .isEqualTo(2009);
        }
    }

    /**
     * 14 позиций «Prado 150» файла клиента: номер в наименовании есть, а строка
     * поколения кузовов не называет. PR 419 ставил поколение по правилу 0290;
     * решение владельца 14.10.2026 (задача 0299) — сверить нечем, значит
     * не подтверждено, значит пусто. Машина при этом остаётся: марка и модель
     * на месте, пусты только поколение и годы — на всех поверхностях.
     *
     * <p>Без номера в наименовании правило 0290 не меняется: та же строка
     * поколения без кузовов у «Рейлинга … Prado» показывается, как была.
     */
    @Test
    @DisplayName("Номер кузова в наименовании, а блок кузовов не назвал — поколение пусто, причина названа")
    void noBodiesInTheBlockConfirmsNothing() throws Exception {
        long part = part("Рейлинг на крышу Toyota Land Cruiser Prado 150");
        row(part, prado, generation(prado, 2009), false, null);
        long plain = part("Рейлинг на крышу правый Toyota Land Cruiser Prado");
        row(plain, prado, generation(prado, 2009), false, null);

        JsonNode rows = page("&sort=number").path("rows");
        JsonNode numbered = rows.get(0);
        assertThat(numbered.path("model").asText()).isEqualTo("Land Cruiser Prado");
        assertThat(numbered.path("generation").isNull())
                .as("«150» в наименовании, а сверить не с чем — не подтверждено").isTrue();
        assertThat(numbered.path("yearFrom").isNull()).isTrue();
        assertThat(numbered.path("yearTo").isNull()).isTrue();
        assertThat(numbered.path("vehicleTitleBody").asText()).isEqualTo("150");
        assertThat(numbered.path("vehicleBlockBodies").isNull())
                .as("кузовов у строки поколения нет — подсказке назвать нечего").isTrue();

        JsonNode unnumbered = rows.get(1);
        assertThat(unnumbered.path("yearFrom").asInt())
                .as("номера в наименовании нет — правило 0290 как было").isEqualTo(2009);
        assertThat(unnumbered.path("vehicleTitleBody").isNull()).isTrue();

        // Все поверхности — одно выражение: отбор, список значений, файл.
        String shown = unnumbered.path("generation").asText();
        assertThat(titles(page("&filter=generation:" + shown)))
                .containsExactly("Рейлинг на крышу правый Toyota Land Cruiser Prado");
        assertThat(export()).contains("\"Рейлинг на крышу Toyota Land Cruiser Prado 150\";\"\";"
                + "\"Toyota\";\"Land Cruiser Prado\";\"\";\"\"");
    }

    @Test
    @DisplayName("Номер через косую («Prado 120/150») — не один кузов: правило не применяется")
    void slashListIsNotOneBody() throws Exception {
        long part = part("Датчик ABS передний левый Toyota Land Cruiser Prado 120/150");
        row(part, prado, generation(prado, 2009), false, "Кузова: GRJ150W, GRJ151W");

        assertThat(only(page("")).path("yearFrom").asInt()).isEqualTo(2009);
    }

    // --- задача 0297: рестайлинги одного поколения Дрома ---

    /**
     * Блок «Подходит» называет у Prado одно поколение Дрома с рестайлингами:
     * «4 поколение (2009–2013); 4 поколение, рестайлинг (2013–2017);
     * 4 поколение, 2-й рестайлинг (2017–2024)». Наш справочник держит их тремя
     * строками, и правило 0290 «одно поколение» видело три. Ответ владельца
     * 14.10.2026 — «целиком»: «4 поколение», годы от первой строки до последней.
     */
    @Test
    @DisplayName("Три рестайлинга одного поколения Дрома — колонка «4 поколение», 2009–2024, на всех поверхностях")
    void restylingsOfOneDromGenerationShowTheWholeGeneration() throws Exception {
        long part = part("Суппорт тормозной передний левый Toyota Land Cruiser Prado");
        drom(part, 2009, 2013, "Кузова: GRJ150W, KDJ150L", "4 поколение");
        drom(part, 2013, 2017, "Кузова: GRJ150W", "4 поколение");
        drom(part, 2017, 2024, "Кузова: GDJ150W", "4 поколение");
        long other = part("Суппорт тормозной передний правый Toyota Land Cruiser Prado");
        drom(other, 2002, 2009, "Кузова: GRJ120W", "3 поколение");
        drom(other, 2009, 2013, "Кузова: GRJ150W", "4 поколение");

        JsonNode rows = page("&sort=number").path("rows");
        JsonNode row = rows.get(0);
        assertThat(row.path("generation").asText())
                .as("поколение Дрома целиком, словами клиента").isEqualTo("4 поколение");
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2009);
        assertThat(row.path("yearTo").asInt()).as("до последнего рестайлинга").isEqualTo(2024);
        assertThat(row.path("vehicleSpans").asInt()).isEqualTo(3);
        assertThat(row.path("vehicleGenerationMerged").asBoolean()).isTrue();
        assertThat(rows.get(1).path("generation").isNull())
                .as("3 и 4 поколение — метка двух поколений не придумывается").isTrue();
        assertThat(rows.get(1).path("vehicleGenerationMerged").asBoolean()).isFalse();

        // Все поверхности — одно выражение: отбор, сортировка, список значений, файл.
        assertThat(titles(page("&filter=generation:4 поколение")))
                .containsExactly("Суппорт тормозной передний левый Toyota Land Cruiser Prado");
        assertThat(titles(page("&find=generation:поколение")))
                .containsExactly("Суппорт тормозной передний левый Toyota Land Cruiser Prado");
        assertThat(values("generation")).containsExactly("4 поколение");
        assertThat(titles(page("&sort=generation")).get(0))
                .isEqualTo("Суппорт тормозной передний левый Toyota Land Cruiser Prado");
        // В файле поколения нет — только «Поколение с» и «Поколение по».
        assertThat(export()).contains("\"Суппорт тормозной передний левый Toyota Land Cruiser Prado\";"
                + "\"\";\"Toyota\";\"Land Cruiser Prado\";\"2009\";\"2024\"");
    }

    @Test
    @DisplayName("Одна строка с меткой — колонка называет поколение Дрома, а не диапазон справочника")
    void singleLabelledRowIsNamedByDrom() throws Exception {
        long part = part("Фара левая Toyota Land Cruiser Prado");
        drom(part, 2013, 2017, "Кузова: GRJ150W", "4 поколение");

        JsonNode row = only(page(""));
        assertThat(row.path("generation").asText()).isEqualTo("4 поколение");
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2013);
        assertThat(row.path("yearTo").asInt()).isEqualTo(2017);
        assertThat(row.path("vehicleGenerationMerged").asBoolean())
                .as("строка одна — объединять было нечего").isFalse();
    }

    @Test
    @DisplayName("Строка без метки рядом с рестайлингами (разобрана до 0297) — сомнение, поколение пусто")
    void unlabelledRowKeepsItEmpty() throws Exception {
        long part = part("Зеркало правое Toyota Land Cruiser Prado");
        drom(part, 2009, 2013, "Кузова: GRJ150W", "4 поколение");
        drom(part, 2013, 2017, "Кузова: GRJ150W", null);

        JsonNode row = only(page(""));
        assertThat(row.path("generation").isNull()).isTrue();
        assertThat(row.path("yearFrom").isNull()).isTrue();
        assertThat(row.path("vehicleSpans").asInt()).as("подсказка «поколений 2», как было").isEqualTo(2);
    }

    @Test
    @DisplayName("Последний рестайлинг «н.в.» — и у поколения целиком год конца пуст")
    void upToNowStaysOpen() throws Exception {
        long part = part("Бампер Toyota Land Cruiser Prado");
        drom(part, 2009, 2013, null, "4 поколение");
        drom(part, 2013, null, null, "4 поколение");

        JsonNode row = only(page(""));
        assertThat(row.path("generation").asText()).isEqualTo("4 поколение");
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2009);
        assertThat(row.path("yearTo").isNull()).as("«н.в.», а не год справочника").isTrue();
    }

    /**
     * Сверка 0294/0299 идёт по строкам до объединения: номер кузова
     * в наименовании обязан подтвердиться у каждого рестайлинга. Одна строка
     * без кузовов или с чужим рядом — всё поколение Дрома не подтверждено.
     */
    @Test
    @DisplayName("Номер кузова в наименовании сверяется с каждой строкой до объединения рестайлингов")
    void bodyNumberIsCheckedPerRowBeforeMerging() throws Exception {
        long confirmed = part("Капот Toyota Land Cruiser Prado 150");
        drom(confirmed, 2009, 2013, "Кузова: GRJ150W, KDJ150L", "4 поколение");
        drom(confirmed, 2013, 2017, "Кузова: GRJ151W", "4 поколение");
        long oneWithout = part("Крыло Toyota Land Cruiser Prado 150");
        drom(oneWithout, 2009, 2013, "Кузова: GRJ150W", "4 поколение");
        drom(oneWithout, 2013, 2017, null, "4 поколение");
        long alien = part("Дверь Toyota Land Cruiser Prado 120");
        drom(alien, 2009, 2013, "Кузова: GRJ150W", "4 поколение");
        drom(alien, 2013, 2017, "Кузова: GRJ150W", "4 поколение");

        JsonNode rows = page("&sort=number").path("rows");
        assertThat(rows.get(0).path("generation").asText()).isEqualTo("4 поколение");
        assertThat(rows.get(0).path("yearTo").asInt()).isEqualTo(2017);

        JsonNode without = rows.get(1);
        assertThat(without.path("generation").isNull())
                .as("у рестайлинга кузовов нет — номер 150 им не подтверждён").isTrue();
        assertThat(without.path("yearFrom").isNull()).isTrue();
        assertThat(without.path("vehicleTitleBody").asText()).isEqualTo("150");

        JsonNode other = rows.get(2);
        assertThat(other.path("generation").isNull()).as("«Prado 120» при кузовах 150").isTrue();
        assertThat(other.path("vehicleTitleBody").asText()).isEqualTo("120");
        assertThat(other.path("vehicleBlockBodies").asText()).isEqualTo("150");
        assertThat(values("generation")).containsExactly("4 поколение");
    }

    @Test
    @DisplayName("Донор побеждает и поколение Дрома из применимости: колонка — поколение донора")
    void donorWinsOverDromGeneration() throws Exception {
        long donor = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO donor (brand_id, model_id, generation_id, status)
                VALUES (?, ?, ?, 'DISMANTLING') RETURNING id""",
                Long.class, toyota, prado, generation(prado, 2002)));
        long part = part("Стекло Toyota Land Cruiser Prado");
        inTenant(() -> jdbc.update("UPDATE part SET donor_id = ? WHERE id = ?", donor, part));
        drom(part, 2009, 2013, null, "4 поколение");
        drom(part, 2013, 2017, null, "4 поколение");

        JsonNode row = only(page(""));
        assertThat(row.path("generation").asText()).isEqualTo(inTenant(() -> jdbc.queryForObject(
                "SELECT name FROM catalog.generation WHERE id = ?", String.class,
                generation(prado, 2002))));
        assertThat(row.path("yearFrom").asInt()).isEqualTo(2002);
        assertThat(row.path("vehicleSource").asText()).isEqualTo("DONOR");
        assertThat(row.path("vehicleGenerationMerged").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Марка в наименовании есть, модели нет — показана одна марка, без чужой модели")
    void brandWithoutModelInTitle() throws Exception {
        long part = part("Блок управления зеркалами Toyota");
        row(part, prado, generation(prado, 2002), false);

        JsonNode row = only(page(""));
        assertThat(row.path("brand").asText()).isEqualTo("Toyota");
        assertThat(row.path("model").isNull())
                .as("Prado в наименовании не назван — своей моделью он не считается").isTrue();
        assertThat(row.path("generation").isNull()).isTrue();
        assertThat(row.path("yearFrom").isNull()).isTrue();
    }

    @Test
    @DisplayName("Подтверждённая человеком машина названа подтверждённой")
    void verifiedIsTold() throws Exception {
        long part = part("Магнитола Toyota Land Cruiser Prado");
        row(part, prado, null, true);

        assertThat(only(page("")).path("vehicleSource").asText()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("Донор побеждает: колонки — его машина, как до задачи")
    void donorWins() throws Exception {
        // Марка донора — другая, чем у своей машины из применимости (Toyota
        // в наименовании): иначе «донор побеждает» не отличить от «применимость
        // побеждает» — марка совпала бы при любом порядке.
        long honda = brand("Honda");
        long civic = model(honda, "Civic");
        long donor = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO donor (brand_id, model_id, generation_id, body_code, engine_code, year, status)
                VALUES (?, ?, ?, 'ZZE120', '3ZZ-FE', 2003, 'DISMANTLING') RETURNING id""",
                Long.class, honda, civic, firstGeneration(civic)));
        long part = part("Фара Toyota Land Cruiser Prado");
        inTenant(() -> jdbc.update("UPDATE part SET donor_id = ? WHERE id = ?", donor, part));
        row(part, prado, generation(prado, 2002), true);

        JsonNode row = only(page(""));
        // То, что колонки показывали до задачи: машина донора, поля донора.
        var expected = inTenant(() -> jdbc.queryForMap("""
                SELECT b.name AS brand, m.name AS model, g.name AS generation,
                       g.year_from, g.year_to
                  FROM donor d JOIN catalog.brand b ON b.id = d.brand_id
                  JOIN catalog.model m ON m.id = d.model_id
                  JOIN catalog.generation g ON g.id = d.generation_id
                 WHERE d.id = ?""", donor));
        assertThat(row.path("brand").asText()).isEqualTo("Honda").isEqualTo(expected.get("brand"));
        assertThat(row.path("model").asText()).isEqualTo("Civic");
        assertThat(row.path("generation").asText()).isEqualTo(expected.get("generation"));
        assertThat(row.path("yearFrom").asInt()).isEqualTo(expected.get("year_from"));
        assertThat(row.path("body").asText()).isEqualTo("ZZE120");
        assertThat(row.path("engine").asText()).isEqualTo("3ZZ-FE");
        assertThat(row.path("year").asInt()).isEqualTo(2003);
        assertThat(row.path("vehicleSource").asText()).isEqualTo("DONOR");
    }

    // --- 7: пусто, когда нечего показать ---

    @Test
    @DisplayName("Нет применимости или её машины нет в наименовании — клетки пусты")
    void nothingToShowIsEmpty() throws Exception {
        part("Теплообменник (б/у) CCLNS004");
        long other = part("Магнитола");
        // Машина есть, но в наименовании не названа: своей она не считается.
        row(other, prado, null, false);

        for (JsonNode row : page("").path("rows")) {
            assertThat(row.path("brand").isNull()).as(row.toString()).isTrue();
            assertThat(row.path("model").isNull()).isTrue();
            assertThat(row.path("generation").isNull()).isTrue();
            assertThat(row.path("vehicleSource").isNull()).isTrue();
        }
    }

    // --- 3: отбор, сортировка, список значений, файл — по показанному ---

    @Test
    @DisplayName("Отбор и сортировка по марке и модели находят позицию без донора")
    void filterAndSortUseTheShownValue() throws Exception {
        long withCar = part("Дверь передняя правая Toyota Land Cruiser Prado 120");
        row(withCar, prado, generation(prado, 2002), false, "Кузова: GRJ120W, KDJ121W");
        part("Теплообменник CCLNS004");

        assertThat(titles(page("&filter=brand:Toyota")))
                .containsExactly("Дверь передняя правая Toyota Land Cruiser Prado 120");
        assertThat(titles(page("&filter=model:Land Cruiser Prado")))
                .containsExactly("Дверь передняя правая Toyota Land Cruiser Prado 120");
        assertThat(titles(page("&find=model:prado")))
                .containsExactly("Дверь передняя правая Toyota Land Cruiser Prado 120");
        // Отбор по машине из блока, а не своей, не находит: колонка её не показывает.
        row(withCar, runner, null, false);
        assertThat(titles(page("&filter=model:4Runner"))).isEmpty();
        // Пустые сортируются последними, марка — первой.
        assertThat(titles(page("&sort=brand")).get(0))
                .isEqualTo("Дверь передняя правая Toyota Land Cruiser Prado 120");

        assertThat(values("brand")).containsExactly("Toyota");
        assertThat(values("model")).containsExactly("Land Cruiser Prado");

        String csv = export();
        assertThat(csv).contains("\"Дверь передняя правая Toyota Land Cruiser Prado 120\";\"\";"
                + "\"Toyota\";\"Land Cruiser Prado\";\"2002\"");
    }

    /**
     * Сортировка по марке — по показанному значению, а не по порядку заведения.
     *
     * <p>Позиция, заведённая раньше, — Toyota, позже — Honda; донора нет
     * ни у одной. По марке Honda обязана встать первой. Отсортируй экран
     * по марке донора (её нет у обеих) — порядок решит последний ключ,
     * номер позиции, и первой встанет Toyota.
     */
    @Test
    @DisplayName("Сортировка по марке и модели идёт по своей машине позиции без донора")
    void sortUsesTheShownValue() throws Exception {
        long first = part("Карданный вал Toyota Land Cruiser Prado 120");
        row(first, prado, generation(prado, 2002), false);
        long honda = brand("Honda");
        long civic = model(honda, "Civic");
        long second = part("Фара Honda Civic");
        inTenant(() -> jdbc.update("""
                INSERT INTO part_applicability (part_id, brand_id, model_id, is_verified)
                VALUES (?, ?, ?, false)""", second, honda, civic));

        assertThat(titles(page("&sort=brand")))
                .containsExactly("Фара Honda Civic", "Карданный вал Toyota Land Cruiser Prado 120");
        assertThat(titles(page("&sort=brand&desc=true")))
                .containsExactly("Карданный вал Toyota Land Cruiser Prado 120", "Фара Honda Civic");
        assertThat(titles(page("&sort=model")))
                .containsExactly("Фара Honda Civic", "Карданный вал Toyota Land Cruiser Prado 120");
        assertThat(titles(page("&sort=model&desc=true")))
                .containsExactly("Карданный вал Toyota Land Cruiser Prado 120", "Фара Honda Civic");
    }

    // --- 6: подбор по машине ---

    @Test
    @DisplayName("Подбор: машины применимости в списке, число у модели — сколько найдёт отбор")
    void vehiclesComeFromApplicability() throws Exception {
        long first = part("Карданный вал Toyota Land Cruiser Prado 120");
        row(first, prado, generation(prado, 2002), false);
        row(first, prado, generation(prado, 2009), false);
        row(first, runner, null, false);
        long second = part("Дверь Toyota Land Cruiser Prado 120");
        row(second, prado, generation(prado, 2002), false);
        // С донором на ту же машину и применимостью на неё же — одна позиция.
        long donor = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO donor (brand_id, model_id, body_code, status)
                VALUES (?, ?, 'GRJ120', 'DISMANTLING') RETURNING id""", Long.class, toyota, prado));
        long third = part("Фара Toyota Land Cruiser Prado");
        inTenant(() -> jdbc.update("UPDATE part SET donor_id = ? WHERE id = ?", donor, third));
        row(third, prado, null, true);

        JsonNode options = json.readTree(body(get("/api/parts/catalog/vehicles")));
        assertThat(options).isNotEmpty();
        long pradoParts = 0;
        long runnerParts = 0;
        long brandParts = -1;
        for (JsonNode option : options) {
            if (option.path("modelId").asLong() == prado) {
                pradoParts += option.path("parts").asLong();
            }
            if (option.path("modelId").asLong() == runner) {
                runnerParts += option.path("parts").asLong();
            }
            brandParts = option.path("brandParts").asLong();
        }
        assertThat(pradoParts)
                .as("число у модели = отбор по ней; позиция с донором и применимостью на Prado — один раз")
                .isEqualTo(total("&brandId=" + toyota + "&modelId=" + prado))
                .isEqualTo(3);
        assertThat(runnerParts).isEqualTo(total("&brandId=" + toyota + "&modelId=" + runner))
                .isEqualTo(1);
        assertThat(brandParts)
                .as("число у марки — не сумма моделей: позиция Prado и 4Runner одна")
                .isEqualTo(total("&brandId=" + toyota))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Подбор: у склада без донора вовсе список не пуст")
    void vehiclesWithoutDonors() throws Exception {
        long part = part("Глушитель Toyota Land Cruiser Prado 120");
        row(part, surf, generation(surf, 2002), false);

        JsonNode options = json.readTree(body(get("/api/parts/catalog/vehicles")));
        assertThat(options).hasSize(1);
        assertThat(options.get(0).path("model").asText()).isEqualTo("Hilux Surf");
        assertThat(options.get(0).path("parts").asLong())
                .isEqualTo(total("&brandId=" + toyota + "&modelId=" + surf)).isEqualTo(1);
    }

    // --- 9: это чтение ---

    @Test
    @DisplayName("Витрина, списки и подбор ничего не пишут: отметок для площадки нет")
    void readingMarksNothing() throws Exception {
        long part = part("Дверь Toyota Land Cruiser Prado 120");
        row(part, prado, generation(prado, 2002), false);
        String before = inTenant(() -> jdbc.queryForObject(
                "SELECT updated_at::text FROM part WHERE id = ?", String.class, part));

        page("&sort=brand&filter=brand:Toyota");
        values("brand");
        body(get("/api/parts/catalog/vehicles"));
        export();

        assertThat(inTenant(() -> jdbc.queryForObject("SELECT count(*) FROM part_change", Long.class)))
                .isZero();
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT updated_at::text FROM part WHERE id = ?", String.class, part)))
                .isEqualTo(before);
    }

    private JsonNode page(String query) throws Exception {
        return json.readTree(body(get("/api/parts/catalog?missing=true" + query)));
    }

    private long total(String query) throws Exception {
        return page(query).path("total").asLong();
    }

    private static JsonNode only(JsonNode page) {
        assertThat(page.path("rows")).hasSize(1);
        return page.path("rows").get(0);
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.path("rows").forEach(row -> titles.add(row.path("title").asText()));
        return titles;
    }

    private List<String> values(String column) throws Exception {
        List<String> values = new ArrayList<>();
        json.readTree(body(get("/api/parts/catalog/values?column=" + column)))
                .forEach(value -> values.add(value.asText()));
        return values;
    }

    private String export() throws Exception {
        return body(get("/api/parts/catalog/export?missing=true"));
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long brand(String name) {
        return jdbc.queryForObject("SELECT id FROM catalog.brand WHERE name = ?", Long.class, name);
    }

    private long model(long brand, String name) {
        return jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND name = ?", Long.class, brand, name);
    }

    private long firstGeneration(long model) {
        return jdbc.queryForObject(
                "SELECT min(id) FROM catalog.generation WHERE model_id = ?", Long.class, model);
    }

    private long model(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM catalog.model WHERE brand_id = ? AND name = ?", Long.class, toyota, name);
    }

    /** Поколение модели по году начала — тем же признаком, каким его ищет разбор блока. */
    private long generation(long model, int yearFrom) {
        return jdbc.queryForObject(
                "SELECT min(id) FROM catalog.generation WHERE model_id = ? AND year_from = ?",
                Long.class, model, yearFrom);
    }

    private long part(String title) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (title, price, status) VALUES (?, 1000, 'IN_STOCK') RETURNING id""",
                Long.class, title));
    }

    private void row(long part, long model, Long generation, boolean verified) {
        row(part, model, generation, verified, null);
    }

    /** Строка применимости с примечанием кузовов — как её пишет разбор блока (0289). */
    private void row(long part, long model, Long generation, boolean verified, String note) {
        inTenant(() -> jdbc.update("""
                INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id,
                                                is_verified, note)
                VALUES (?, ?, ?, ?, ?, ?)""", part, toyota, model, generation, verified, note));
    }

    /**
     * Строка поколения Prado, как её пишет разбор блока (0289, 0297): поколение
     * справочника по году начала, годы клиента, кузова и метка поколения Дрома.
     */
    private void drom(long part, int from, Integer to, String note, String label) {
        inTenant(() -> jdbc.update("""
                INSERT INTO part_applicability (part_id, brand_id, model_id, generation_id,
                                                year_from, year_to, note, generation_label,
                                                is_verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, false)""",
                part, toyota, prado, generation(prado, from), from, to, note, label));
    }

    private MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"owner","password":"пароль"}"""
                                .formatted(COMPANY)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private <T> T inTenant(Supplier<T> body) {
        TenantContext.set(TENANT);
        try {
            return transactionTemplate.execute(status -> body.get());
        } finally {
            TenantContext.clear();
        }
    }
}
