package ru.partsflow.intake;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.partsflow.platform.security.CurrentUser;

/**
 * Карточка машины и её правка (задача 0364).
 *
 * <p>Читает тот же круг, что видит список машин и их затраты, — общим
 * правилом {@code /api/intake/**}; правит тот, кто заводит машину
 * ({@code intake.receive}, как у {@code POST /api/intake/donors}): ошибку
 * в годе исправляет тот, кто её сделал.
 */
@RestController
@RequestMapping("/api/intake")
public class DonorCardController {

    private final DonorEditService edits;

    public DonorCardController(DonorEditService edits) {
        this.edits = edits;
    }

    /** Карточка: плитка «N запчастей», строки машины словами и значения формы. */
    @GetMapping("/donors/{id}")
    public DonorEditService.DonorDetails card(@PathVariable long id) {
        return edits.detailsOf(id);
    }

    /** Правка формой «карандаш»: те же поля, что при заведении. */
    @PutMapping("/donors/{id}")
    @PreAuthorize("hasAuthority('intake.receive')")
    public DonorEditService.DonorSaved update(@PathVariable long id,
                                              @Valid @RequestBody DonorEditService.DonorFields change) {
        return edits.update(id, change, CurrentUser.memberId());
    }

    /** Руль, КПП и привод словами для выбора на форме машины. */
    @GetMapping("/donor-vocabulary")
    public DonorEditService.Vocabulary vocabulary() {
        return edits.vocabulary();
    }

    /**
     * Номер, который форма «Создание донора» подставит, — наибольший плюс
     * один (вписанные руками и пришедшие переносом последовательность
     * не двигают). Под тем же правом, что заведение: подставлять номер
     * тому, кто машину не заведёт, незачем.
     */
    @GetMapping("/donors/next-number")
    @PreAuthorize("hasAuthority('intake.receive')")
    public NextNumber nextNumber() {
        return new NextNumber(edits.nextNumber());
    }

    public record NextNumber(long number) {
    }
}
