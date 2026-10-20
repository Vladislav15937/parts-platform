package ru.partsflow.inventory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.partsflow.shared.NotFound;

import java.util.List;

/**
 * Справочник именованных текстовых блоков (задача 0337).
 *
 * <p>Имя уникально без регистра и пробелов по краям — это держит индекс схемы,
 * а здесь стоит проверка ради слов: «Операция нарушает целостность данных»
 * не говорит, какое имя занято. Текст хранится как набран, с переводами
 * строк; пустое имя или текст — отказ словами.
 */
@Service
public class TextBlockService {

    private final TextBlockRepository blocks;
    private final PartRepository parts;

    public TextBlockService(TextBlockRepository blocks, PartRepository parts) {
        this.blocks = blocks;
        this.parts = parts;
    }

    @Transactional(readOnly = true)
    public List<TextBlock> list() {
        return blocks.findAllByOrderByNameAsc();
    }

    @Transactional
    public TextBlock create(String name, String body) {
        String cleanName = requireName(name);
        requireBody(body);
        blocks.findByNormalizedName(cleanName).ifPresent(found -> {
            throw taken(cleanName);
        });
        try {
            return blocks.saveAndFlush(new TextBlock(cleanName, body));
        } catch (DataIntegrityViolationException e) {
            // Второй одновременный запрос с тем же именем: проверка выше
            // его не видела, отбил индекс.
            throw taken(cleanName);
        }
    }

    /**
     * Правка имени и текста. Текст блока меняется во всех позициях, которые
     * его выбрали, — для этого блоки и заведены; в прайс он попадает при
     * следующем забирании, отметку «позиция изменилась» это не двигает
     * (тысячи позиций ушли бы в дельту разом).
     */
    @Transactional
    public TextBlock update(long id, String name, String body) {
        TextBlock block = blocks.findById(id).orElseThrow(() -> NotFound.TEXT_BLOCK.error(id));
        String cleanName = requireName(name);
        requireBody(body);
        blocks.findByNormalizedName(cleanName).ifPresent(found -> {
            if (!found.getId().equals(id)) {
                throw taken(cleanName);
            }
        });
        block.setName(cleanName);
        block.setBody(body);
        try {
            return blocks.saveAndFlush(block);
        } catch (DataIntegrityViolationException e) {
            throw taken(cleanName);
        }
    }

    /** Блок, выбранный хоть у одной позиции, не удаляется: объявления остались бы без текста молча. */
    @Transactional
    public void delete(long id) {
        TextBlock block = blocks.findById(id).orElseThrow(() -> NotFound.TEXT_BLOCK.error(id));
        long used = parts.countByTextBlockId(id);
        if (used > 0) {
            throw new IllegalArgumentException("Блок «" + block.getName() + "» выбран у позиций: "
                    + used + ". Сначала выберите им другой блок или уберите выбор");
        }
        blocks.delete(block);
    }

    /**
     * Блок с таким текстом дословно — найденный или заведённый (перенос блока из
     * файлов Bazon, задача 0337). Одинаковые тексты схлопываются в один блок;
     * имя — по тексту, как в {@code tenant/095}: сжатый в одну строку, не длиннее
     * 60 знаков, при совпадении имени у другого текста — « (2)», « (3)».
     * Имя из Bazon («Наличные») в файле выгрузки не приходит — владелец
     * переименовывает блок в карточке.
     */
    @Transactional
    public TextBlock findOrCreateByBody(String body) {
        requireBody(body);
        var found = blocks.findFirstByBody(body);
        if (found.isPresent()) {
            return found.get();
        }
        String flat = body.replaceAll("\\s+", " ").strip();
        String base = flat.length() > NAME_LIMIT ? flat.substring(0, NAME_LIMIT - 1).strip() + "…" : flat;
        String name = base;
        for (int n = 2; blocks.findByNormalizedName(name).isPresent(); n++) {
            name = base + " (" + n + ")";
        }
        return blocks.saveAndFlush(new TextBlock(name, body));
    }

    private static final int NAME_LIMIT = 60;

    /** Блок для выбора в карточке; {@code null} — выбор снят. */
    @Transactional(readOnly = true)
    public TextBlock require(Long id) {
        if (id == null) {
            return null;
        }
        return blocks.findById(id).orElseThrow(() -> NotFound.TEXT_BLOCK.error(id));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите название текстового блока");
        }
        return name.strip();
    }

    private static void requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Впишите текст блока");
        }
    }

    private static IllegalArgumentException taken(String name) {
        return new IllegalArgumentException("Текстовый блок «" + name
                + "» уже есть — выберите его или назовите новый иначе");
    }
}
