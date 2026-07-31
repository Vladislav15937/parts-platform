package ru.partsflow.migration.excel;

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

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Импорт через HTTP, а не вызовом импортёра.
 *
 * <p>Разница не формальная. {@code ExcelImportTest} зовёт импортёр напрямую
 * и потому не заметил, что итог не сериализуется: {@code Report} был классом
 * с методами в стиле record, а без {@code getX()} Jackson не находит у него
 * ни одного свойства. Ответ уходил пятисоткой «No acceptable representation»,
 * и увидеть это можно было только нажав кнопку в браузере.
 */
@SpringBootTest(properties = "app.provisioning-token=секрет-импорта")
@AutoConfigureMockMvc
class ExcelImportRestTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantProvisioning provisioning;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Разбор и загрузка возвращают читаемый JSON")
    void previewAndImportReturnJson() throws Exception {
        String code = "imp" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.Result tenant = provisioning.provision(new TenantProvisioning.Request(
                code, "Разборка", "vladelec", "пароль-8симв", null));
        MockHttpSession session = login(code);

        long warehouseId = jdbc.queryForObject(
                "SELECT id FROM %s.warehouse ORDER BY id LIMIT 1".formatted(tenant.schemaName()),
                Long.class);

        MockMultipartFile file = new MockMultipartFile("file", "sklad.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook());

        mvc.perform(multipart("/api/import/excel/preview").file(file).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header[1]").value("Название"))
                .andExpect(jsonPath("$.detected.NAME").value(1))
                .andExpect(jsonPath("$.rows.length()").value(2));

        String key = UUID.randomUUID().toString();
        mvc.perform(multipart("/api/import/excel").file(file).with(csrf()).session(session)
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", key)
                        .param("NAME", "1").param("QUANTITY", "2").param("PRICE", "3"))
                .andExpect(status().isOk())
                // Ровно то, чего не было: итог обязан приехать полями, а не
                // пятисоткой о несериализуемом ответе.
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skipped").isArray());

        // Повтор того же запроса — ровно то, что делает владелец, увидев
        // ошибку. Итог тот же, склад не удваивается.
        mvc.perform(multipart("/api/import/excel").file(file).with(csrf()).session(session)
                        .param("warehouseId", String.valueOf(warehouseId))
                        .param("requestId", key)
                        .param("NAME", "1").param("QUANTITY", "2").param("PRICE", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2));

        Integer parts = jdbc.queryForObject(
                "SELECT count(*) FROM %s.part".formatted(tenant.schemaName()), Integer.class);
        org.assertj.core.api.Assertions.assertThat(parts)
                .as("повтор завёл вторую копию склада")
                .isEqualTo(2);
    }

    /** Настоящий xlsx: это zip с XML, сторонние библиотеки в тесте не нужны. */
    private static byte[] workbook() throws Exception {
        List<List<String>> rows = List.of(
                List.of("№", "Название", "Кол-во", "Цена, руб"),
                List.of("1", "Фара левая", "2", "9 500"),
                List.of("2", "Бампер", "1", "14 000"));

        StringBuilder sheet = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>\
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""");
        for (int r = 0; r < rows.size(); r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            List<String> values = rows.get(r);
            for (int c = 0; c < values.size(); c++) {
                sheet.append("<c r=\"").append((char) ('A' + c)).append(r + 1)
                        .append("\" t=\"inlineStr\"><is><t>")
                        .append(values.get(c)).append("</t></is></c>");
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>\
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                    <Default Extension="xml" ContentType="application/xml"/>\
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>\
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>\
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>\
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">\
                    <sheets><sheet name="Склад" sheetId="1" r:id="rId1"/></sheets></workbook>""");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>\
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>\
                    </Relationships>""");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

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
