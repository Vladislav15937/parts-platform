package ru.partsflow.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Списки общего справочника машин Bazon для выбора в карточке и на приёмке
 * (задача 0377): марка → модель → кузов; двигатели — списком марки.
 *
 * <p>Только чтение: справочник общий, клиент его не правит. Проверок роли нет,
 * как у {@link VehicleCatalogController}: марка машины не секрет.
 */
@RestController
@RequestMapping("/api/catalog/bazon")
public class BazonVehicleController {

    private final BazonVehicles vehicles;

    public BazonVehicleController(BazonVehicles vehicles) {
        this.vehicles = vehicles;
    }

    @GetMapping("/marks")
    public List<BazonVehicles.Mark> marks() {
        return vehicles.marks();
    }

    @GetMapping("/marks/{markId}/models")
    public List<BazonVehicles.Model> models(@PathVariable long markId) {
        return vehicles.models(markId);
    }

    @GetMapping("/marks/{markId}/motors")
    public List<BazonVehicles.Motor> motors(@PathVariable long markId) {
        return vehicles.motors(markId);
    }

    @GetMapping("/models/{modelId}/bodies")
    public List<BazonVehicles.Body> bodies(@PathVariable long modelId) {
        return vehicles.bodies(modelId);
    }
}
