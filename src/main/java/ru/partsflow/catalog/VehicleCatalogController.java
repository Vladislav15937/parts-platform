package ru.partsflow.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST справочника машин.
 *
 * <p>Только чтение: дерево наполняется миграцией из каталога Дрома, а не
 * руками клиента. Дать заводить свои марки — значит через месяц получить
 * «Тойота», «тойота» и «Toyota» в одном складе, то есть ровно ту беду,
 * от которой избавляет справочник наименований деталей.
 *
 * <p>Проверок роли нет: марка машины не секрет, и нужна она всем — приёмщику
 * при заведении донора, продавцу при поиске по звонку.
 */
@RestController
@RequestMapping("/api/catalog")
public class VehicleCatalogController {

    private final VehicleCatalogService catalog;

    public VehicleCatalogController(VehicleCatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * Весь справочник одним запросом — для предзагрузки на телефон.
     *
     * <p>То же правило, что у справочников приёмки: пять запросов по плохой
     * связи — это пять шансов оборваться и пять частично заполненных кэшей,
     * из которых непонятно, можно ли работать.
     */
    @GetMapping("/vehicles")
    public VehicleCatalogService.Vehicles vehicles() {
        return catalog.all();
    }

    @GetMapping("/brands")
    public List<VehicleCatalogService.Brand> brands(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return catalog.brands(query, limit);
    }

    /**
     * Модели марки.
     *
     * <p>Предел по умолчанию высокий намеренно: у Toyota их 227, и обрезать
     * список на полусотне значит спрятать половину машин, которые как раз
     * и приезжают на разборку.
     */
    @GetMapping("/brands/{brandId}/models")
    public List<VehicleCatalogService.Model> models(
            @PathVariable long brandId,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "limit", defaultValue = "300") int limit) {
        return catalog.models(brandId, query, limit);
    }

    @GetMapping("/models/{modelId}/generations")
    public List<VehicleCatalogService.Generation> generations(@PathVariable long modelId) {
        return catalog.generations(modelId);
    }
}
