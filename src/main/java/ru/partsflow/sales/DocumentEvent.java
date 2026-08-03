package ru.partsflow.sales;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Запись в истории документа.
 *
 * <p>Это не аудит полей. {@code audit_log} отвечает на вопрос «что изменилось
 * в таблице», а менеджеру нужен ответ на «что происходило со сделкой»:
 * «создана и зарезервирована», «перенесено в новую сделку 67971». Собрать
 * второе из первого нельзя — в аудите нет ни намерения, ни формулировки.
 */
@org.hibernate.annotations.Immutable
@Entity
@Table(name = "document_event")
public class DocumentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type", nullable = false)
    private String documentType;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String message;

    @Column(name = "author_id")
    private Long authorId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected DocumentEvent() {
    }

    public static DocumentEvent forDeal(Long dealId, String eventType, String message, Long authorId) {
        return of("DEAL", dealId, eventType, message, authorId);
    }

    public static DocumentEvent forReturn(Long returnId, String eventType, String message,
                                          Long authorId) {
        return of("RETURN", returnId, eventType, message, authorId);
    }

    private static DocumentEvent of(String documentType, Long documentId, String eventType,
                                    String message, Long authorId) {
        DocumentEvent e = new DocumentEvent();
        e.documentType = documentType;
        e.documentId = documentId;
        e.eventType = eventType;
        e.message = message;
        e.authorId = authorId;
        return e;
    }

    public Long getId() { return id; }
    public String getDocumentType() { return documentType; }
    public Long getDocumentId() { return documentId; }
    public String getEventType() { return eventType; }
    public String getMessage() { return message; }
    public Long getAuthorId() { return authorId; }
    public Instant getCreatedAt() { return createdAt; }
}
