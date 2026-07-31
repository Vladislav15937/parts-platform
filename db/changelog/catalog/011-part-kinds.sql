--liquibase formatted sql

--changeset platform:catalog-040-seed-part-categories runOnChange:true
--comment Категории деталей (12 шт).
--comment
--comment Категория приходит детали от эталона наименования, а не от строки,
--comment которую набрал приёмщик. Пока справочник был пуст, всякая принятая
--comment деталь оставалась без категории — это и вскрыл сквозной прогон.
--comment
--comment Дерево одноуровневое намеренно. Глубокая таксономия у площадок своя
--comment и у каждой разная, а наша задача здесь одна: свести написания
--comment к эталону. Разложить эталоны по чужому дереву — работа маппинга
--comment площадки, и она отдельная.

INSERT INTO catalog.part_category (name, slug, path, sort_order)
VALUES ('Кузов', 'kuzov', 'kuzov'::ltree, 0),
       ('Оптика', 'optika', 'optika'::ltree, 10),
       ('Двигатель', 'dvigatel', 'dvigatel'::ltree, 20),
       ('Трансмиссия', 'transmissiya', 'transmissiya'::ltree, 30),
       ('Подвеска и рулевое', 'podveska', 'podveska'::ltree, 40),
       ('Тормозная система', 'tormoza', 'tormoza'::ltree, 50),
       ('Электрика', 'elektrika', 'elektrika'::ltree, 60),
       ('Салон', 'salon', 'salon'::ltree, 70),
       ('Стёкла и зеркала', 'stekla', 'stekla'::ltree, 80),
       ('Охлаждение и отопление', 'ohlazhdenie', 'ohlazhdenie'::ltree, 90),
       ('Выпуск и топливо', 'vypusk', 'vypusk'::ltree, 100),
       ('Колёса', 'kolesa', 'kolesa'::ltree, 110)
ON CONFLICT (path) DO UPDATE SET name = excluded.name, sort_order = excluded.sort_order;

--rollback DELETE FROM catalog.part_category;

--changeset platform:catalog-041-seed-part-kinds runOnChange:true
--comment Эталонные наименования деталей (178 шт) с синонимами.
--comment
--comment Синонимы — это то, как деталь называют на складе: «запаска»,
--comment «фара лев.», «мозги», «граната». Сопоставление точное, поэтому
--comment написание в синониме должно совпадать с набранным до символа
--comment после lower/btrim. Похожесть в автосопоставление не идёт: «Кронштейн
--comment топливного фильтра» → «Фильтр топливный» выглядит правдоподобно
--comment и уводит деталь в чужую категорию.
--comment
--comment has_side отмечает то, что бывает левым и правым или передним
--comment и задним: по нему экран приёмки решает, спрашивать ли сторону.

INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, v.name, v.synonyms, v.has_side
  FROM (VALUES ('kuzov', 'Бампер', ARRAY['бампер', 'буфер']::text[], true),
       ('kuzov', 'Капот', ARRAY['капот']::text[], false),
       ('kuzov', 'Крышка багажника', ARRAY['крышка багажника', 'багажник', 'дверь багажника', 'пятая дверь']::text[], false),
       ('kuzov', 'Дверь', ARRAY['дверь', 'дверка']::text[], true),
       ('kuzov', 'Крыло', ARRAY['крыло']::text[], true),
       ('kuzov', 'Порог', ARRAY['порог']::text[], true),
       ('kuzov', 'Лонжерон', ARRAY['лонжерон']::text[], true),
       ('kuzov', 'Стойка кузова', ARRAY['стойка кузова', 'стойка']::text[], true),
       ('kuzov', 'Задняя панель', ARRAY['задняя панель', 'панель задняя']::text[], false),
       ('kuzov', 'Рамка радиатора', ARRAY['рамка радиатора', 'телевизор', 'панель передняя']::text[], false),
       ('kuzov', 'Усилитель бампера', ARRAY['усилитель бампера', 'усилитель']::text[], false),
       ('kuzov', 'Решётка радиатора', ARRAY['решетка радиатора', 'решётка радиатора', 'решетка']::text[], false),
       ('kuzov', 'Накладка бампера', ARRAY['накладка бампера', 'губа', 'юбка бампера']::text[], false),
       ('kuzov', 'Подкрылок', ARRAY['подкрылок', 'локер']::text[], true),
       ('kuzov', 'Брызговик', ARRAY['брызговик']::text[], true),
       ('kuzov', 'Спойлер', ARRAY['спойлер', 'антикрыло']::text[], false),
       ('kuzov', 'Молдинг', ARRAY['молдинг', 'накладка']::text[], true),
       ('kuzov', 'Крыша', ARRAY['крыша']::text[], false),
       ('kuzov', 'Задняя четверть', ARRAY['задняя четверть', 'четверть', 'боковина']::text[], true),
       ('kuzov', 'Петля двери', ARRAY['петля двери', 'петля']::text[], true),
       ('kuzov', 'Замок двери', ARRAY['замок двери', 'замок']::text[], true),
       ('kuzov', 'Ручка двери наружная', ARRAY['ручка двери наружная', 'ручка наружная', 'ручка двери']::text[], true),
       ('kuzov', 'Ручка двери внутренняя', ARRAY['ручка двери внутренняя', 'ручка внутренняя']::text[], true),
       ('kuzov', 'Ограничитель двери', ARRAY['ограничитель двери', 'ограничитель']::text[], true),
       ('kuzov', 'Амортизатор багажника', ARRAY['амортизатор багажника', 'упор багажника', 'газовый упор']::text[], true),
       ('kuzov', 'Эмблема', ARRAY['эмблема', 'значок', 'шильдик']::text[], false),
       ('kuzov', 'Жабо', ARRAY['жабо', 'накладка лобового стекла']::text[], false),
       ('optika', 'Фара', ARRAY['фара', 'блок-фара', 'фара головная']::text[], true),
       ('optika', 'Фонарь задний', ARRAY['фонарь задний', 'фонарь', 'задний фонарь', 'стоп']::text[], true),
       ('optika', 'Противотуманная фара', ARRAY['противотуманная фара', 'птф', 'туманка']::text[], true),
       ('optika', 'Повторитель поворота', ARRAY['повторитель поворота', 'повторитель', 'поворотник']::text[], true),
       ('optika', 'Фонарь подсветки номера', ARRAY['фонарь подсветки номера', 'подсветка номера']::text[], false),
       ('optika', 'Блок розжига', ARRAY['блок розжига', 'розжиг', 'балласт ксенона']::text[], true),
       ('optika', 'Корректор фары', ARRAY['корректор фары', 'корректор']::text[], true),
       ('optika', 'Дополнительный стоп-сигнал', ARRAY['дополнительный стоп-сигнал', 'стоп-сигнал', 'третий стоп']::text[], false),
       ('optika', 'Катафот бампера', ARRAY['катафот', 'отражатель бампера', 'катафот бампера']::text[], true),
       ('dvigatel', 'Двигатель', ARRAY['двигатель', 'двс', 'мотор', 'двигатель в сборе']::text[], false),
       ('dvigatel', 'Головка блока цилиндров', ARRAY['головка блока цилиндров', 'гбц', 'головка блока']::text[], false),
       ('dvigatel', 'Блок цилиндров', ARRAY['блок цилиндров', 'блок']::text[], false),
       ('dvigatel', 'Поддон двигателя', ARRAY['поддон двигателя', 'поддон']::text[], false),
       ('dvigatel', 'Коленчатый вал', ARRAY['коленчатый вал', 'коленвал']::text[], false),
       ('dvigatel', 'Распределительный вал', ARRAY['распределительный вал', 'распредвал']::text[], false),
       ('dvigatel', 'Поршень', ARRAY['поршень']::text[], false),
       ('dvigatel', 'Шатун', ARRAY['шатун']::text[], false),
       ('dvigatel', 'Маховик', ARRAY['маховик']::text[], false),
       ('dvigatel', 'Клапанная крышка', ARRAY['клапанная крышка', 'крышка клапанная', 'крышка головки']::text[], false),
       ('dvigatel', 'Впускной коллектор', ARRAY['впускной коллектор', 'коллектор впускной', 'впуск']::text[], false),
       ('dvigatel', 'Выпускной коллектор', ARRAY['выпускной коллектор', 'коллектор выпускной', 'выпуск']::text[], false),
       ('dvigatel', 'Турбина', ARRAY['турбина', 'турбокомпрессор']::text[], false),
       ('dvigatel', 'Компрессор кондиционера', ARRAY['компрессор кондиционера', 'компрессор кондея', 'компрессор']::text[], false),
       ('dvigatel', 'Генератор', ARRAY['генератор', 'гена']::text[], false),
       ('dvigatel', 'Стартер', ARRAY['стартер']::text[], false),
       ('dvigatel', 'Топливный насос высокого давления', ARRAY['тнвд', 'топливный насос высокого давления']::text[], false),
       ('dvigatel', 'Форсунка', ARRAY['форсунка', 'инжектор']::text[], false),
       ('dvigatel', 'Топливная рампа', ARRAY['топливная рампа', 'рампа']::text[], false),
       ('dvigatel', 'Дроссельная заслонка', ARRAY['дроссельная заслонка', 'дроссель']::text[], false),
       ('dvigatel', 'Помпа', ARRAY['помпа', 'водяной насос', 'насос охлаждающей жидкости']::text[], false),
       ('dvigatel', 'Натяжитель ремня', ARRAY['натяжитель ремня', 'натяжитель']::text[], false),
       ('dvigatel', 'Обводной ролик', ARRAY['обводной ролик', 'ролик']::text[], false),
       ('dvigatel', 'Опора двигателя', ARRAY['опора двигателя', 'подушка двигателя', 'подушка двс']::text[], true)) AS v(category_slug, name, synonyms, has_side)
  JOIN catalog.part_category c ON c.slug = v.category_slug
 WHERE NOT EXISTS (
       SELECT 1 FROM catalog.part_kind k
        WHERE lower(btrim(k.name)) = lower(btrim(v.name)));

INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, v.name, v.synonyms, v.has_side
  FROM (VALUES ('dvigatel', 'Масляный насос', ARRAY['масляный насос', 'насос масляный']::text[], false),
       ('dvigatel', 'Катушка зажигания', ARRAY['катушка зажигания', 'катушка']::text[], false),
       ('dvigatel', 'Масляный фильтр', ARRAY['масляный фильтр', 'фильтр масляный']::text[], false),
       ('transmissiya', 'Автоматическая коробка передач', ARRAY['акпп', 'автомат', 'коробка автомат', 'автоматическая коробка']::text[], false),
       ('transmissiya', 'Механическая коробка передач', ARRAY['мкпп', 'механика', 'коробка механика', 'механическая коробка']::text[], false),
       ('transmissiya', 'Вариатор', ARRAY['вариатор', 'cvt']::text[], false),
       ('transmissiya', 'Раздаточная коробка', ARRAY['раздаточная коробка', 'раздатка']::text[], false),
       ('transmissiya', 'Редуктор задний', ARRAY['редуктор задний', 'задний редуктор']::text[], false),
       ('transmissiya', 'Редуктор передний', ARRAY['редуктор передний', 'передний редуктор']::text[], false),
       ('transmissiya', 'Карданный вал', ARRAY['карданный вал', 'кардан']::text[], false),
       ('transmissiya', 'Привод', ARRAY['привод', 'полуось', 'приводной вал']::text[], true),
       ('transmissiya', 'Сцепление', ARRAY['сцепление']::text[], false),
       ('transmissiya', 'Корзина сцепления', ARRAY['корзина сцепления', 'корзина']::text[], false),
       ('transmissiya', 'Диск сцепления', ARRAY['диск сцепления']::text[], false),
       ('transmissiya', 'Выжимной подшипник', ARRAY['выжимной подшипник', 'выжимной']::text[], false),
       ('transmissiya', 'ШРУС наружный', ARRAY['шрус наружный', 'граната наружная']::text[], true),
       ('transmissiya', 'ШРУС внутренний', ARRAY['шрус внутренний', 'граната внутренняя']::text[], true),
       ('transmissiya', 'Гидротрансформатор', ARRAY['гидротрансформатор', 'бублик']::text[], false),
       ('transmissiya', 'Кулиса', ARRAY['кулиса', 'селектор', 'рычаг кпп']::text[], false),
       ('podveska', 'Амортизатор', ARRAY['амортизатор', 'аморт']::text[], true),
       ('podveska', 'Стойка амортизатора', ARRAY['стойка амортизатора', 'стойка аморт']::text[], true),
       ('podveska', 'Пружина', ARRAY['пружина']::text[], true),
       ('podveska', 'Рычаг', ARRAY['рычаг', 'рычаг подвески']::text[], true),
       ('podveska', 'Шаровая опора', ARRAY['шаровая опора', 'шаровая']::text[], true),
       ('podveska', 'Ступица', ARRAY['ступица']::text[], true),
       ('podveska', 'Поворотный кулак', ARRAY['поворотный кулак', 'кулак', 'цапфа']::text[], true),
       ('podveska', 'Задняя балка', ARRAY['задняя балка', 'балка']::text[], false),
       ('podveska', 'Подрамник', ARRAY['подрамник']::text[], false),
       ('podveska', 'Стабилизатор', ARRAY['стабилизатор']::text[], false),
       ('podveska', 'Стойка стабилизатора', ARRAY['стойка стабилизатора', 'линк', 'косточка']::text[], true),
       ('podveska', 'Сайлентблок', ARRAY['сайлентблок', 'сайлент']::text[], true),
       ('podveska', 'Рулевая рейка', ARRAY['рулевая рейка', 'рейка']::text[], false),
       ('podveska', 'Рулевая тяга', ARRAY['рулевая тяга', 'тяга рулевая']::text[], true),
       ('podveska', 'Рулевой наконечник', ARRAY['рулевой наконечник', 'наконечник рулевой', 'наконечник']::text[], true),
       ('podveska', 'Насос ГУР', ARRAY['насос гур', 'гур']::text[], false),
       ('podveska', 'Рулевая колонка', ARRAY['рулевая колонка', 'колонка']::text[], false),
       ('podveska', 'Руль', ARRAY['руль', 'рулевое колесо']::text[], false),
       ('tormoza', 'Суппорт', ARRAY['суппорт']::text[], true),
       ('tormoza', 'Тормозной диск', ARRAY['тормозной диск', 'диск тормозной']::text[], true),
       ('tormoza', 'Тормозной барабан', ARRAY['тормозной барабан', 'барабан']::text[], true),
       ('tormoza', 'Тормозные колодки', ARRAY['тормозные колодки', 'колодки']::text[], false),
       ('tormoza', 'Главный тормозной цилиндр', ARRAY['главный тормозной цилиндр', 'гтц']::text[], false),
       ('tormoza', 'Вакуумный усилитель', ARRAY['вакуумный усилитель', 'вакуумник']::text[], false),
       ('tormoza', 'Блок ABS', ARRAY['блок abs', 'абс', 'блок абс']::text[], false),
       ('tormoza', 'Трос ручника', ARRAY['трос ручника', 'трос ручного тормоза']::text[], true),
       ('tormoza', 'Тормозной шланг', ARRAY['тормозной шланг', 'шланг тормозной']::text[], true),
       ('elektrika', 'Блок управления двигателем', ARRAY['блок управления двигателем', 'эбу', 'мозги']::text[], false),
       ('elektrika', 'Блок предохранителей', ARRAY['блок предохранителей', 'монтажный блок']::text[], false),
       ('elektrika', 'Блок комфорта', ARRAY['блок комфорта', 'бкс']::text[], false),
       ('elektrika', 'Жгут проводов', ARRAY['жгут проводов', 'проводка', 'жгут', 'коса']::text[], false),
       ('elektrika', 'Аккумулятор', ARRAY['аккумулятор', 'акб']::text[], false),
       ('elektrika', 'Датчик ABS', ARRAY['датчик abs', 'датчик абс']::text[], true),
       ('elektrika', 'Датчик кислорода', ARRAY['датчик кислорода', 'лямбда', 'лямбда-зонд']::text[], false),
       ('elektrika', 'Датчик коленвала', ARRAY['датчик коленвала', 'дпкв']::text[], false),
       ('elektrika', 'Датчик распредвала', ARRAY['датчик распредвала', 'дпрв']::text[], false),
       ('elektrika', 'Датчик массового расхода воздуха', ARRAY['дмрв', 'датчик массового расхода воздуха', 'расходомер']::text[], false),
       ('elektrika', 'Датчик детонации', ARRAY['датчик детонации']::text[], false),
       ('elektrika', 'Подушка безопасности', ARRAY['подушка безопасности', 'airbag', 'аирбег']::text[], true),
       ('elektrika', 'Ремень безопасности', ARRAY['ремень безопасности', 'ремень']::text[], true),
       ('elektrika', 'Блок SRS', ARRAY['блок srs', 'блок подушек', 'блок airbag']::text[], false)) AS v(category_slug, name, synonyms, has_side)
  JOIN catalog.part_category c ON c.slug = v.category_slug
 WHERE NOT EXISTS (
       SELECT 1 FROM catalog.part_kind k
        WHERE lower(btrim(k.name)) = lower(btrim(v.name)));

