package ru.partsflow.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Именованные текстовые блоки описания (задача 0337). Читают все, кто правит
 * карточку; заводит и правит владелец или менеджер — текст блока уезжает
 * в объявления всех позиций, которые его выбрали.
 */
@RestController
@RequestMapping("/api/text-blocks")
public class TextBlockController {

    private static final String MANAGES = "hasAnyRole('OWNER','MANAGER')";

    private final TextBlockService blocks;

    public TextBlockController(TextBlockService blocks) {
        this.blocks = blocks;
    }

    @GetMapping
    public List<BlockView> list() {
        return blocks.list().stream().map(BlockView::of).toList();
    }

    @PostMapping
    @PreAuthorize(MANAGES)
    @ResponseStatus(HttpStatus.CREATED)
    public BlockView create(@RequestBody BlockRequest request) {
        return BlockView.of(blocks.create(request.name(), request.body()));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGES)
    public BlockView update(@PathVariable long id, @RequestBody BlockRequest request) {
        return BlockView.of(blocks.update(id, request.name(), request.body()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGES)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        blocks.delete(id);
    }

    public record BlockRequest(String name, String body) {
    }

    public record BlockView(Long id, String name, String body) {
        static BlockView of(TextBlock block) {
            return new BlockView(block.getId(), block.getName(), block.getBody());
        }
    }
}
