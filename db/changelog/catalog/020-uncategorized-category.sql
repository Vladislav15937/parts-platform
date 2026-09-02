--liquibase formatted sql

--changeset platform:catalog-020-uncategorized-category runOnChange:true
--comment Категория-заглушка «Не разобрано» для переезда.
--comment
--comment Импорт из прежней системы кладёт каждую позицию в «Не разобрано»:
--comment part.category_id обязателен, а выгрузка категорию не отдаёт, там
--comment есть только наименование. Раскладываются по дереву они потом, через
--comment справочник наименований, — пустая категория честнее выдуманной.
--comment
--comment Сеет её миграция, а не импортёр в рантайме. Раньше строку заводил сам
--comment BazonImporter.ensureUncategorized одним INSERT — и на ячейке
--comment с разделением ролей падал: рабочая роль на catalog имеет только
--comment SELECT (справочники пишет миграция, читают все). SELECT категории
--comment проходил, INSERT отвечал «permission denied for table part_category»,
--comment и весь перенос уезжал пятисоткой ещё до первой позиции. Теперь строка
--comment уже стоит, SELECT её находит, а рантайм в catalog не пишет вовсе.

INSERT INTO catalog.part_category (name, slug, path, sort_order)
VALUES ('Не разобрано', 'uncategorized', 'uncategorized'::ltree, 9999)
ON CONFLICT (path) DO NOTHING;

--rollback DELETE FROM catalog.part_category WHERE slug = 'uncategorized';
