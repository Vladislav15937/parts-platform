package ru.partsflow.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TextBlockRepository extends JpaRepository<TextBlock, Long> {

    List<TextBlock> findAllByOrderByNameAsc();

    /** Тот же ключ, что у уникального индекса схемы: без регистра и пробелов по краям. */
    @Query("SELECT b FROM TextBlock b WHERE lower(trim(b.name)) = lower(trim(:name))")
    Optional<TextBlock> findByNormalizedName(String name);

    /** Блок по тексту дословно — для переноса, чтобы одинаковые тексты не плодили блоки. */
    Optional<TextBlock> findFirstByBody(String body);
}
