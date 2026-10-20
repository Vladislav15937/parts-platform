package ru.partsflow.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Именованный текстовый блок описания объявления, как у Bazon (задача 0337,
 * схема — {@code tenant/095}): позиция выбирает блок по имени, текст уезжает
 * в описание дословно, последним блоком.
 */
@Entity
@Table(name = "text_block")
public class TextBlock {

    /**
     * Текст блока позиции в запросе — одно выражение на склад, выгрузку таблицы и файл
     * Дрома (алиас позиции {@code p}): выбранный именованный блок, иначе копия текста.
     * Три копии условия разошлись бы, и правка блока была бы видна в одном месте
     * из трёх.
     */
    public static final String EFFECTIVE_SQL =
            "COALESCE((SELECT tb.body FROM text_block tb WHERE tb.id = p.text_block_id), p.text_block)";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String body;

    protected TextBlock() {
    }

    public TextBlock(String name, String body) {
        this.name = name;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
