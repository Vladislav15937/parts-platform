package ru.partsflow.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.partsflow.inventory.PartTitleGenerator;

import java.util.List;

/**
 * Достаёт из донора части заголовка: марку, модель, кузов, двигатель, год.
 *
 * <p>Донор хранит ссылки в общий каталог, а заголовок собирается из названий,
 * поэтому нужен один запрос с join'ами. Отдельный класс, а не метод в сервисе:
 * это единственное место, где приёмка вообще заглядывает в каталог машин.
 *
 * <p><b>Пустые поля — нормальный режим, а не ошибка.</b> Каталог марок
 * и моделей ещё не наполнен, и на неполных данных заголовок получится короче:
 * «Фара левая перед. лев. (б/у) 8414012530» вместо полного. Это лучше, чем
 * отказать в приёмке, — генератор заголовка сознательно терпит null во всех
 * частях, кроме вида детали.
 */
@Component
public class DonorVehicleResolver {

    private final JdbcTemplate jdbc;

    public DonorVehicleResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return части заголовка или {@code null}, если донора нет — контрактная деталь */
    public PartTitleGenerator.VehicleTitlePart resolve(Long donorId) {
        if (donorId == null) {
            return null;
        }
        List<PartTitleGenerator.VehicleTitlePart> found = jdbc.query("""
                SELECT b.name  AS brand,
                       m.name  AS model,
                       -- Введённое руками сильнее справочника: приёмщик писал
                       -- его, глядя в документы машины, а поколение подобрано
                       -- по году и у модели вне каталога отсутствует вовсе.
                       COALESCE(d.body_code, g.code)              AS body_code,
                       COALESCE(d.engine_code, mo.engine_code)    AS engine_code,
                       d.year
                  FROM donor d
                  LEFT JOIN catalog.brand b        ON b.id = d.brand_id
                  LEFT JOIN catalog.model m        ON m.id = d.model_id
                  LEFT JOIN catalog.generation g   ON g.id = d.generation_id
                  LEFT JOIN catalog.modification mo ON mo.id = d.modification_id
                 WHERE d.id = ?""",
                (rs, i) -> new PartTitleGenerator.VehicleTitlePart(
                        rs.getString("brand"),
                        rs.getString("model"),
                        rs.getString("body_code"),
                        rs.getString("engine_code"),
                        rs.getObject("year") == null ? null : rs.getInt("year")),
                donorId);

        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Поколение по модели и году.
     *
     * <p>Год приёмщик знает из документов, поколение — вопрос, на который он
     * отвечать не должен. Подбор жил только в PWA
     * ({@code frontend/src/catalog/vehicles.ts}), то есть машина, заведённая
     * любым другим путём — запросом, переносом, будущим импортом, — оставалась
     * без поколения навсегда, а вместе с ним без кузова в заголовке
     * и в применимости.
     *
     * <p><b>Не попавший ни в один диапазон год соседнее поколение
     * не подставляет.</b> Тихо поставленная чужая применимость отправит деталь
     * клиенту, которому она не подойдёт, и заметить это можно будет только
     * от него.
     *
     * <p>Несколько подошедших — берём самое позднее, как и PWA: диапазоны
     * пересекаться не должны, но справочник живой, и полагаться на это нельзя.
     *
     * @return идентификатор поколения либо {@code null} — модели нет, года нет
     *         или год вне всех диапазонов
     */
    public Long generationFor(Long modelId, Short year) {
        if (modelId == null || year == null) {
            return null;
        }
        List<Long> found = jdbc.queryForList("""
                SELECT g.id
                  FROM catalog.generation g
                 WHERE g.model_id = ?
                   AND g.year_from IS NOT NULL
                   AND ? BETWEEN g.year_from AND COALESCE(g.year_to, 9999)
                 ORDER BY g.year_from DESC
                 LIMIT 1""",
                Long.class, modelId, year);

        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Проставляет поколение машинам, у которых его нет.
     *
     * <p>Перенос из прежней системы заводит доноров своим SQL, мимо
     * {@code IntakeService.registerDonor}, — и поколение у них не появлялось
     * никогда. Замерено на живой выгрузке: 440 машин, у всех есть модель
     * и год, поколения нет ни у одной. А от него зависит кузов в заголовке
     * и подбор детали по машине: продавец, выбравший поколение, не находил
     * ничего.
     *
     * <p>Правило подбора не повторяется здесь запросом, а зовётся тем же
     * {@link #generationFor}: написанное дважды, оно разъедется на первой же
     * правке — и машины, заведённые руками и переносом, начнут отвечать
     * по-разному.
     *
     * <p>Считается по парам «модель и год», а не по машинам: у переехавшего
     * клиента четыре сотни машин на несколько десятков таких пар, и спрашивать
     * справочник на каждую значит потратить перенос на повторные запросы
     * об одном и том же.
     *
     * <p>Трогает только пустое: выбранное человеком поколение перебивать
     * нельзя — он смотрел в документы, а мы считаем по году.
     *
     * <p><b>Транзакция обязательна.</b> {@code search_path} выставляет
     * провайдер соединений Hibernate при выдаче соединения транзакции;
     * вызванный снаружи, {@code JdbcTemplate} берёт соединение из пула
     * напрямую и уходит в {@code public} — «relation donor does not exist»,
     * то есть пятисотка в конце часового переезда. Ловушка из правил проекта,
     * и это четвёртый раз, когда на неё наступают.
     *
     * @return сколько машин получили поколение
     */
    @org.springframework.transaction.annotation.Transactional
    public int backfillGenerations() {
        List<long[]> pairs = jdbc.query("""
                SELECT DISTINCT d.model_id, d.year
                  FROM donor d
                 WHERE d.generation_id IS NULL
                   AND d.model_id IS NOT NULL
                   AND d.year IS NOT NULL""",
                (rs, i) -> new long[]{rs.getLong("model_id"), rs.getShort("year")});

        int filled = 0;
        for (long[] pair : pairs) {
            Long generationId = generationFor(pair[0], (short) pair[1]);
            if (generationId == null) {
                // Год вне всех диапазонов справочника: соседнее поколение
                // не подставляем — чужая применимость отправит деталь
                // покупателю, которому она не подойдёт.
                continue;
            }
            filled += jdbc.update("""
                    UPDATE donor SET generation_id = ?
                     WHERE generation_id IS NULL AND model_id = ? AND year = ?""",
                    generationId, pair[0], (short) pair[1]);
        }
        return filled;
    }
}
