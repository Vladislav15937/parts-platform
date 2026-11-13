package ru.partsflow.intake;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.inventory.DonorRetitle;
import ru.partsflow.shared.NotFound;
import ru.partsflow.shared.ValueWords;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
    private final DonorRetitle retitles;
    private final DonorNumbers numbers;
    private final DonorStatusService statuses;

    public DonorEditService(DonorRepository donors, SupplyRepository supplies,
                            DonorDirectory directory, DonorVehicleResolver vehicles,
                            DonorRetitle retitles, DonorNumbers numbers,
                            DonorStatusService statuses) {
        this.donors = donors;
        this.supplies = supplies;
        this.directory = directory;
        this.vehicles = vehicles;
        this.retitles = retitles;
        this.numbers = numbers;
        this.statuses = statuses;
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
     *
     * <p><b>Поменялось то, из чего собирается название и объявление, — машина
     * тянет за собой свои позиции</b> (задача 0383): {@link DonorRetitle}
     * пересобирает их названия и отмечает для дельты Дрома в этой же транзакции,
     * и упавшая пересборка откатывает правку машины целиком — машина с новым
     * годом при старых названиях и есть то состояние, которое задача закрывает.
     * Что считать таким полем, решено сверкой с формулами: марка, модель,
     * поколение, год, кузов, двигатель. Руль, коробку, привод, цвет,
     * комплектацию, VIN, пробег, поставку и заметку не читает ни название
     * ({@code PartTitleGenerator}), ни файл Дрома — их правка позиций не трогает,
     * иначе каждая заметка отправляла бы в дельту все детали машины.
     *
     * @param authorId кто правит — он же автор правки названий в истории позиций
     */
    @Transactional
    public DonorSaved update(long donorId, DonorFields change, Long authorId) {
        Donor donor = require(donorId);
        // Поколение — тем же правилом, что при заведении: по году, если его
        // не выбрали, — год человек знает, поколение спрашивать не должен.
        // Считается до записи: сравнить с прежним надо, пока прежнее лежит.
        Long generation = change.generationId() != null
                ? change.generationId()
                : vehicles.generationFor(change.modelId(), change.year());
        boolean vehicleChanged = !Objects.equals(donor.getBrandId(), change.brandId())
                || !Objects.equals(donor.getModelId(), change.modelId())
                || !Objects.equals(donor.getGenerationId(), generation)
                || !Objects.equals(donor.getYear(), change.year())
                || !Objects.equals(donor.getBodyCode(), text(change.bodyCode()))
                || !Objects.equals(donor.getEngineCode(), text(change.engineCode()));
        // Позиции — под блокировку до записи машины: их объявления запоминаются
        // такими, какими были.
        DonorRetitle.Snapshot before = vehicleChanged ? retitles.lock(donorId) : null;
        donor.changeBrand(change.brandId());
        donor.setModelId(change.modelId());
        donor.setYear(change.year());
        donor.setGenerationId(generation);
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
        // Номер правится, но не стирается: пустой в запросе — «не трогали»
        // (у машины без номера в складе не найти ни одной детали).
        if (change.number() != null && !change.number().equals(donor.getNumber())) {
            donor.setNumber(numbers.assign(change.number(), donorId));
        }
        donor.setRestyling(text(change.restyling()));
        donor.setClientDescription(text(change.clientDescription()));
        donor.setVideoUrl(videoUrl(change.videoUrl()));
        donor.setStatusId(statusOf(change.statusId()));
        // Пересборка читает машину из базы, а не из сущности — сначала записать.
        donors.saveAndFlush(donor);
        int renamed = before == null ? 0 : retitles.retitle(before, authorId);
        DonorDetails details = detailsOf(donorId);
        return new DonorSaved(details.card(), details.form(), renamed);
    }

    /**
     * Руль, коробка, привод и статус — коды со словами для выбора на форме.
     *
     * <p>Слова — общим словарём {@link ValueWords}, а не копией на экране:
     * копий слов-значений было пять, и они разошлись (разбор PR 510).
     * Порядок — порядок перечисления. Статусы — список клиента
     * ({@code donor_status}), кодом служит номер строки: на экране его
     * не показывают, он уезжает обратно в {@code statusId}.
     */
    @Transactional(readOnly = true)
    public Vocabulary vocabulary() {
        return new Vocabulary(
                options(Donor.Steering.values(), ValueWords::steering),
                options(Donor.TransmissionType.values(), ValueWords::transmission),
                options(Donor.DriveType.values(), ValueWords::drive),
                statuses.list().stream()
                        .map(status -> new Option(String.valueOf(status.id()), status.name()))
                        .toList());
    }

    /** Номер, который форма заведения подставит («Номер донора — 512»). */
    @Transactional(readOnly = true)
    public long nextNumber() {
        return numbers.peek();
    }

    /** Статус из списка клиента; несуществующий — 400 словами, а не отказ внешнего ключа. */
    Long statusOf(Long statusId) {
        if (statusId != null) {
            statuses.require(statusId);
        }
        return statusId;
    }

    /** Длиннее адреса браузеры не открывают. */
    static final int VIDEO_URL_LIMIT = 2000;

    private static final java.util.regex.Pattern LINK =
            java.util.regex.Pattern.compile("(?i)(https?://)?[^\\s/]+\\.[^\\s]+");

    /**
     * Ссылка на видео: пусто — «нет»; иначе то, что похоже на адрес.
     *
     * <p>Проверяется форма, а не доступность: ходить на чужой сайт при
     * сохранении машины значило бы зависеть от него. Пробел внутри или
     * отсутствие точки — почти наверняка вставили не то (описание вместо
     * ссылки), и это 400 словами, а не молча сохранённый мусор.
     */
    static String videoUrl(String value) {
        String link = text(value);
        if (link == null) {
            return null;
        }
        if (link.length() > VIDEO_URL_LIMIT || !LINK.matcher(link).matches()) {
            throw new IllegalArgumentException(
                    "Ссылка на видео не похожа на адрес — вставьте ссылку целиком, например https://youtu.be/…");
        }
        return link;
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
    static String text(String value) {
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
                              Long supplyId, String note,
                              Long number, String restyling, String clientDescription,
                              String videoUrl, Long statusId) {

        static DonorFields of(Donor donor) {
            return new DonorFields(donor.getBrandId(), donor.getModelId(),
                    donor.getGenerationId(), donor.getYear(), donor.getBodyCode(),
                    donor.getEngineCode(), donor.getSteering(), donor.getTransmissionType(),
                    donor.getTransmissionModel(), donor.getDriveType(), donor.getColor(),
                    donor.getEquipmentCode(), donor.getVin(), donor.getMileageKm(),
                    donor.getSupplyId(), donor.getNote(), donor.getNumber(),
                    donor.getRestyling(), donor.getClientDescription(), donor.getVideoUrl(),
                    donor.getStatusId());
        }
    }

    /** @param card машина словами; @param form значения для правки кодами */
    public record DonorDetails(DonorDirectory.Card card, DonorFields form) {
    }

    /**
     * Ответ правки: карточка, значения формы и сколько позиций машины
     * переименовано — без числа человек не знает, что правка года потянула
     * за собой шестьдесят пять деталей.
     */
    public record DonorSaved(DonorDirectory.Card card, DonorFields form, int renamed) {
    }

    public record Option(String code, String title) {
    }

    public record Vocabulary(List<Option> steering, List<Option> transmission,
                             List<Option> drive, List<Option> statuses) {
    }
}
