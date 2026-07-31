--liquibase formatted sql

--changeset partsflow:catalog-015-part-kinds-live3 runOnChange:false
--comment Эталоны, которых не хватило при разборе остатка руками

-- Найдено не составлением словаря, а построчным разбором тех семисот
-- сорока трёх написаний, что остались после второй порции: тридцать пять
-- деталей, которым в справочнике не нашлось ни эталона, ни синонима —
-- домкрат, рессора, люк, педаль тормоза, набор инструментов. Это не редкость
-- склада, а провал словаря: под ними двести с лишним карточек, и без эталона
-- их некуда сопоставить даже руками.

INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Заглушка кузова', ARRAY['заглушка кузова', 'заглушка двери', 'заглушка крышки багажника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление крыла', ARRAY['крепление крыла', 'кронштейн крыла'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крюк буксировочный', ARRAY['крюк буксировочный', 'петля буксировочная', 'проушина буксировочная'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Люк', ARRAY['люк', 'люк крыши', 'мотор люка'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Молдинг крыши', ARRAY['молдинг крыши', 'молдинг на крышу', 'водосток'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клипса', ARRAY['клипса', 'клипса бампера', 'пистон обшивки'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Рама', ARRAY['рама', 'рама кузова'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Лампа', ARRAY['лампа', 'лампочка', 'лампа галогеновая', 'лампа ксенон'], false
  FROM catalog.part_category c
 WHERE c.slug = 'optika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кожух ремня ГРМ', ARRAY['кожух грм', 'кожух ремня грм', 'крышка ремня грм', 'защита грм'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ремень ГРМ', ARRAY['ремень грм', 'цепь грм', 'комплект грм'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Свеча накаливания', ARRAY['свеча накаливания'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Теплообменник', ARRAY['теплообменник', 'радиатор масляный', 'маслоохладитель'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Подшипник', ARRAY['подшипник', 'подшипник шариковый'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Отбойник амортизатора', ARRAY['отбойник амортизатора', 'отбойник стойки'], true
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Пыльник стойки амортизатора', ARRAY['пыльник стойки', 'пыльник стойки амортизатора', 'пыльник амортизатора'], true
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Рессора', ARRAY['рессора', 'лист рессоры'], true
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Бачок тормозной жидкости', ARRAY['бачок тормозной жидкости', 'бачок для тормозной жидкости'], false
  FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Педаль тормоза', ARRAY['педаль тормоза', 'накладка педали тормоза'], false
  FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Прокладка глушителя', ARRAY['прокладка глушителя', 'кольцо глушителя'], false
  FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крышка радиатора', ARRAY['крышка радиатора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Фильтр салонный', ARRAY['фильтр салонный', 'салонный фильтр', 'фильтр салона'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок круиз-контроля', ARRAY['блок круиз-контроля', 'блок управления круиз-контролем'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления иммобилайзером', ARRAY['блок управления иммобилайзером', 'блок иммобилайзера', 'блок иммобилайзер'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления фарами', ARRAY['блок управления фарами', 'блок управления светом'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления подвеской', ARRAY['блок управления подвеской'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления полным приводом', ARRAY['блок управления полным приводом', 'блок управления раздаткой', 'блок управления 4wd'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Видеорегистратор', ARRAY['видеорегистратор'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Радар-детектор', ARRAY['радар детектор', 'радар-детектор'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик дождя', ARRAY['датчик дождя'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик света', ARRAY['датчик света', 'датчик автосвета'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик ускорения', ARRAY['датчик ускорения', 'датчик замедления', 'датчик курсовой устойчивости'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Инвертор', ARRAY['инвертор'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Прикуриватель', ARRAY['прикуриватель', 'заглушка прикуривателя'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Усилитель звука', ARRAY['усилитель звука', 'усилитель магнитофона', 'сабвуфер'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Домкрат', ARRAY['домкрат', 'ручка домкрата', 'крепление домкрата'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kolesa'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Набор инструментов', ARRAY['набор инструментов', 'набор автомобильный', 'ящик для инструментов', 'ключ балонный', 'знак аварийной остановки'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kolesa'
ON CONFLICT DO NOTHING;

--rollback DELETE FROM catalog.part_kind WHERE name IN ('Заглушка кузова', 'Крепление крыла', 'Крюк буксировочный', 'Люк', 'Молдинг крыши', 'Клипса', 'Рама', 'Лампа', 'Кожух ремня ГРМ', 'Ремень ГРМ', 'Свеча накаливания', 'Теплообменник', 'Подшипник', 'Отбойник амортизатора', 'Пыльник стойки амортизатора', 'Рессора', 'Бачок тормозной жидкости', 'Педаль тормоза', 'Прокладка глушителя', 'Крышка радиатора', 'Фильтр салонный', 'Блок круиз-контроля', 'Блок управления иммобилайзером', 'Блок управления фарами', 'Блок управления подвеской', 'Блок управления полным приводом', 'Видеорегистратор', 'Радар-детектор', 'Датчик дождя', 'Датчик света', 'Датчик ускорения', 'Инвертор', 'Прикуриватель', 'Усилитель звука', 'Домкрат', 'Набор инструментов');
