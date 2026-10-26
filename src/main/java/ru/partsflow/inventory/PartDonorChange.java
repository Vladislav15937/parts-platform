package ru.partsflow.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.shared.NotFound;

import java.util.Objects;

/**
 * Перевязка позиции на другой номер донора (задача 0365, решение владельца
 * продукта 19.10.2026 «Всё как у bazon»).
 *
 * <p>У позиции с машиной-донором поля машины закрыты замком: марка, модель,
 * кузов, двигатель, год, комплектация и поколение всегда равны машине, и правка
 * одной детали «мимо» неё давала расхождение названия, прайса и подбора.
 * Исправить машину у детали можно одним путём — выбрать другой «Номер донора».
 *
 * <p><b>Что делает смена — по правилу заведения</b> (что делает Bazon, на обходе
 * не видели; до обхода действует это умолчание, оно названо в PR): позиция
 * становится такой, какой её завела бы приёмка с новой машины.
 * <ul>
 *   <li>своя машина позиции — вписанная руками ({@code manual_*}) и выбранная
 *       из справочника ({@code bazon_*}) — снимается: она принадлежала прежней
 *       машине, а приёмка с донора своей не ставит. Журналом, «было» видно;</li>
 *   <li>применимость, поставленную прежней машиной и не подтверждённую человеком,
 *       снимает {@link CatalogService#dropDonorFit}, новую ставит
 *       {@link CatalogService#applyFromDonor} — тем же путём, что приёмка (0363);</li>
 *   <li>название пересобирается формулой (0336).</li>
 * </ul>
 *
 * <p>Позиции без донора номер донора не навязывается: её машина правится полями
 * формы, как раньше (п. 3 задачи), — привязать её к машине здесь нельзя.
 */
@Service
public class PartDonorChange {

    private static final Logger log = LoggerFactory.getLogger(PartDonorChange.class);

    private final PartRepository parts;
    private final PartIdentity identity;
    private final CatalogService catalog;
    private final PartTitles titles;
    private final PartChangeLog partChanges;
    private final JdbcTemplate jdbc;

    public PartDonorChange(PartRepository parts, PartIdentity identity, CatalogService catalog,
                           PartTitles titles, PartChangeLog partChanges, JdbcTemplate jdbc) {
        this.parts = parts;
        this.identity = identity;
        this.catalog = catalog;
        this.titles = titles;
        this.partChanges = partChanges;
        this.jdbc = jdbc;
    }

    /**
     * @param donorId новая машина; та же — ничего не меняется
     * @return позиция после смены
     */
    @Transactional
    public Part change(Long partId, Long donorId, Long authorId) {
        // Под блокировкой строки, как правка карточки: смена донора и правка
        // той же позиции идут по очереди.
        Part part = parts.findByIdForUpdate(partId).orElseThrow(() -> NotFound.PART.error(partId));
        if (donorId == null) {
            throw new IllegalArgumentException("Выберите машину-донора из списка");
        }
        Long was = part.getDonorId();
        if (was == null) {
            log.warn("Смена донора позиции {} отбита: позиция без донора", partId);
            throw new IllegalArgumentException("Позиция заведена без машины-донора — её марку,"
                    + " модель и остальное правят строками формы, номер донора у неё не выбирается");
        }
        if (Objects.equals(was, donorId)) {
            return part;
        }
        Integer found = jdbc.queryForObject("SELECT count(*) FROM donor WHERE id = ?",
                Integer.class, donorId);
        if (found == null || found == 0) {
            throw NotFound.DONOR.error(donorId);
        }
        // Сущностью: слушатель журнала пишет «Донор: 261 → 262» в историю позиции.
        part.setDonorId(donorId);
        identity.clearOwnVehicle(partId, authorId);
        catalog.dropDonorFit(partId, was);
        catalog.applyFromDonor(partId, donorId, authorId);
        titles.retitle(part);
        part.touchedBy(authorId);
        // Марка и модель уезжают в прайс: площадке нужна дельта.
        partChanges.changed(partId);
        return part;
    }
}
