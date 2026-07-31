--liquibase formatted sql

--changeset partsflow:catalog-013-part-kinds-live2 runOnChange:false
--comment Вторая порция справочника по написаниям живого склада

-- Составлено разбором нераспознанных наименований арендатора «YARD Ткацкая»:
-- 255 написаний с десятью и более карточками, под ними 9 100 из 11 182
-- карточек без вида детали. Первая порция (012) закрыла верхушку списка,
-- эта — его основную массу.
--
-- Эталоны и синонимы разделены намеренно: синоним под своим эталоном
-- раздваивает справочник, и следующий клиент, пишущий в третьем порядке
-- слов, получит третий эталон вместо сопоставления.
--
-- Часть написаний не взята намеренно — см. build_part_kinds_live2.py,
-- список SKIPPED: по ним деталь не определяется, и эталон отправил бы её
-- в чужую категорию.

INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление бампера', ARRAY['крепление бампера', 'кронштейн бампера', 'направляющая бампера', 'клык бампера'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Абсорбер бампера', ARRAY['абсорбер бампера', 'наполнитель бампера', 'пенопласт бампера'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Заглушка бампера', ARRAY['заглушка бампера', 'заглушка буксировочного крюка'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Петля капота', ARRAY['петля капота'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Замок капота', ARRAY['замок капота'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка замка капота', ARRAY['накладка замка капота', 'планка замка капота', 'защита замка капота'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Упор капота', ARRAY['держатель капота', 'упор капота', 'штырь капота'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Амортизатор капота', ARRAY['амортизатор капота', 'газовый упор капота'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трос капота', ARRAY['трос капота', 'тросик капота'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Утеплитель капота', ARRAY['утеплитель капота', 'шумоизоляция капота'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Уголок двери', ARRAY['уголок двери', 'треугольник двери', 'накладка уголка двери'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Личинка замка', ARRAY['личинка замка', 'личинка замка двери', 'личинка замка багажника', 'личинка замка 5-ой двери', 'личинка замка 5-й двери', 'цилиндр замка'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Петля замка двери', ARRAY['петля замка двери', 'скоба замка двери', 'ответная часть замка'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Замок лючка бензобака', ARRAY['замок лючка бензобака', 'замок лючка бака', 'привод лючка бензобака'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трос замка', ARRAY['трос замка', 'трос замка двери', 'трос замка багажника', 'тросик багажника', 'тросик 5-й двери'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ролик раздвижной двери', ARRAY['ролик раздвижной двери', 'ролик сдвижной двери', 'направляющая сдвижной двери'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Рамка номера', ARRAY['рамка для номера', 'рамка номера', 'площадка номера'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Рейлинг', ARRAY['рейлинг', 'рейлинги', 'рейлинг крыши'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Заглушка рейлинга', ARRAY['заглушка рейлинга'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Защита днища', ARRAY['защита днища кузова', 'защита днища', 'пыльник днища'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Распорка кузова', ARRAY['распорка', 'распорка кузова'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Планка под фару', ARRAY['планка под фару', 'планка под фары', 'планка под фонарь', 'накладка под фару'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка противотуманной фары', ARRAY['накладка противотуманной фары', 'накладка птф', 'окантовка птф', 'рамка птф'], true
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Защита радиатора', ARRAY['защита радиатора', 'пыльник радиатора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клапан вентиляции салона', ARRAY['клапан вентиляции крыла', 'клапан вентиляции салона'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kuzov'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление фары', ARRAY['крепление фары', 'кронштейн фары', 'ухо фары'], true
  FROM catalog.part_category c
 WHERE c.slug = 'optika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление противотуманной фары', ARRAY['крепление туманки', 'крепление птф', 'кронштейн птф'], true
  FROM catalog.part_category c
 WHERE c.slug = 'optika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Патрубок воздушного фильтра', ARRAY['гофра воздушного фильтра', 'патрубок воздушного фильтра', 'патрубок воздухозаборника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Резонатор воздушного фильтра', ARRAY['резонатор воздушного фильтра', 'резонатор воздухозаборника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Патрубок картерных газов', ARRAY['патрубок картерных газов', 'трубка картерных газов', 'шланг картерных газов'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трубка вакуумная', ARRAY['трубка вакуумная', 'шланг вакуумный'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трубка масляная', ARRAY['трубка масляная', 'маслопровод'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Щуп масляный', ARRAY['щуп масляный', 'масляный щуп'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Сапун', ARRAY['сапун', 'маслоотделитель'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Прокладка ГБЦ', ARRAY['прокладка гбц', 'прокладка головки блока'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Шкив помпы', ARRAY['шкив помпы'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ролик натяжной', ARRAY['ролик натяжной', 'натяжной ролик'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ремень приводной', ARRAY['ремень поликлиновой', 'ремень приводной', 'ремень генератора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кронштейн генератора', ARRAY['кронштейн генератора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кронштейн впускного коллектора', ARRAY['кронштейн впускного коллектора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клапан VTEC', ARRAY['клапан vtec', 'клапан втек'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клапан холостого хода', ARRAY['клапан холостого хода', 'регулятор холостого хода', 'рхх'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Свеча зажигания', ARRAY['свеча зажигания'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Тросик газа', ARRAY['тросик газа', 'трос газа', 'трос акселератора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'dvigatel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Опора АКПП', ARRAY['кронштейн акпп', 'опора акпп', 'подушка акпп'], false
  FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кожух маховика', ARRAY['кожух маховика', 'крышка маховика'], false
  FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Заглушка маховика', ARRAY['заглушка маховика'], false
  FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Болт маховика', ARRAY['болт маховика'], false
  FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Болт гидротрансформатора', ARRAY['болт гидротрансформатора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Пыльник ШРУСа', ARRAY['пыльник шруса', 'пыльник шрус'], false
  FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трубка охлаждения АКПП', ARRAY['трубка охлаждения акпп', 'патрубок охлаждения акпп'], false
  FROM catalog.part_category c
 WHERE c.slug = 'transmissiya'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление стабилизатора', ARRAY['крепление стабилизатора', 'скоба стабилизатора', 'хомут стабилизатора'], true
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Опора стойки амортизатора', ARRAY['опора стойки', 'опора стойки амортизатора', 'опора амортизатора'], true
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Проставка пружины', ARRAY['проставка под пружину', 'проставка пружины'], true
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Болт подвески', ARRAY['болт подвески', 'болт эксцентрик', 'эксцентрик развала'], false
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Гайка ступичная', ARRAY['гайка ступичная', 'гайка ступицы'], false
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Балка продольная', ARRAY['балка продольная под двс', 'балка продольная'], true
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кронштейн насоса ГУР', ARRAY['кронштейн насоса гидроусилителя', 'кронштейн гидроусилителя руля', 'кронштейн насоса гур'], false
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Радиатор ГУР', ARRAY['радиатор гидроусилителя', 'радиатор гур'], false
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Трубка ГУР', ARRAY['трубка гидроусилителя', 'трубка гур'], false
  FROM catalog.part_category c
 WHERE c.slug = 'podveska'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ремкомплект суппорта', ARRAY['ремкомплект суппорта', 'ремкомплект тормозного суппорта'], false
  FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Поршень суппорта', ARRAY['поршень суппорта'], false
  FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Колодки стояночного тормоза', ARRAY['колодки стояночного тормоза', 'колодки ручного тормоза', 'колодки ручника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'tormoza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Моторчик заслонок печки', ARRAY['моторчик привода заслонок печки', 'моторчик заслонки печки', 'привод заслонки печки', 'сервопривод заслонки печки'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Активатор замка', ARRAY['активатор замка', 'активатор замка двери', 'активатор замка багажника', 'активатор замка 5-ой двери', 'моторчик замка'], true
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крышка подушки безопасности', ARRAY['крышка подушки безопасности', 'накладка подушки безопасности'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик AIRBAG', ARRAY['датчик airbag', 'датчик подушки безопасности', 'датчик удара'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик абсолютного давления', ARRAY['датчик абсолютного давления', 'датчик map', 'мап сенсор'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик вакуумный', ARRAY['датчик вакуумный', 'вакуумный датчик'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик аккумулятора', ARRAY['датчик аккумулятора', 'датчик тока аккумулятора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Датчик положения руля', ARRAY['датчик положения руля', 'датчик угла поворота руля'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Разъём', ARRAY['разъем', 'разъём', 'разъем генератора', 'разъем форсунки', 'разъем селектора акпп', 'разъем дроссельной заслонки', 'разъем вакуумного клапана', 'разъем клапана egr'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Провода высоковольтные', ARRAY['провод высокого напряжения', 'высоковольтные провода', 'провода зажигания'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Регулятор напряжения', ARRAY['регулятор генератора', 'регулятор напряжения', 'реле-регулятор'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Клемма аккумулятора', ARRAY['клемма аккумулятора', 'клемма акб'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление аккумулятора', ARRAY['крепление аккумулятора', 'подставка под аккумулятор', 'площадка аккумулятора', 'прижим аккумулятора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Форсунка омывателя', ARRAY['форсунка омывателя', 'жиклёр омывателя', 'жиклер омывателя'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ключ зажигания', ARRAY['ключ зажигания', 'ключ от замка зажигания'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка', ARRAY['кнопка', 'кнопка курсовой устойчивости', 'кнопка включения полного привода', 'кнопка режимов акпп', 'кнопка освещения панели приборов'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка открывания багажника', ARRAY['кнопка открывания багажника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка обогрева заднего стекла', ARRAY['кнопка обогрева заднего стекла'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка подогрева сидений', ARRAY['кнопка подогрева сидений', 'кнопка обогрева сидений'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка корректора фар', ARRAY['кнопка корректора фар', 'регулятор корректора фар'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кнопка старт-стоп', ARRAY['кнопка старт-стоп', 'кнопка запуска двигателя'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления дверьми', ARRAY['блок управления дверьми'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления АКПП', ARRAY['блок управления акпп'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Блок управления рулевой рейкой', ARRAY['блок управления рулевой рейкой'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление магнитолы', ARRAY['крепление магнитофона', 'крепление магнитолы', 'салазки магнитолы'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Монитор', ARRAY['монитор', 'дисплей', 'экран мультимедиа'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Камера переднего вида', ARRAY['камера переднего вида'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Антенна иммобилайзера', ARRAY['антенна иммобилайзера', 'кольцо иммобилайзера'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Карта навигации', ARRAY['загрузочная sd карта', 'sd карта навигации', 'карта навигации'], false
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Контактная группа сдвижной двери', ARRAY['контактная группа сдвижной двери'], true
  FROM catalog.part_category c
 WHERE c.slug = 'elektrika'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Обшивка двери багажника', ARRAY['обшивка двери багажника', 'обшивка крышки багажника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка стойки кузова', ARRAY['накладка на стойку кузова', 'накладка стойки', 'обшивка стойки'], true
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Накладка панели приборов', ARRAY['накладка на торпедо', 'накладка панели приборов', 'накладка торпедо'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Полка багажника', ARRAY['полка багажника', 'задняя полка'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Шторка багажника', ARRAY['шторка багажника', 'ролета багажника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крючок багажника', ARRAY['крючок багажника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Органайзер багажника', ARRAY['ящик в багажник', 'органайзер багажника', 'ящик багажника'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Пепельница', ARRAY['пепельница'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Часы', ARRAY['часы', 'часы салонные'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Подстаканник', ARRAY['подстаканник'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Педаль газа', ARRAY['педаль газа', 'педаль акселератора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ручка потолочная', ARRAY['ручка в салоне', 'ручка потолочная', 'поручень салона'], true
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Ручка открывания капота', ARRAY['ручка открывания капота', 'рычаг открывания капота'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление солнцезащитного козырька', ARRAY['крепление солнцезащитного козырька', 'кронштейн козырька'], true
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Заглушка крепления сиденья', ARRAY['заглушка крепления сиденья', 'накладка салазок сиденья'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Заглушка кнопки', ARRAY['заглушка кнопки'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Пыльник рулевой колонки', ARRAY['пыльник рулевой колонки'], false
  FROM catalog.part_category c
 WHERE c.slug = 'salon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Стекло кузова', ARRAY['стекло глухое', 'глухое стекло', 'стекло боковины'], true
  FROM catalog.part_category c
 WHERE c.slug = 'stekla'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крышка термостата', ARRAY['крышка термостата'], false
  FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Корпус термостата', ARRAY['корпус термостата'], false
  FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Крепление радиатора', ARRAY['крепление радиатора', 'кронштейн радиатора', 'подушка радиатора'], false
  FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Фланец системы охлаждения', ARRAY['фланец системы охлаждения', 'тройник системы охлаждения'], false
  FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Кран печки', ARRAY['кран печки', 'кран отопителя'], false
  FROM catalog.part_category c
 WHERE c.slug = 'ohlazhdenie'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Защита горловины топливного бака', ARRAY['защита горловины бензобака', 'защита горловины топливного бака'], false
  FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Защита топливного бака', ARRAY['защита топливного бака', 'защита бензобака'], false
  FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Тепловой экран', ARRAY['тепловой экран', 'теплозащитный экран', 'экран глушителя'], false
  FROM catalog.part_category c
 WHERE c.slug = 'vypusk'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, 'Гайка колёсная', ARRAY['гайка колесная', 'гайка колёсная', 'гайка колеса'], false
  FROM catalog.part_category c
 WHERE c.slug = 'kolesa'
ON CONFLICT DO NOTHING;

-- Синонимы к существующим эталонам: только те, которых там ещё нет.
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['радиатор охлаждения двигателя']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Радиатор охлаждения';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['рычаг переключения кпп', 'рычаг переключения акпп', 'рычаг переключения передач акпп', 'ручка кпп']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Кулиса';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['патрубок радиатора нижний', 'патрубок радиатора верхний', 'патрубок системы охлаждения', 'патрубок радиатора', 'патрубок печки', 'шланг расширительного бачка']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Патрубок';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['руль с подушкой безопасности']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Руль';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['молдинг двери', 'молдинг на крыло', 'молдинг крыши', 'молдинг стекла', 'молдинг лобового стекла', 'накладка на дверь', 'накладка на крыло']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Молдинг';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['накладка на бампер']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Накладка бампера';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['накладки на порог']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Накладка порога';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['порог кузова']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Порог';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['крыло заднее']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Крыло';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['уголок жабо']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Жабо';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['катафот в бампер']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Катафот бампера';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['планка телевизора']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Рамка радиатора';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['амортизатор задней двери']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Амортизатор багажника';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['замок крышки багажника']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Замок двери багажника';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['петля крышки багажника']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Петля двери багажника';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['ручка открывания багажника', 'ручка задней двери']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Ручка двери багажника';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['накладка крышки багажника']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Накладка двери багажника';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['бак топливный']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Топливный бак';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['патрубок горловины топливного бака']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Горловина топливного бака';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['шланг топливный']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Трубка топливная';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['фильтр паров топлива']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Адсорбер';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['подушка безопасности пассажира']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Подушка безопасности';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['переключатель поворотников и света', 'переключатель подрулевой']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Блок подрулевых переключателей';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['блок предохранителей под капот']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Блок предохранителей';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['коса двс']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Жгут проводов';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['датчик положения коленвала']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Датчик коленвала';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['датчик температуры охлаждающей жидкости', 'датчик температуры воздуха', 'датчик температуры выхлопных газов']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Датчик температуры';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['расходомер воздушный']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Датчик массового расхода воздуха';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['реостат']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Реостат печки';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['сигнал звуковой']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Звуковой сигнал';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['магнитофон']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Магнитола';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['динамик высокочастотный']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Динамик';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['блок управления паркторниками']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Парктроник';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['кронштейн двигателя']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Кронштейн опоры двигателя';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['натяжитель ремня генератора', 'натяжитель приводного ремня', 'натяжитель ремня гидроусилителя']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Натяжитель ремня';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['рычаг нижний', 'рычаг верхний']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Рычаг';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['тяга продольная']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Тяга подвески';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['бардачок пассажирский']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Перчаточный ящик';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['консоль панели приборов', 'консоль между сидений', 'накладка центральной консоли']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Центральная консоль';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['брызговики комплект']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Брызговик';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['колпак на диск', 'колпачок на диски']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Колпак колеса';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['конденсатор']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Радиатор кондиционера';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['диффузор радиатора кондиционера']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Диффузор вентилятора';
UPDATE catalog.part_kind
   SET synonyms = synonyms || (SELECT coalesce(array_agg(s), ARRAY[]::text[])
                                 FROM unnest(ARRAY['повторитель поворота в бампер']) AS s
                                WHERE s <> ALL (synonyms))
 WHERE name = 'Повторитель поворота';

--rollback DELETE FROM catalog.part_kind WHERE name IN ('Крепление бампера', 'Абсорбер бампера', 'Заглушка бампера', 'Петля капота', 'Замок капота', 'Накладка замка капота', 'Упор капота', 'Амортизатор капота', 'Трос капота', 'Утеплитель капота', 'Уголок двери', 'Личинка замка', 'Петля замка двери', 'Замок лючка бензобака', 'Трос замка', 'Ролик раздвижной двери', 'Рамка номера', 'Рейлинг', 'Заглушка рейлинга', 'Защита днища', 'Распорка кузова', 'Планка под фару', 'Накладка противотуманной фары', 'Защита радиатора', 'Клапан вентиляции салона', 'Крепление фары', 'Крепление противотуманной фары', 'Патрубок воздушного фильтра', 'Резонатор воздушного фильтра', 'Патрубок картерных газов', 'Трубка вакуумная', 'Трубка масляная', 'Щуп масляный', 'Сапун', 'Прокладка ГБЦ', 'Шкив помпы', 'Ролик натяжной', 'Ремень приводной', 'Кронштейн генератора', 'Кронштейн впускного коллектора', 'Клапан VTEC', 'Клапан холостого хода', 'Свеча зажигания', 'Тросик газа', 'Опора АКПП', 'Кожух маховика', 'Заглушка маховика', 'Болт маховика', 'Болт гидротрансформатора', 'Пыльник ШРУСа', 'Трубка охлаждения АКПП', 'Крепление стабилизатора', 'Опора стойки амортизатора', 'Проставка пружины', 'Болт подвески', 'Гайка ступичная', 'Балка продольная', 'Кронштейн насоса ГУР', 'Радиатор ГУР', 'Трубка ГУР', 'Ремкомплект суппорта', 'Поршень суппорта', 'Колодки стояночного тормоза', 'Моторчик заслонок печки', 'Активатор замка', 'Крышка подушки безопасности', 'Датчик AIRBAG', 'Датчик абсолютного давления', 'Датчик вакуумный', 'Датчик аккумулятора', 'Датчик положения руля', 'Разъём', 'Провода высоковольтные', 'Регулятор напряжения', 'Клемма аккумулятора', 'Крепление аккумулятора', 'Форсунка омывателя', 'Ключ зажигания', 'Кнопка', 'Кнопка открывания багажника', 'Кнопка обогрева заднего стекла', 'Кнопка подогрева сидений', 'Кнопка корректора фар', 'Кнопка старт-стоп', 'Блок управления дверьми', 'Блок управления АКПП', 'Блок управления рулевой рейкой', 'Крепление магнитолы', 'Монитор', 'Камера переднего вида', 'Антенна иммобилайзера', 'Карта навигации', 'Контактная группа сдвижной двери', 'Обшивка двери багажника', 'Накладка стойки кузова', 'Накладка панели приборов', 'Полка багажника', 'Шторка багажника', 'Крючок багажника', 'Органайзер багажника', 'Пепельница', 'Часы', 'Подстаканник', 'Педаль газа', 'Ручка потолочная', 'Ручка открывания капота', 'Крепление солнцезащитного козырька', 'Заглушка крепления сиденья', 'Заглушка кнопки', 'Пыльник рулевой колонки', 'Стекло кузова', 'Крышка термостата', 'Корпус термостата', 'Крепление радиатора', 'Фланец системы охлаждения', 'Кран печки', 'Защита горловины топливного бака', 'Защита топливного бака', 'Тепловой экран', 'Гайка колёсная');
