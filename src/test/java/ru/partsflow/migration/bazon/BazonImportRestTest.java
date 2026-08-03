package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import ru.partsflow.platform.tenant.TenantProvisioning;
import ru.partsflow.support.PostgresTestBase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Перенос склада из чужой системы через HTTP.
 *
 * <p>Отдельно от {@link BazonImporterTest} по той же причине, по которой
 * отделён импорт из Excel: тот тест зовёт импортёр напрямую и не заметил бы,
 * что итог не сериализуется. У Excel так и вышло — ответ уходил пятисоткой
 * «No acceptable representation», и увидеть это можно было только нажав
 * кнопку в браузере.
 *
 * <p>Здесь же проверяется то, чего у импортёра нет и быть не может: арендатор
 * берётся из сессии, а не из запроса.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class BazonImportRestTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantProvisioning provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Выгрузка загружается, итог приезжает читаемым JSON")
    void importReturnsJson() throws Exception {
        String code = "baz" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        MockHttpSession session = login(code);

        mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", DONORS))
                        .file(csv("catalog", CATALOG))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                // Итог обязан приехать полями, а не пятисоткой
                // о несериализуемом ответе.
                .andExpect(jsonPath("$.loaded['товаров']").value(3))
                .andExpect(jsonPath("$.loaded['доноров']").value(1))
                .andExpect(jsonPath("$.problemCount").value(0));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM %s.part".formatted(tenant.schemaName()), Integer.class))
                .isEqualTo(3);

        // Повтор — ровно то, что делает владелец, увидев ошибку или просто
        // не поняв, прошло ли. Склад не должен удвоиться.
        mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", DONORS))
                        .file(csv("catalog", CATALOG))
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded['товаров пропущено (уже есть)']").value(3));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM %s.part".formatted(tenant.schemaName()), Integer.class))
                .as("повтор завёл вторую копию склада")
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM %s.donor".formatted(tenant.schemaName()), Integer.class))
                .as("повтор завёл вторую копию машин — деталей у них не будет, "
                        + "и в отчёте об окупаемости они выглядят чистым убытком")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("После переноса наименования сопоставлены, а счётчик заполнен")
    void namesAreMatchedAfterImport() throws Exception {
        String code = "baz" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));

        mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", DONORS))
                        .file(csv("catalog", CATALOG))
                        .with(csrf()).session(login(code)))
                .andExpect(status().isOk());

        // «Фара» дословно есть в поставляемом справочнике эталонов. Пока
        // импорт не звал общий сопоставитель, весь склад переехавшего клиента
        // ложился в «Не разобрано» — включая написания, совпадающие точно.
        assertThat(jdbc.queryForObject("""
                SELECT match_status FROM %s.part_name WHERE name = 'Стартер'"""
                .formatted(tenant.schemaName()), String.class))
                .as("написание, совпадающее с эталоном дословно, осталось "
                        + "нераспознанным — весь склад ляжет в «Не разобрано»")
                .isEqualTo("AUTO");

        assertThat(jdbc.queryForObject("""
                SELECT match_status FROM %s.part_name WHERE name = 'Фара левая'"""
                .formatted(tenant.schemaName()), String.class))
                .as("«Фара левая» сопоставляться и не должна: сторона живёт "
                        + "отдельным полем, это разбирают руками")
                .isEqualTo("UNMATCHED");

        // А счётчик обязан быть заполнен у всех: без него экран разбора
        // показывает «позиций пока нет» под написанием с сотней карточек.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM %s.part_name WHERE usage_count = 0"""
                .formatted(tenant.schemaName()), Integer.class))
                .as("счётчик использований не посчитан — экран разбора "
                        + "остался без единственного ориентира")
                .isZero();

        // Сленг из чужой системы становится эталоном: ради этого справочник
        // и нужен, иначе прайс остаётся словарём чужих написаний.
        assertThat(jdbc.queryForObject("""
                SELECT title FROM %s.part WHERE legacy_code = 'A-300'"""
                .formatted(tenant.schemaName()), String.class))
                .as("заголовок остался в написании чужой системы")
                .startsWith("Стартер");

        // И карточки обязаны догнать наименование: сопоставить написание
        // и оставить весь склад в «Не разобрано» значит починить будущее
        // и не починить прошлое.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM %s.part p
                  JOIN %s.part_name pn ON pn.id = p.part_name_id
                 WHERE pn.part_kind_id IS NOT NULL AND p.part_kind_id IS NULL"""
                .formatted(tenant.schemaName(), tenant.schemaName()), Integer.class))
                .as("наименование распознано, а карточка осталась без вида детали")
                .isZero();

        // И поколение обязано подобраться по году: импортёр заводит машины
        // своим SQL, мимо registerDonor, и до этого шага у переехавшего
        // клиента поколения не было ни у одной машины — а от него зависит
        // кузов в заголовке и подбор детали по машине.
        assertThat(jdbc.queryForObject("""
                SELECT g.name FROM %s.donor d
                  JOIN catalog.generation g ON g.id = d.generation_id
                 WHERE d.legacy_code = 'Д-1'"""
                .formatted(tenant.schemaName()), String.class))
                .as("поколение по году не подобрано — заголовок и применимость "
                        + "останутся без кузова")
                .isNotBlank();
    }

    @Test
    @DisplayName("Чужой формат отвергается, а не грузится частично")
    void alienFormatIsRejected() throws Exception {
        String code = "baz" + UUID.randomUUID().toString().substring(0, 8);
        provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));

        // Первые байты файла .xlsx: это zip, и прочитанный как текст
        // он давал строки без заголовка. Часть из них доезжала до склада
        // призрачными карточками «Без наименования» — пятнадцать штук
        // на двухстах настоящих. Поймано прогоном инструкции по подключению.
        byte[] xlsx = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00, 0x08, 0x00};

        mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", DONORS))
                        .file(new MockMultipartFile("catalog", "catalog.xlsx",
                                "application/vnd.ms-excel", xlsx))
                        .with(csrf()).session(login(code)))
                .andExpect(status().isBadRequest());

        // И машин это касается ровно так же.
        mvc.perform(multipart("/api/import/bazon")
                        .file(new MockMultipartFile("donors", "donors.xlsx",
                                "application/vnd.ms-excel", xlsx))
                        .file(csv("catalog", CATALOG))
                        .with(csrf()).session(login(code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Без файлов — 400, а не 500")
    void missingFilesAreRejected() throws Exception {
        String code = "baz" + UUID.randomUUID().toString().substring(0, 8);
        provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));

        mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", DONORS))
                        .file(new MockMultipartFile("catalog", "catalog.csv", "text/csv",
                                new byte[0]))
                        .with(csrf()).session(login(code)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Не владелец склад не заливает")
    void onlyOwnerImports() throws Exception {
        String code = "baz" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));

        jdbc.update("""
                INSERT INTO %s.tenant_member (display_name, role, login, password_hash)
                VALUES ('Продавец', 'SELLER', 'prodavets', ?)"""
                .formatted(tenant.schemaName()),
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("пароль-8симв"));

        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"prodavets","password":"пароль-8симв"}"""
                                .formatted(code)))
                .andExpect(status().isOk())
                .andReturn();

        // Операция заливает склад целиком, и отменить её можно только
        // восстановлением из бэкапа.
        mvc.perform(multipart("/api/import/bazon")
                        .file(csv("donors", DONORS))
                        .file(csv("catalog", CATALOG))
                        .with(csrf())
                        .session((MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isForbidden());
    }

    /** Выгрузка приходит в windows-1251 — это данность, а не выбор. */
    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile(name, name + ".csv", "text/csv",
                content.getBytes(BazonCsvReader.CHARSET));
    }

    private static final String DONORS = """
            "Номер донора";"Марка";"Модель";"Год выпуска";"VIN";"Поставка";"Статус";"Цвет";\
            "Пробег";"Руль";"Привод";"Тип КПП";"Модель КПП";"Комплектация"
            "Д-1";"Toyota";"Camry";"2006";"JTDBR32E060012345";"Контейнер 12";"Разбор";\
            "серебристый";"180000";"Левый";"Передний";"Автомат";"U151E";""
            """;

    private static final String CATALOG = """
            "Номер товара";"Запчасть";"Номер донора";"Марка";"Модель";"Год выпуска";"Кузов";\
            "Двигатель";"Комментарий";"Заметка";"Левый / Правый";"Передний / Задний";\
            "Оценка состояния";"Маркировка";"Производитель";"Цена";"Номер производителя";\
            "Основной (свободно)";"Основной (резерв)";"Основной (ожидается)";"Выгружать"
            "A-100";"Фара левая";"Д-1";"Toyota";"Camry";"2006";"";"";"";"";"Левый";"Передний";\
            "Хорошее";"";"";"9500";"81150-33670";"2";"1";"0";"да"
            "A-200";"Бампер передний";"";"";"";"";"";"";"";"";"";"";"";"";"";"12000";"";\
            "1";"0";"0";"да"
            "A-300";"Стартер";"";"";"";"";"";"";"";"";"";"";"";"";"";"6800";"";"1";"0";"0";"да"
            """;

    private MockHttpSession login(String code) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"%s","login":"vladelec","password":"пароль-8симв"}"""
                                .formatted(code)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
