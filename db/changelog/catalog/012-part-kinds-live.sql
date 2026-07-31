--liquibase formatted sql

--changeset platform:catalog-012-part-kinds-live splitStatements:false
--comment Расширение справочника видов деталей написаниями живого склада.
--comment
--comment Новым changeset'ом, а не правкой 011: тот уже прогонялся, и правка
--comment сломала бы чек-сумму. Источник — перенос «YARD Ткацкая», 35 841
--comment позиция: автосопоставление взяло десятую часть написаний, а 912
--comment не имели даже похожего эталона — под ними лежало 18 125 карточек,
--comment половина склада. Здесь закрыты сто крупнейших, это 13 108 карточек.
--comment
--comment Синонимы отделены от эталонов намеренно: девятнадцать написаний
--comment оказались не новой деталью, а другим порядком слов или буквой «ё» —
--comment «Колодки тормозные», «Козырек солнцезащитный», «рейка рулевая».
--comment Заводить под них эталон значило бы раздвоить справочник.
--comment
--comment Чего здесь нет намеренно: «стекло» (286 карточек), «Габарит» (98)
--comment и «гидроусилитель» (84). По этим словам деталь не определяется —
--comment стекло бывает лобовое, заднее, двери и форточка, — и эталон для них
--comment отправил бы деталь в чужую категорию. Их разбирает человек, глядя
--comment на карточку.

