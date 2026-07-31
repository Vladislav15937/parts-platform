package ru.partsflow.sales;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentEventRepository extends JpaRepository<DocumentEvent, Long> {

    List<DocumentEvent> findByDocumentTypeAndDocumentIdOrderByIdAsc(String documentType, Long documentId);
}
