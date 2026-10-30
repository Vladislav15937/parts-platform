package ru.partsflow.intake;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.shared.NotFound;
import ru.partsflow.shared.ValueWords;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Карточка машины и её правка после заведения (задача 0364).
 *
 * <p>До неё машину нельзя было исправить вовсе: ошибся годом — и все детали,
 * снятые с неё, наследуют неверный год, а руль, коробку и привод у новой
 * машины было нечем ввести. Правка идёт той же формой, что заведение
 * («карандаш» в карточке машины, как у Bazon).
 *
 * <p><b>Отдельный класс, а не ещё один метод {@code IntakeService}.</b> Там
 * приёмка деталей и поставки; машина как карточка — своя небольшая забота,
 * и правка её не трогает ни склад, ни документы.
 */
@Service
public class DonorEditService {

    private final DonorRepository donors;
    private final SupplyRepository supplies;
    private final DonorDirectory directory;
    private final DonorVehicleResolver vehicles;

    public DonorEditService(DonorRepository donors, SupplyRepository supplies,
                            DonorDirectory directory, DonorVehicleResolver vehicles) {
        this.donors = donors;
        this.supplies = supplies;
        this.directory = directory;
        this.vehicles = vehicles;
    }

    /**
     * Карточка словами и значения для формы правки кодами.
     *
     * <p>Двумя частями, потому что это два разных вопроса: карточка
     * показывает «Правый руль», а форма обязана отправить обратно {@code RIGHT}
     * — и то, чего человек не трогал, уехать ровно тем, что лежало.
     */
    @Transactional(readOnly = true)
    public DonorDetails detailsOf(long donorId) {
        Donor donor = require(donorId);
        DonorDirectory.Card card = directory.cardOf(donorId)
                .orElseThrow(() -> NotFound.DONOR.error(donorId));
        return new DonorDetails(card, DonorFields.of(donor));
    }

    /**
     * Правка машины формой целиком.
     *
     * <p>PUT, а не PATCH, как у карточки позиции: пустое поле формы означает
     * «очищено», иначе стереть ошибочный пробег было бы нечем. Чего на форме
     * нет — код цвета, модификация, место, стадия разбора, — то не трогается:
     * у переехавшей машины код цвета лежит отдельно, и форма, его не знающая,
     * стёрла бы его молча.
     *
     * <p>Журнал действий пишет слушатель Hibernate ({@code donor} в
     * {@code AUDITED}), автор — вошедший: «Машина: Год выпуска 2003 → 2004».
     */
    @Transactional
    public DonorDetails update(long donorId, DonorFields change) {
        Donor donor = require(donorId);
        donor.changeBrand(change.brandId());
        donor.setModelId(change.modelId());
        donor.setYear(change.year());
        donor.setGenerationId(change.generationId() != null
                ? change.generationId()
                // Тем же правилом, что при заведении: поколение по году, если
                // его не выбрали, — год человек знает, поколение спрашивать не должен.
                : vehicles.generationFor(change.modelId(), change.year()));
        donor.setBodyCode(text(change.bodyCode()));
        donor.setEngineCode(text(change.engineCode()));
        donor.setSteering(change.steering());
        donor.setTransmissionType(change.transmissionType());
        donor.setTransmissionModel(text(change.transmissionModel()));
        donor.setDriveType(change.driveType());
        donor.setColor(text(change.color()));
        donor.setEquipmentCode(text(change.equipmentCode()));
        donor.setVin(text(change.vin()));
        donor.setMileageKm(change.mileageKm());
        if (change.supplyId() != null && supplies.findById(change.supplyId()).isEmpty()) {
            throw NotFound.SUPPLY.error(change.supplyId());
        }
        donor.arrivedWith(change.supplyId());
        donor.setNote(text(change.note()));
        donors.saveAndFlush(donor);
        return detailsOf(donorId);
    }

    /**
     * Руль, коробка и привод — коды со словами для выбора на форме.
     *
     * <p>Слова — общим словарём {@link ValueWords}, а не копией на экране:
     * копий слов-значений было пять, и они разошлись (разбор PR 510).
     * Порядок — порядок перечисления.
     */
    public static Vocabulary vocabulary() {
        return new Vocabulary(
                options(Donor.Steering.values(), ValueWords::steering),
                options(Donor.TransmissionType.values(), ValueWords::transmission),
                options(Donor.DriveType.values(), ValueWords::drive));
    }

    private static List<Option> options(Enum<?>[] values, Function<String, String> word) {
        return Arrays.stream(values)
                .map(value -> new Option(value.name(), word.apply(value.name())))
                .toList();
    }

    private Donor require(long donorId) {
        return donors.findById(donorId).orElseThrow(() -> NotFound.DONOR.error(donorId));
    }

    /** Пустое и пробелы — «не заполнено», а не значение. */
    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * Значения формы машины — те, что форма показывает и отправляет.
     *
     * <p>Один тип на ответ и на запрос: форма отправляет ровно то, что
     * получила, с правками, — второй тип с тем же составом разошёлся бы
     * с первым на первом новом поле.
     */
    public record DonorFields(Long brandId, Long modelId, Long generationId, Short year,
                              String bodyCode, String engineCode,
                              Donor.Steering steering, Donor.TransmissionType transmissionType,
                              String transmissionModel, Donor.DriveType driveType,
                              String color, String equipmentCode, String vin,
                              @jakarta.validation.constraints.PositiveOrZero Integer mileageKm,
                              Long supplyId, String note) {

        static DonorFields of(Donor donor) {
            return new DonorFields(donor.getBrandId(), donor.getModelId(),
                    donor.getGenerationId(), donor.getYear(), donor.getBodyCode(),
                    donor.getEngineCode(), donor.getSteering(), donor.getTransmissionType(),
                    donor.getTransmissionModel(), donor.getDriveType(), donor.getColor(),
                    donor.getEquipmentCode(), donor.getVin(), donor.getMileageKm(),
                    donor.getSupplyId(), donor.getNote());
        }
    }

    /** @param card машина словами; @param form значения для правки кодами */
    public record DonorDetails(DonorDirectory.Card card, DonorFields form) {
    }

    public record Option(String code, String title) {
    }

    public record Vocabulary(List<Option> steering, List<Option> transmission,
                             List<Option> drive) {
    }
}
