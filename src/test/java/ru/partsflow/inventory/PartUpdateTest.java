package ru.partsflow.inventory;

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
import org.springframework.transaction.support.TransactionTemplate;
import ru.partsflow.platform.tenant.TenantContext;
import ru.partsflow.support.PostgresTestBase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правка карточки товара.
 *
 * <p>{@code PartService.changePrice} существовал с самого начала и даже
 * публиковал событие для площадок, но снаружи его не звал никто: ни эндпоинта,
 * ни экрана. То есть цену принятой детали владелец изменить не мог вовсе —
 * а на разборке это ежедневная работа. Ровно тот случай, который в проекте
 * уже записан правилом: эндпоинт без экрана — отсутствующая возможность,
 * а сервис без эндпоинта тем более.
 *
 * <p>Через HTTP: роль проверяется аннотацией на методе, и вызов сервиса
 * напрямую её не касается.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class PartUpdateTest extends PostgresTestBase {

    private static final String TENANT = "t_000146";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PartService parts;

    private Long partId;
    private Long ownerId;

    @BeforeAll
    static void migrate() {
        provisionTenants(TENANT);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM public.tenant_registry WHERE tenant_id = 146");
        jdbc.update("""
                INSERT INTO public.tenant_registry (tenant_id, schema_name, company_name, code)
                VALUES (146, ?, 'Разборка', 'editco')""", TENANT);

        inTenant(() -> {
            ownerId = member("vladelec", "Владелец", "OWNER");
            member("prodavec", "Продавец", "SELLER");

            partId = jdbc.queryForObject("""
                    INSERT INTO part (category_id, title, price, note, is_published)
                    VALUES (1, 'Фара Toyota Camry 2006 перед. лев. (б/у)', 4500,
                            'скол на креплении', true)
                    RETURNING id""", Long.class);
            return null;
        });
    }

    @Test
    @DisplayName("Правка карточки меняет цену и отмечает, кто это сделал")
    void priceChangeIsAttributed() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":3900,"note":"скол на креплении","published":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(3900));

        assertThat(price()).isEqualByComparingTo("3900");
        // Цена без автора и времени — это ровно та ситуация, в которой потом
        // нельзя разобрать, почему деталь ушла за бесценок.
        assertThat(priceChangedBy()).isEqualTo(ownerId);
        assertThat(priceChangedAt()).isNotNull();
        assertThat(updatedBy()).isEqualTo(ownerId);
    }

    /**
     * Занятый штрихкод объясняется словами, а не отказом базы.
     *
     * <p>Уникальность стережёт {@code part_barcode_uk}, и это верно: один код
     * у двух позиций означает, что сканер на складе приводит не к той детали.
     * Но владелец вводит штрихкод с этикетки, и «Операция нарушает целостность
     * данных» не говорит ни что случилось, ни у какой позиции этот код уже
     * стоит — а именно её и надо найти, чтобы понять, кто из двух прав.
     */
    @Test
    @DisplayName("Занятый штрихкод называет позицию, а не отвечает про целостность")
    void takenBarcodeIsExplained() throws Exception {
        MockHttpSession session = login("vladelec");
        String code = inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, partId));
        Long other = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, 'Бампер', 1000)
                RETURNING id""", Long.class));

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":4500,\"barcode\":\"ШК-1\",\"published\":true}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/parts/" + other).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":1000,\"barcode\":\"ШК-1\",\"published\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(code)));
    }

    /**
     * «Выгружать» правят три пути, и роль у них одна.
     *
     * <p>Отметка есть в карточке, в правке списком и отдельным запросом
     * списком номеров. Первые два были владельцу и менеджеру, третий —
     * ещё и продавцу: то есть снять с площадки хоть весь склад он мог,
     * а тронуть одну позицию через форму — нет. Снятое объявление уносит
     * накопленные просмотры, а заметно это через дни по пустому прайсу.
     *
     * <p>Проверяются оба пути сразу: разойдясь снова, они дадут ровно ту же
     * дыру, и увидеть её можно только сравнением.
     */
    @Test
    @DisplayName("Продавец не снимает позиции с выгрузки ни одним из путей")
    void publicationIsOwnerWork() throws Exception {
        MockHttpSession seller = login("prodavec");

        mvc.perform(post("/api/parts/publication").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partIds\":[%d],\"published\":false}".formatted(partId)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/parts/catalog/bulk").with(csrf()).session(seller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":{\"published\":false}}"))
                .andExpect(status().isForbidden());

        assertThat(publishedOf(partId)).as("позиция снята с выгрузки продавцом").isTrue();

        // Владельцу — можно, иначе проверка запрещает саму работу.
        mvc.perform(post("/api/parts/publication").with(csrf()).session(login("vladelec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partIds\":[%d],\"published\":false}".formatted(partId)))
                .andExpect(status().isOk());
        assertThat(publishedOf(partId)).isFalse();
    }

    /**
     * Снятый штрихкод — это NULL, а не пустая строка.
     *
     * <p>Пустых строк в уникальном индексе может быть только одна: сняв
     * штрихкод с одной позиции, владелец на второй получал «Операция нарушает
     * целостность данных» — при том что ничего не задвоил и вообще стирал.
     * NULL же друг с другом не сталкиваются. Та же природа, что у нулевой
     * цены установки: пусто означает «не заполнено», а не значение.
     */
    @Test
    @DisplayName("Штрихкод можно снять с двух позиций подряд")
    void clearedBarcodesDoNotCollide() throws Exception {
        MockHttpSession session = login("vladelec");
        Long other = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price) VALUES (1, 'Крыло', 2000)
                RETURNING id""", Long.class));

        for (Long id : java.util.List.of(partId, other)) {
            mvc.perform(put("/api/parts/" + id).with(csrf()).session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"price\":2000,\"barcode\":\"\",\"published\":true}"))
                    .andExpect(status().isOk());
        }

        // Счёт по своим двум позициям: соседние тесты штрихкоды оставляют,
        // а фикстура позиции между тестами не чистит.
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT count(*) FROM part WHERE barcode IS NOT NULL AND id IN (?, ?)",
                Integer.class, partId, other)))
                .as("снятый штрихкод остался пустой строкой").isZero();
    }

    /**
     * Отметка о смене цены обязана означать «цену меняли»: иначе по ней нельзя
     * искать подешевевшее, а площадка получает дельту на правку заметки.
     */
    @Test
    @DisplayName("Правка соседнего поля не выдаёт себя за смену цены")
    void editingOtherFieldsIsNotAPriceChange() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":4500,"note":"скол и потёртость","published":true}"""))
                .andExpect(status().isOk());

        assertThat(note()).isEqualTo("скол и потёртость");
        assertThat(priceChangedAt()).isNull();
        // А автор правки записан: карточку трогали.
        assertThat(updatedBy()).isEqualTo(ownerId);
    }

    /**
     * Пустое поле формы — это «очищено», а не «не трогать». Иначе стереть
     * заметку с экрана невозможно вовсе.
     */
    @Test
    @DisplayName("Незаполненное поле формы стирает прежнее значение")
    void emptyFieldClearsTheValue() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":4500,"published":true}"""))
                .andExpect(status().isOk());

        assertThat(note()).isNull();
    }

    // Здесь цена, минимальная цена и себестоимость: продавец, торгующийся
    // с покупателем, не должен уметь подвинуть себе нижнюю границу.
    @Test
    @DisplayName("Продавец карточку править не может")
    void sellerCannotEdit() throws Exception {
        MockHttpSession session = login("prodavec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":100,"published":true}"""))
                .andExpect(status().isForbidden());

        assertThat(price()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("Отрицательная цена не принимается")
    void negativePriceIsRejected() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":-1,"published":true}"""))
                .andExpect(status().isBadRequest());

        assertThat(price()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("Правка несуществующей карточки — 400, а не пятисотка")
    void unknownPartIsRejected() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/999999").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":100,"published":true}"""))
                .andExpect(status().isBadRequest());
    }

    /**
     * Габариты и упаковка — те самые поля, которые доехали до карточки только
     * сейчас. Пока их некому было писать, они означали пустую строку у каждой
     * позиции каждого клиента навсегда.
     */
    @Test
    @DisplayName("Габариты, упаковка и текстовый блок сохраняются")
    void dimensionsAndTextAreSaved() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + partId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":4500,"weightKg":3.5,"lengthMm":120,"widthMm":80,
                                 "heightMm":45,"packageLengthMm":130,"packageWidthMm":90,
                                 "packageHeightMm":50,"packageWeightKg":4,
                                 "textBlock":"Проверена на стенде","published":false}"""))
                .andExpect(status().isOk());

        var card = inTenant(() -> jdbc.queryForMap("""
                SELECT weight_kg, length_mm, package_height_mm, text_block, is_published
                  FROM part WHERE id = ?""", partId));

        assertThat((BigDecimal) card.get("weight_kg")).isEqualByComparingTo("3.5");
        assertThat(card.get("length_mm")).isEqualTo(120);
        assertThat(card.get("package_height_mm")).isEqualTo(50);
        assertThat(card.get("text_block")).isEqualTo("Проверена на стенде");
        assertThat(card.get("is_published")).isEqualTo(false);
    }

    /**
     * Правка списком меняет только то, что владелец тронул.
     *
     * <p>Это главное отличие от правки одной карточки: там форма уезжает
     * целиком и пустое поле означает «очищено», а здесь у выбранных позиций
     * заметки разные, и «пустое значит очистить» стёрло бы их все одним
     * нажатием.
     */
    @Test
    @DisplayName("Правка списком трогает только переданные поля")
    void bulkChangesOnlyWhatWasTouched() throws Exception {
        Long second = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, note)
                VALUES (1, 'Бампер', 8000, 'своя заметка') RETURNING id""", Long.class));
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d],"changes":{"section":"А-1"}}"""
                                .formatted(partId, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2));

        assertThat(sectionOf(partId)).isEqualTo("А-1");
        assertThat(sectionOf(second)).isEqualTo("А-1");
        // Заметки у позиций разные, и правка секции их не касается.
        assertThat(note()).isEqualTo("скол на креплении");
        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT note FROM part WHERE id = ?", String.class, second)))
                .isEqualTo("своя заметка");
        assertThat(updatedBy()).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("Цена списком меняется и отмечается автором")
    void bulkPriceIsAttributed() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],"changes":{"price":3900,"published":false}}"""
                                .formatted(partId)))
                .andExpect(status().isOk());

        assertThat(price()).isEqualByComparingTo("3900");
        assertThat(priceChangedBy()).isEqualTo(ownerId);
        assertThat(publishedOf(partId)).isFalse();
        assertThat(priceEvents(partId))
                .as("площадке нужна дельта, а не отметка о том, что открыли форму")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Та же цена списком не выдаёт себя за смену цены")
    void bulkSamePriceIsNotAChange() throws Exception {
        MockHttpSession session = login("vladelec");

        // Правка секции у сотни позиций не должна засыпать площадку сотней
        // дельт: цена у них не менялась.
        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],"changes":{"price":4500,"section":"Б-2"}}"""
                                .formatted(partId)))
                .andExpect(status().isOk());

        assertThat(sectionOf(partId)).isEqualTo("Б-2");
        assertThat(priceChangedAt()).isNull();
        assertThat(priceEvents(partId)).isZero();
    }

    @Test
    @DisplayName("Список без позиций отвергается и сервисом, а не только формой")
    void serviceRefusesEmptySelection() {
        // Сервис зовут не только из контроллера, и проверка «есть что править»
        // должна стоять там, где операция.
        assertThatThrownBy(() -> inTenant(() ->
                parts.updateAll(List.of(), Map.of("price", 1), Map.of(), ownerId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inTenant(() ->
                parts.updateAll(List.of(partId), Map.of(), Map.of(), ownerId)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private int priceEvents(Long part) {
        return inTenant(() -> jdbc.queryForObject("""
                SELECT count(*) FROM outbox
                 WHERE aggregate_id = ? AND event_type = 'part.price_changed.v1'""",
                Integer.class, part));
    }

    @Test
    @DisplayName("Поле, которого нет в списке разрешённых, не правится")
    void bulkRefusesUnknownField() throws Exception {
        MockHttpSession session = login("vladelec");

        // Заголовок собирается справочником, остаток ведёт журнал, ячейку
        // правят перемещением: разрешить их списком значило бы дать испортить
        // сотню позиций одним нажатием.
        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],"changes":{"title":"Чужой заголовок"}}"""
                                .formatted(partId)))
                .andExpect(status().isBadRequest());

        assertThat(inTenant(() -> jdbc.queryForObject(
                "SELECT title FROM part WHERE id = ?", String.class, partId)))
                .startsWith("Фара");
    }

    @Test
    @DisplayName("Продавец списком не правит")
    void sellerCannotEditInBulk() throws Exception {
        MockHttpSession session = login("prodavec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],"changes":{"price":1}}""".formatted(partId)))
                .andExpect(status().isForbidden());

        assertThat(price()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("Пустой список позиций отвергается")
    void bulkNeedsPositions() throws Exception {
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[],"changes":{"price":1}}"""))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------- цена процентом и суммой

    /**
     * Цена двигается операцией, а не только новым числом.
     *
     * <p>Торг на разборке идёт словами «отдам за минус десять» и «скидка
     * пятьсот, забирай сегодня». Пока поле принимало только готовое число,
     * владелец считал 27 000 − 10 % в уме или в калькуляторе телефона —
     * а ошибка в разряде здесь стоит детали, отданной за 2 700.
     *
     * <p>Считает сервер: тот же расчёт нужен правке списком, и две копии
     * разошлись бы на первом округлении.
     */
    @Test
    @DisplayName("«Уменьшить на %»: 27 000 минус десять процентов — это 24 300")
    void priceDropsByPercent() throws Exception {
        Long part = partWithPrice("27000");
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":10,"priceOp":"DECREASE_PERCENT","published":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(24300.00));

        assertThat(priceOf(part)).isEqualByComparingTo("24300");
        // Площадке нужна дельта: цена в объявлении обязана уехать
        // ровно та же, что владелец увидел в карточке.
        assertThat(priceEvents(part)).isEqualTo(1);
    }

    @Test
    @DisplayName("«Увеличить на сумму» и «Округлить до» считаются от прежней цены")
    void priceGrowsByAmountAndRounds() throws Exception {
        Long part = partWithPrice("24300");
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":500,"priceOp":"INCREASE_AMOUNT","published":true}"""))
                .andExpect(status().isOk());
        assertThat(priceOf(part)).isEqualByComparingTo("24800");

        // Арифметическое, а не всегда вверх: это цена, а не наценка.
        // 24 800 до тысяч — 25 000, а 24 300 было бы 24 000.
        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":1000,"priceOp":"ROUND_TO","published":true}"""))
                .andExpect(status().isOk());
        assertThat(priceOf(part)).isEqualByComparingTo("25000");
    }

    @Test
    @DisplayName("Без операции поле по-прежнему принимает готовое число")
    void plainNumberStillReplacesPrice() throws Exception {
        Long part = partWithPrice("27000");
        MockHttpSession session = login("vladelec");

        // Самый частый случай остаётся одним движением: умолчание —
        // «Изменить», и старое поведение обязано не сломаться.
        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":30000,"published":true}"""))
                .andExpect(status().isOk());

        assertThat(priceOf(part)).isEqualByComparingTo("30000");
    }

    /**
     * Отрицательной цены не бывает — и это отказ словами, а не ноль.
     *
     * <p>Ноль означал бы деталь, выставленную даром: нулевая цена в прайс
     * не уезжает вовсе, объявление пропадает, и узнают об этом через
     * несколько дней по опустевшей выдаче площадки.
     */
    @Test
    @DisplayName("«Уменьшить на сумму» больше цены — отказ словами, цена цела")
    void priceCannotGoNegative() throws Exception {
        Long part = partWithPrice("24300");
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":100000,"priceOp":"DECREASE_AMOUNT","published":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Отрицательной цены не бывает")));

        assertThat(priceOf(part)).isEqualByComparingTo("24300");
    }

    @Test
    @DisplayName("«Уменьшить на 100 %» не молчит и не оставляет ноль")
    void hundredPercentIsRefused() throws Exception {
        Long part = partWithPrice("24300");
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":100,"priceOp":"DECREASE_PERCENT","published":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("ноль")));

        assertThat(priceOf(part)).isEqualByComparingTo("24300");
    }

    @Test
    @DisplayName("Пустое значение операции цену не трогает, а пустой шаг округления — отказ")
    void emptyOperandLeavesPriceAlone() throws Exception {
        Long part = partWithPrice("24300");
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priceOp":"DECREASE_PERCENT","published":true}"""))
                .andExpect(status().isOk());
        assertThat(priceOf(part)).isEqualByComparingTo("24300");

        // Округление без шага — единственная операция, которую нельзя
        // выполнить «никак»: промолчать значит сказать «сохранено» там,
        // где ничего не произошло.
        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priceOp":"ROUND_TO","published":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("шаг округления")));
        assertThat(priceOf(part)).isEqualByComparingTo("24300");
    }

    /**
     * Правка списком считает от прежней цены каждой позиции.
     *
     * <p>Это и есть общая переоценка, ради которой задача заведена: «подними
     * всё, что лежит с зимы, на пять процентов». Сведённая к одному числу,
     * она поставила бы всем позициям одну цену — то есть испортила бы склад
     * ровно тем действием, которым его правят.
     */
    @Test
    @DisplayName("Списком процент считается от своей цены у каждой позиции")
    void bulkPercentCountsFromEachOwnPrice() throws Exception {
        Long first = partWithPrice("27000");
        Long second = partWithPrice("1000");
        Long third = partWithPrice("555");
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d,%d],"changes":{"price":10},
                                 "operations":{"price":"DECREASE_PERCENT"}}"""
                                .formatted(first, second, third)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(3))
                .andExpect(jsonPath("$.skipped").value(0));

        assertThat(priceOf(first)).isEqualByComparingTo("24300");
        assertThat(priceOf(second)).isEqualByComparingTo("900");
        // Округление до копеек явным правилом: 499,5 — это 499,50,
        // а не 499,49999 из двоичной плавающей.
        assertThat(priceOf(third)).isEqualByComparingTo("499.50");
    }

    /**
     * Позиция с незаполненным полем не считается от нуля, и об этом говорят.
     *
     * <p>Придуманный ноль превратил бы «поднять на 5 %» в «поставить ноль»,
     * то есть в снятое объявление. А «изменено 2» без слова о пропущенной
     * читается как «сделано всем»: заметить иначе можно только сверкой
     * склада руками.
     */
    @Test
    @DisplayName("Позиция без цены пропускается, и это сказано числом")
    void bulkSkipsPositionsWithoutBase() throws Exception {
        Long withPrice = partWithPrice("1000");
        Long without = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price)
                VALUES (1, 'Крыло без цены', NULL) RETURNING id""", Long.class));
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d],"changes":{"price":5},
                                 "operations":{"price":"INCREASE_PERCENT"}}"""
                                .formatted(withPrice, without)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(1));

        assertThat(priceOf(withPrice)).isEqualByComparingTo("1050");
        assertThat(priceOf(without)).isNull();
    }

    @Test
    @DisplayName("Арифметика бывает только у денег")
    void operationsAreForMoneyOnly() throws Exception {
        MockHttpSession session = login("vladelec");

        // «Увеличить заметку на 10 %» не значит ничего, а молча выполненная
        // замена вместо неё — это стёртая заметка у сотни позиций.
        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],"changes":{"note":"10"},
                                 "operations":{"note":"INCREASE_PERCENT"}}"""
                                .formatted(partId)))
                .andExpect(status().isBadRequest());

        assertThat(note()).isEqualTo("скол на креплении");
    }

    /**
     * История говорит не только «стало», но и насколько подвинулось.
     *
     * <p>«27 000 → 24 300» надо делить в уме, а разбираются с ценой по два
     * десятка строк за раз. Это посчитанная разница, а не записанная
     * операция: чем именно двигали цену, {@code audit_log} не хранит —
     * он кладёт снимки строки, и своего поля под это у него нет.
     */
    @Test
    @DisplayName("В истории у цены стоит, на сколько процентов она подвинулась")
    void historyShowsHowFarThePriceMoved() throws Exception {
        Long part = partWithPrice("27000");
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":10,"priceOp":"DECREASE_PERCENT","published":true}"""))
                .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/parts/" + part + "/history").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].fields[0].label").value("Цена"))
                .andExpect(jsonPath("$.changes[0].fields[0].before").value("27000"))
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("24300"))
                .andExpect(jsonPath("$.changes[0].fields[0].delta").value("−10 %"));
    }

    /**
     * Одна дешёвая позиция не отменяет переоценку всего склада.
     *
     * <p><b>Головной сценарий задачи.</b> «Подними всё, что лежит с зимы,
     * на пять процентов» — по большому отбору, у переехавшего клиента это
     * 35 841 позиция. Отбить пачку целиком из-за одной детали за сто рублей,
     * попавшей под «скинуть пятьсот», значит не сделать ничего и назвать
     * человеку одну позицию из скольких-то: сколько там ещё таких, он
     * не узнает, а править их по одной ему нечем.
     *
     * <p>Так уже решено для перевозки пачкой ({@code moveBatch}): проблемная
     * пропускается и называется вызывающему, остальные едут.
     */
    @Test
    @DisplayName("Позиция, уходящая в минус, пропускается — остальные меняются")
    void bulkKeepsGoodPositionsWhenOneWouldGoNegative() throws Exception {
        Long rich = partWithPrice("1000");
        Long cheap = partWithPrice("100");
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d],"changes":{"price":500},
                                 "operations":{"price":"DECREASE_AMOUNT"}}"""
                                .formatted(rich, cheap)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                // Числа мало: «одна не прошла» из тридцати тысяч — это
                // «пойди найди её». Публичный код виден на витрине.
                .andExpect(jsonPath("$.rejectedCodes[0]").value(codeOf(cheap)))
                .andExpect(jsonPath("$.rejectedReason").value(
                        org.hamcrest.Matchers.containsString("Отрицательной цены не бывает")));

        // Та, у которой всё было бы корректно, изменена — а не откачена
        // вместе с соседкой.
        assertThat(priceOf(rich)).isEqualByComparingTo("500");
        assertThat(priceOf(cheap)).isEqualByComparingTo("100");
    }

    /**
     * Не прошла ни одна — это отказ словами, а не «изменено 0».
     *
     * <p>Частичная работа кончается там, где работы не было вовсе: «изменено
     * 0» не говорит ни что случилось, ни что делать. Так же поступает
     * {@code moveBatch}, когда отложены все строки.
     */
    @Test
    @DisplayName("Когда не прошла ни одна позиция — отказ с причиной")
    void bulkRefusesWhenNoPositionPassed() throws Exception {
        Long first = partWithPrice("100");
        Long second = partWithPrice("200");
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d],"changes":{"price":500},
                                 "operations":{"price":"DECREASE_AMOUNT"}}"""
                                .formatted(first, second)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Ни одна из 2 позиций")));

        assertThat(priceOf(first)).isEqualByComparingTo("100");
        assertThat(priceOf(second)).isEqualByComparingTo("200");
    }

    /**
     * Негодный запрос роняет пачку целиком — и это не то же самое, что
     * негодная позиция.
     *
     * <p>Отказы здесь двух пород, и путать их дорого. «Этой позиции
     * операция не подходит» — цена ушла бы в минус, поле не заполнено —
     * пропускает одну и везёт остальные: у соседей всё в порядке, и держать
     * их из-за чужой беды незачем. «Запрос неверен» — отрицательный процент,
     * округление без шага — не подходит **ни одной** позиции, и пропускать
     * тут нечего: пачка встаёт вся.
     *
     * <p>Без этой проверки первая порода со временем поглотит вторую:
     * кто-нибудь обернёт в перехват и негодный запрос, и «уменьшить
     * на минус десять процентов» тихо переставит цены всему складу вместо
     * отказа. Поведение верное было и до этого теста — разбор проверил
     * его вручную временной проверкой и удалил её; постоянной не было,
     * то есть от возврата ничто не страховало.
     */
    @Test
    @DisplayName("Негодный запрос не пропускает позиции, а роняет пачку")
    void bulkWithBadRequestChangesNothing() throws Exception {
        Long first = partWithPrice("1000");
        Long second = partWithPrice("2000");
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d],"changes":{"price":-10},
                                 "operations":{"price":"DECREASE_PERCENT"}}"""
                                .formatted(first, second)))
                .andExpect(status().isBadRequest())
                // Проверяем словами, а не кодом ответа: «ни одна позиция
                // не прошла» тоже отвечает четырёхсотым и тоже оставляет цены
                // целыми, и по одному коду эти два исхода неразличимы.
                // Первая редакция теста этого не проверяла — и оставалась
                // зелёной, когда негодный запрос начинал пропускать позиции
                // вместо отказа. Поймано подделкой: перехват `Refused`
                // расширен до `RuntimeException`.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("не может быть отрицательным")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Ни одна из"))));

        // Ни одной — а не «одна прошла, вторая пропущена».
        assertThat(priceOf(first)).isEqualByComparingTo("1000");
        assertThat(priceOf(second)).isEqualByComparingTo("2000");
    }

    /**
     * Позиция пропускается целиком, а не одним полем.
     *
     * <p>Записав ей заметку и не записав цену, мы оставили бы карточку
     * в состоянии, которого никто не просил, и сказали бы «пропущена»
     * о наполовину изменённой.
     */
    @Test
    @DisplayName("Непрошедшей позиции не меняются и остальные поля")
    void rejectedPositionKeepsItsOtherFields() throws Exception {
        Long rich = partWithPrice("1000");
        Long cheap = partWithPrice("100");
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d],
                                 "changes":{"price":500,"section":"зима"},
                                 "operations":{"price":"DECREASE_AMOUNT"}}"""
                                .formatted(rich, cheap)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1));

        assertThat(sectionOf(rich)).isEqualTo("зима");
        assertThat(sectionOf(cheap)).isNull();
    }

    /**
     * Округление арифметическое, а не всегда вверх.
     *
     * <p>Это цена, а не наценка прайс-листа: «до 1000» от 24 300 обязано дать
     * 24 000. Всегда вверх было бы тихой наценкой в пользу продавца, о которой
     * на экране не написано, — и заметить её можно только сложив прайс руками.
     *
     * <p>Отдельным методом от «24 800 → 25 000»: то число даёт 25 000
     * и при округлении вверх, то есть проверяет только сам факт округления.
     */
    @Test
    @DisplayName("«Округлить до 1000»: 24 300 — это 24 000, а не 25 000")
    void roundsDownByArithmetic() throws Exception {
        Long part = partWithPrice("24300");
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":1000,"priceOp":"ROUND_TO","published":true}"""))
                .andExpect(status().isOk());

        assertThat(priceOf(part)).isEqualByComparingTo("24000");
    }

    /**
     * Пустая цена — не ноль, и в карточке тоже.
     *
     * <p>Придуманный ноль превратил бы «поднять на 5 %» в «поставить ноль»,
     * то есть в снятое объявление. Правка списком такую позицию пропускает
     * молча (их там тридцать тысяч), а здесь человек выбрал операцию именно
     * этой карточке — молчать нельзя, нужен отказ словами.
     */
    @Test
    @DisplayName("В карточке процент от незаполненной цены — отказ, а не ноль")
    void cardRefusesArithmeticOnEmptyPrice() throws Exception {
        Long part = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price)
                VALUES (1, 'Крыло без цены', NULL) RETURNING id""", Long.class));
        MockHttpSession session = login("vladelec");

        mvc.perform(put("/api/parts/" + part).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":10,"priceOp":"DECREASE_PERCENT","published":true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("не заполнена")));

        assertThat(priceOf(part)).isNull();
    }

    /**
     * Те же шесть операций — у всех четырёх денежных полей.
     *
     * <p>Код общий, но «то же самое, только по другому полю» этот проект
     * ловил уже трижды: проверка, стоящая только на цене, не отвечает
     * за минимальную цену, себестоимость и цену установки.
     */
    @Test
    @DisplayName("Процентом и суммой двигаются все четыре денежных поля")
    void bulkMovesEveryMoneyField() throws Exception {
        Long part = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, min_price, cost_price,
                                  installation_price)
                VALUES (1, 'Бампер', 10000, 8000, 5000, 2400) RETURNING id""", Long.class));
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d],
                                 "changes":{"minPrice":10,"costPrice":500,
                                            "installationPrice":1000},
                                 "operations":{"minPrice":"DECREASE_PERCENT",
                                               "costPrice":"INCREASE_AMOUNT",
                                               "installationPrice":"ROUND_TO"}}"""
                                .formatted(part)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1));

        assertThat(moneyOf(part, "min_price")).isEqualByComparingTo("7200");
        assertThat(moneyOf(part, "cost_price")).isEqualByComparingTo("5500");
        // Вниз, а не вверх: 2400 до тысяч — это 2000. Правило одно на все
        // четыре поля, и проверять его надо не только на цене.
        assertThat(moneyOf(part, "installation_price")).isEqualByComparingTo("2000");
        // Цену не трогали — она и не тронулась.
        assertThat(priceOf(part)).isEqualByComparingTo("10000");
    }

    /**
     * Отказ называет своё поле и не придумывает последствий.
     *
     * <p>Пока слова были одни на все четыре поля, «уменьшить себестоимость
     * до нуля» отвечало «Нулевая цена в прайс не уедет — поставьте цену
     * числом»: себестоимости в прайсе нет и не было. Владелец, который
     * поверит такому тексту, пойдёт искать несуществующую связь между
     * себестоимостью и объявлением.
     */
    @Test
    @DisplayName("Отказ по себестоимости говорит про себестоимость, а не про прайс")
    void refusalNamesItsOwnField() throws Exception {
        Long ok = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, cost_price)
                VALUES (1, 'Дверь', 5000) RETURNING id""", Long.class));
        Long zeroed = inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, cost_price)
                VALUES (1, 'Ручка', 500) RETURNING id""", Long.class));
        MockHttpSession session = login("vladelec");

        mvc.perform(post("/api/parts/bulk").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partIds":[%d,%d],"changes":{"costPrice":500},
                                 "operations":{"costPrice":"DECREASE_AMOUNT"}}"""
                                .formatted(ok, zeroed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectedReason").value(
                        org.hamcrest.Matchers.containsString("Себестоимость позиции")))
                .andExpect(jsonPath("$.rejectedReason").value(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("прайс"))));

        assertThat(moneyOf(ok, "cost_price")).isEqualByComparingTo("4500");
        assertThat(moneyOf(zeroed, "cost_price")).isEqualByComparingTo("500");
    }

    private String codeOf(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT public_code FROM part WHERE id = ?", String.class, part));
    }

    private BigDecimal moneyOf(Long part, String column) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT " + column + " FROM part WHERE id = ?", BigDecimal.class, part));
    }

    private Long partWithPrice(String price) {
        return inTenant(() -> jdbc.queryForObject("""
                INSERT INTO part (category_id, title, price, is_published)
                VALUES (1, 'Фара Toyota Camry 2006 перед. прав. (б/у)', ?, true)
                RETURNING id""", Long.class, new BigDecimal(price)));
    }

    private BigDecimal priceOf(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT price FROM part WHERE id = ?", BigDecimal.class, part));
    }

    private String sectionOf(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT section FROM part WHERE id = ?", String.class, part));
    }

    private Boolean publishedOf(Long part) {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT is_published FROM part WHERE id = ?", Boolean.class, part));
    }

    private BigDecimal price() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT price FROM part WHERE id = ?", BigDecimal.class, partId));
    }

    private String note() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT note FROM part WHERE id = ?", String.class, partId));
    }

    private Long priceChangedBy() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT price_changed_by FROM part WHERE id = ?", Long.class, partId));
    }

    private Instant priceChangedAt() {
        return inTenant(() -> {
            var value = jdbc.queryForObject(
                    "SELECT price_changed_at FROM part WHERE id = ?",
                    java.sql.Timestamp.class, partId);
            return value == null ? null : value.toInstant();
        });
    }

    private Long updatedBy() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT updated_by FROM part WHERE id = ?", Long.class, partId));
    }

    private Long member(String login, String displayName, String role) {
        var found = jdbc.queryForList(
                "SELECT id FROM tenant_member WHERE login = ?", Long.class, login);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO tenant_member (display_name, role, login, password_hash)
                VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, displayName, role, login, passwordEncoder.encode("пароль"));
    }

    private MockHttpSession login(String login) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"editco","login":"%s","password":"пароль"}"""
                                .formatted(login)))
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