INSERT INTO catalog.part_kind (category_id, name, synonyms, has_side)
SELECT c.id, v.name, v.synonyms, v.has_side
  FROM (VALUES ('elektrika', 'Моторчик стеклоподъёмника', ARRAY['моторчик стеклоподъемника', 'моторчик стеклоподъёмника']::text[], true),
       ('elektrika', 'Стеклоподъёмник', ARRAY['стеклоподъемник', 'стеклоподъёмник']::text[], true),
       ('elektrika', 'Моторчик печки', ARRAY['моторчик печки', 'вентилятор печки', 'моторчик отопителя']::text[], false),
       ('elektrika', 'Моторчик стеклоочистителя', ARRAY['моторчик стеклоочистителя', 'моторчик дворников']::text[], false),
       ('elektrika', 'Звуковой сигнал', ARRAY['звуковой сигнал', 'сигнал', 'клаксон']::text[], false),
       ('elektrika', 'Замок зажигания', ARRAY['замок зажигания']::text[], false),
       ('elektrika', 'Магнитола', ARRAY['магнитола', 'головное устройство', 'автомагнитола']::text[], false),
       ('elektrika', 'Камера заднего вида', ARRAY['камера заднего вида', 'камера']::text[], false),
       ('elektrika', 'Парктроник', ARRAY['парктроник', 'датчик парковки']::text[], false),
       ('salon', 'Сиденье переднее', ARRAY['сиденье переднее', 'переднее сиденье', 'кресло переднее']::text[], true),
       ('salon', 'Сиденье заднее', ARRAY['сиденье заднее', 'заднее сиденье', 'диван']::text[], false),
       ('salon', 'Панель приборов', ARRAY['панель приборов', 'торпедо', 'торпеда']::text[], false),
       ('salon', 'Щиток приборов', ARRAY['щиток приборов', 'приборка', 'приборная панель']::text[], false),
       ('salon', 'Перчаточный ящик', ARRAY['перчаточный ящик', 'бардачок']::text[], false),
       ('salon', 'Центральная консоль', ARRAY['центральная консоль', 'консоль', 'тоннель']::text[], false),
       ('salon', 'Обшивка двери', ARRAY['обшивка двери', 'карта двери', 'обивка двери']::text[], true),
       ('salon', 'Обивка потолка', ARRAY['обивка потолка', 'потолок']::text[], false),
       ('salon', 'Ковролин', ARRAY['ковролин', 'ковёр', 'ковер']::text[], false),
       ('salon', 'Подлокотник', ARRAY['подлокотник']::text[], false),
       ('salon', 'Солнцезащитный козырёк', ARRAY['солнцезащитный козырек', 'козырёк', 'козырек']::text[], true),
       ('salon', 'Блок управления печкой', ARRAY['блок управления печкой', 'климат-контроль', 'блок печки']::text[], false),
       ('salon', 'Дефлектор', ARRAY['дефлектор', 'воздуховод']::text[], false),
       ('stekla', 'Лобовое стекло', ARRAY['лобовое стекло', 'лобовое', 'ветровое стекло']::text[], false),
       ('stekla', 'Заднее стекло', ARRAY['заднее стекло', 'стекло заднее']::text[], false),
       ('stekla', 'Стекло двери', ARRAY['стекло двери', 'боковое стекло']::text[], true),
       ('stekla', 'Форточка', ARRAY['форточка', 'стекло форточки']::text[], true),
       ('stekla', 'Зеркало наружное', ARRAY['зеркало наружное', 'зеркало', 'боковое зеркало']::text[], true),
       ('stekla', 'Зеркало салонное', ARRAY['зеркало салонное', 'зеркало заднего вида']::text[], false),
       ('ohlazhdenie', 'Радиатор охлаждения', ARRAY['радиатор охлаждения', 'радиатор', 'основной радиатор']::text[], false),
       ('ohlazhdenie', 'Радиатор кондиционера', ARRAY['радиатор кондиционера', 'конденсер', 'радиатор кондея']::text[], false),
       ('ohlazhdenie', 'Радиатор печки', ARRAY['радиатор печки', 'радиатор отопителя']::text[], false),
       ('ohlazhdenie', 'Интеркулер', ARRAY['интеркулер', 'радиатор интеркулера']::text[], false),
       ('ohlazhdenie', 'Вентилятор охлаждения', ARRAY['вентилятор охлаждения', 'вентилятор радиатора']::text[], false),
       ('ohlazhdenie', 'Диффузор вентилятора', ARRAY['диффузор вентилятора', 'диффузор']::text[], false),
       ('ohlazhdenie', 'Расширительный бачок', ARRAY['расширительный бачок', 'бачок']::text[], false),
       ('ohlazhdenie', 'Патрубок', ARRAY['патрубок', 'шланг']::text[], false),
       ('ohlazhdenie', 'Термостат', ARRAY['термостат']::text[], false),
       ('ohlazhdenie', 'Испаритель', ARRAY['испаритель']::text[], false),
       ('ohlazhdenie', 'Осушитель кондиционера', ARRAY['осушитель кондиционера', 'осушитель']::text[], false),
       ('ohlazhdenie', 'Трубка кондиционера', ARRAY['трубка кондиционера', 'магистраль кондиционера']::text[], false),
       ('ohlazhdenie', 'Отопитель', ARRAY['отопитель', 'печка']::text[], false),
       ('vypusk', 'Глушитель', ARRAY['глушитель', 'банка']::text[], false),
       ('vypusk', 'Резонатор', ARRAY['резонатор']::text[], false),
       ('vypusk', 'Катализатор', ARRAY['катализатор', 'кат']::text[], false),
       ('vypusk', 'Приёмная труба', ARRAY['приемная труба', 'приёмная труба', 'штаны']::text[], false),
       ('vypusk', 'Гофра', ARRAY['гофра']::text[], false),
       ('vypusk', 'Топливный бак', ARRAY['топливный бак', 'бак', 'бензобак']::text[], false),
       ('vypusk', 'Топливный насос', ARRAY['топливный насос', 'бензонасос']::text[], false),
       ('vypusk', 'Датчик уровня топлива', ARRAY['датчик уровня топлива', 'дут']::text[], false),
       ('vypusk', 'Крышка бензобака', ARRAY['крышка бензобака', 'лючок бензобака']::text[], false),
       ('vypusk', 'Топливный фильтр', ARRAY['топливный фильтр', 'фильтр топливный']::text[], false),
       ('vypusk', 'Адсорбер', ARRAY['адсорбер']::text[], false),
       ('kolesa', 'Диск колёсный', ARRAY['диск колесный', 'диск колёсный', 'диск']::text[], false),
       ('kolesa', 'Шина', ARRAY['шина', 'покрышка', 'резина']::text[], false),
       ('kolesa', 'Колесо в сборе', ARRAY['колесо в сборе', 'колесо']::text[], false),
       ('kolesa', 'Колпак колеса', ARRAY['колпак колеса', 'колпак']::text[], false),
       ('kolesa', 'Датчик давления в шинах', ARRAY['датчик давления в шинах', 'датчик давления']::text[], false),
       ('kolesa', 'Запасное колесо', ARRAY['запасное колесо', 'запаска', 'докатка']::text[], false)) AS v(category_slug, name, synonyms, has_side)
  JOIN catalog.part_category c ON c.slug = v.category_slug
 WHERE NOT EXISTS (
       SELECT 1 FROM catalog.part_kind k
        WHERE lower(btrim(k.name)) = lower(btrim(v.name)));

--rollback DELETE FROM catalog.part_kind;
