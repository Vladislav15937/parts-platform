package ru.partsflow.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PartNameRepository extends JpaRepository<PartName, Long> {

    /**
     * Поиск по нормализованному написанию: в БД уникальный индекс по
     * {@code lower(btrim(name))}, и искать надо так же, иначе «Фара» и «фара »
     * заведутся дважды и разъедутся по сопоставлению.
     */
    @Query(value = "SELECT * FROM part_name WHERE lower(btrim(name)) = lower(btrim(:name))",
            nativeQuery = true)
    Optional<PartName> findByNormalizedName(@Param("name") String name);

    /** Экран «нераспознанные»: свежие сверху, как в кабинете Bazon. */
    Page<PartName> findByMatchStatusOrderByCreatedAtDesc(PartName.MatchStatus status,
                                                         Pageable pageable);

    /**
     * То же, но сначала написания, под которыми больше позиций.
     *
     * <p>Порядок по времени экран не разгребает: импорт склада заводит все
     * написания одной секундой, и внутри неё сортировка случайна. Счётчик
     * отвечает на вопрос, который задаёт владелец, — какое написание держит
     * двести карточек, а какое одну, заведённую вчера по ошибке.
     *
     * <p><b>Заканчивается номером записи, и это не украшение.</b> Число
     * позиций и время создания оба неуникальны: у 353 написаний живого
     * клиента счётчик равен единице, а заведены они одной секундой импорта.
     * Полного порядка нет, и страницы начинают перекрываться — обход
     * по двадцать штук отдал 679 строк при 579 уникальных: сотня написаний
     * показана дважды, сотня не показана вовсе. Экран этого не видел, потому
     * что тянет список с начала растущими кусками, но эндпоинт отдаёт
     * неполноту любому, кто листает страницами.
     */
    Page<PartName> findByMatchStatusOrderByUsageCountDescCreatedAtDescIdDesc(
            PartName.MatchStatus status, Pageable pageable);

    long countByMatchStatus(PartName.MatchStatus status);

    /**
     * Пересчитывает счётчик по карточкам склада.
     *
     * <p>Нужен после переноса из чужой системы: тот заводит карточки пакетом
     * мимо {@link PartNameService#resolve}, и счётчик остаётся нулевым.
     * Экран разбора при этом показывает «позиций пока нет» под написанием,
     * за которым висит две сотни карточек, — и теряет единственный ориентир,
     * что чинить раньше.
     */
    @Modifying
    @Query(value = """
            UPDATE part_name pn
               SET usage_count = (SELECT count(*) FROM part p WHERE p.part_name_id = pn.id)""",
            nativeQuery = true)
    int recountUsage();

    /**
     * Счётчик использований ведётся в БД инкрементом, а не чтением-записью
     * в приложении: приёмка идёт с нескольких телефонов одновременно, и два
     * приёмщика с одним наименованием затрут друг другу счёт.
     */
    @Modifying
    @Query(value = "UPDATE part_name SET usage_count = usage_count + 1, updated_at = now() WHERE id = :id",
            nativeQuery = true)
    void incrementUsage(@Param("id") Long id);
}