-- Синонимы к эталонам, которые уже есть.
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['топливная рейка']
 WHERE name = 'Топливная рампа' AND NOT (synonyms @> ARRAY['топливная рейка']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['мотор дворников']
 WHERE name = 'Моторчик стеклоочистителя' AND NOT (synonyms @> ARRAY['мотор дворников']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['приемная труба глушителя', 'приемная труба']
 WHERE name = 'Приёмная труба' AND NOT (synonyms @> ARRAY['приемная труба глушителя', 'приемная труба']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['датчик положения распредвала']
 WHERE name = 'Датчик распредвала' AND NOT (synonyms @> ARRAY['датчик положения распредвала']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['датчик кислородный']
 WHERE name = 'Датчик кислорода' AND NOT (synonyms @> ARRAY['датчик кислородный']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['козырек солнцезащитный', 'солнцезащитный козырек']
 WHERE name = 'Солнцезащитный козырёк' AND NOT (synonyms @> ARRAY['козырек солнцезащитный', 'солнцезащитный козырек']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['колонка рулевая']
 WHERE name = 'Рулевая колонка' AND NOT (synonyms @> ARRAY['колонка рулевая']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['ручка двери внешняя']
 WHERE name = 'Ручка двери наружная' AND NOT (synonyms @> ARRAY['ручка двери внешняя']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['колодки тормозные']
 WHERE name = 'Тормозные колодки' AND NOT (synonyms @> ARRAY['колодки тормозные']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['мотор печки']
 WHERE name = 'Моторчик печки' AND NOT (synonyms @> ARRAY['мотор печки']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['бачок расширительный']
 WHERE name = 'Расширительный бачок' AND NOT (synonyms @> ARRAY['бачок расширительный']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['рейка рулевая']
 WHERE name = 'Рулевая рейка' AND NOT (synonyms @> ARRAY['рейка рулевая']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['зеркало салона']
 WHERE name = 'Зеркало салонное' AND NOT (synonyms @> ARRAY['зеркало салона']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['селектор акпп']
 WHERE name = 'Кулиса' AND NOT (synonyms @> ARRAY['селектор акпп']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['заслонка дроссельная']
 WHERE name = 'Дроссельная заслонка' AND NOT (synonyms @> ARRAY['заслонка дроссельная']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['барабан тормозной']
 WHERE name = 'Тормозной барабан' AND NOT (synonyms @> ARRAY['барабан тормозной']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['диффузор радиатора']
 WHERE name = 'Диффузор вентилятора' AND NOT (synonyms @> ARRAY['диффузор радиатора']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['консоль центральная']
 WHERE name = 'Центральная консоль' AND NOT (synonyms @> ARRAY['консоль центральная']);
UPDATE catalog.part_kind SET synonyms = synonyms || ARRAY['колпак на колесо']
 WHERE name = 'Колпак колеса' AND NOT (synonyms @> ARRAY['колпак на колесо']);

-- Новые эталоны.
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Подголовник', ARRAY['подголовник'], true FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Фальшпанель', ARRAY['фальшпанель', 'накладка на торпеду'], false FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Коврик салона', ARRAY['коврики комплект', 'коврик салона', 'коврик багажника'], false FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Обшивка багажника', ARRAY['обшивка багажника', 'пол багажника'], false FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Консоль КПП', ARRAY['консоль кпп', 'консоль магнитофона', 'консоль спидометра'], false FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кожух рулевой колонки', ARRAY['кожух рулевой колонки'], false FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка стеклоподъёмника', ARRAY['кнопка стеклоподъемника', 'кнопка стеклоподъёмника', 'накладка на кнопки стеклоподъемника'], true FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок подрулевых переключателей', ARRAY['блок подрулевых переключателей', 'переключатель стеклоочистителей'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Спидометр', ARRAY['спидометр'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Плафон салона', ARRAY['плафон', 'плафон салона'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Реостат печки', ARRAY['реостат печки'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Динамик', ARRAY['динамик'], true FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления климатом', ARRAY['блок управления климат-контролем'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления зеркалами', ARRAY['блок управления зеркалами'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления стеклоподъёмниками', ARRAY['блок управления стеклоподъемниками'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления ABS', ARRAY['блок управления abs'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Лента AIRBAG', ARRAY['лента airbag'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления AIRBAG', ARRAY['блок управления airbag'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Концевик двери', ARRAY['концевик двери'], true FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка аварийной сигнализации', ARRAY['кнопка аварийной сигнализации'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Моторчик омывателя', ARRAY['мотор омывателя', 'моторчик омывателя'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Разъём датчика', ARRAY['разъем датчика', 'разъём датчика'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Реле', ARRAY['реле'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Антенна', ARRAY['антенна'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Защита крыла', ARRAY['защита крыла'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка порога', ARRAY['накладка на порог', 'порожек пластиковый'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка зеркала', ARRAY['накладка на зеркало'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ручка двери багажника', ARRAY['ручка 5-й двери', 'ручка 5-ой двери'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Замок двери багажника', ARRAY['замок 5-й двери', 'замок 5-ой двери'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Петля двери багажника', ARRAY['петля 5-ой двери', 'петля 5-й двери'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка двери багажника', ARRAY['накладка 5-й двери'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка замка багажника', ARRAY['накладка замка багажника'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Петля замка багажника', ARRAY['петля замка багажника'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Уплотнитель двери', ARRAY['уплотнение двери', 'уплотнитель двери'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Уплотнитель багажника', ARRAY['уплотнитель багажника'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ветровик', ARRAY['ветровик', 'ветровики'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Расширитель крыла', ARRAY['расширитель крыла'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Поддомкратник', ARRAY['поддомкратник'], true FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Решётка под дворники', ARRAY['решетка под дворники', 'решётка под дворники'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Воздухозаборник', ARRAY['воздухозаборник'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кронштейн опоры двигателя', ARRAY['кронштейн опоры двигателя'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Защита двигателя', ARRAY['защита двигателя'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крышка двигателя декоративная', ARRAY['крышка двс декоративная'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Корпус воздушного фильтра', ARRAY['корпус воздушного фильтра'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Шкив коленвала', ARRAY['шкив коленвала'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Болт шкива коленвала', ARRAY['болт шкива коленвала'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кожух выпускного коллектора', ARRAY['кожух выпускного коллектора'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клапан VVT', ARRAY['клапан vvt-i', 'клапан vvt'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клапан вакуумный', ARRAY['клапан вакуумный'], false FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клапан EGR', ARRAY['клапан egr'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трубка EGR', ARRAY['трубка egr'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Регулятор давления топлива', ARRAY['регулятор давления топлива'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Горловина топливного бака', ARRAY['горловина топливного бака'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление топливного бака', ARRAY['крепление топливного бака'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трубка топливная', ARRAY['трубка топливная'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Корпус бензонасоса', ARRAY['корпус бензонасоса'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Тросик лючка бака', ARRAY['тросик лючка бака', 'ручка открывания бензобака'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Подушка глушителя', ARRAY['подушка глушителя'], false FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кронштейн компрессора кондиционера', ARRAY['кронштейн компрессора кондиционера'], false FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Механизм стояночного тормоза', ARRAY['механизм стояночного тормоза'], false FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Рабочий тормозной цилиндр', ARRAY['рабочий тормозной цилиндр'], true FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Щиток тормозного диска', ARRAY['щиток тормозного диска'], true FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепёж тормозных колодок', ARRAY['крепеж тормозных колодок'], false FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Провод датчика ABS', ARRAY['провод датчика abs'], true FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Тяга подвески', ARRAY['тяга подвески', 'тяга поперечная'], true FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление балки подвески', ARRAY['крепление балки подвески'], false FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Карданчик рулевой', ARRAY['карданчик рулевой'], false FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трос переключения АКПП', ARRAY['трос переключения акпп'], false FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Подушка редуктора', ARRAY['подушка редуктора'], false FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление запасного колеса', ARRAY['крепление запасного колеса'], false FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Шланг кондиционера', ARRAY['шланг кондиционера'], false FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Шланг гидроусилителя', ARRAY['шланг гидроусилителя'], false FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Бачок гидроусилителя', ARRAY['бачок гидроусилителя'], false FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Бачок омывателя', ARRAY['бачок омывателя'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трубка системы охлаждения', ARRAY['трубка системы охлаждения'], false FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик температуры', ARRAY['датчик температуры'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик давления масла', ARRAY['датчик давления масла'], false FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик положения кузова', ARRAY['датчик положения кузова'], true FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;

--rollback DELETE FROM catalog.part_kind WHERE name IN ('Подголовник', 'Фальшпанель', 'Коврик салона', 'Обшивка багажника', 'Консоль КПП', 'Кожух рулевой колонки', 'Кнопка стеклоподъёмника', 'Блок подрулевых переключателей', 'Спидометр', 'Плафон салона', 'Реостат печки', 'Динамик', 'Блок управления климатом', 'Блок управления зеркалами', 'Блок управления стеклоподъёмниками', 'Блок управления ABS', 'Лента AIRBAG', 'Блок управления AIRBAG', 'Концевик двери', 'Кнопка аварийной сигнализации', 'Моторчик омывателя', 'Разъём датчика', 'Реле', 'Антенна', 'Защита крыла', 'Накладка порога', 'Накладка зеркала', 'Ручка двери багажника', 'Замок двери багажника', 'Петля двери багажника', 'Накладка двери багажника', 'Накладка замка багажника', 'Петля замка багажника', 'Уплотнитель двери', 'Уплотнитель багажника', 'Ветровик', 'Расширитель крыла', 'Поддомкратник', 'Решётка под дворники', 'Воздухозаборник', 'Кронштейн опоры двигателя', 'Защита двигателя', 'Крышка двигателя декоративная', 'Корпус воздушного фильтра', 'Шкив коленвала', 'Болт шкива коленвала', 'Кожух выпускного коллектора', 'Клапан VVT', 'Клапан вакуумный', 'Клапан EGR', 'Трубка EGR', 'Регулятор давления топлива', 'Горловина топливного бака', 'Крепление топливного бака', 'Трубка топливная', 'Корпус бензонасоса', 'Тросик лючка бака', 'Подушка глушителя', 'Кронштейн компрессора кондиционера', 'Механизм стояночного тормоза', 'Рабочий тормозной цилиндр', 'Щиток тормозного диска', 'Крепёж тормозных колодок', 'Провод датчика ABS', 'Тяга подвески', 'Крепление балки подвески', 'Карданчик рулевой', 'Трос переключения АКПП', 'Подушка редуктора', 'Крепление запасного колеса', 'Шланг кондиционера', 'Шланг гидроусилителя', 'Бачок гидроусилителя', 'Бачок омывателя', 'Трубка системы охлаждения', 'Датчик температуры', 'Датчик давления масла', 'Датчик положения кузова');
