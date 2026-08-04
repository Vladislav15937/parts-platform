#!/usr/bin/env python3
"""Русские написания марок и моделей для поиска по складу.

Покупатель звонит и говорит по-русски: «есть фара на камри?». Продавец
набирает как слышит — и получает ноль при тысяче двухстах позициях
от Camry на складе. Ровно та же беда, что с размерами колёс («225 55 18»
против «225/55 R18») и с кодами ячеек на этикетках: данные есть,
а спрашивают о них другими знаками.

Словарь составлен руками, а не транслитерацией: одно латинское имя даёт
несколько ходовых русских написаний («Аутлендер» и «Оутлендер», «СРВ»
и «ЦРВ»), и правило, выдающее одно, промахивается ровно там, где человек
написал второе. Это тот же выбор, что у справочника видов деталей: словарь
живого языка не выводится из данных.

Состав: 112 моделей живого склада «YARD Ткацкая» плюс ходовые японские
и корейские, которых у него нет, но которые встретятся у соседей —
первые десять клиентов из одного города и торгуют примерно одним.

Запуск: python3 db/seed/build_vehicle_aliases.py > \
        db/changelog/catalog/018-vehicle-aliases.sql
"""

# Марка → её русские написания. У марок уже есть name_ru, но одного
# написания мало: «Мицубиси», «Митсубиси» и «Мицубиши» пишут все три.
BRANDS = {
    'Toyota': ['тойота', 'таета', 'тоета'],
    'Honda': ['хонда'],
    'Nissan': ['ниссан', 'нисан'],
    'Mitsubishi': ['мицубиси', 'митсубиси', 'мицубиши', 'митсубиши'],
    'Mazda': ['мазда'],
    'Subaru': ['субару'],
    'Suzuki': ['сузуки', 'судзуки'],
    'Daihatsu': ['дайхатсу', 'дайхацу'],
    'Lexus': ['лексус'],
    'Infiniti': ['инфинити', 'инфинипи'],
    'Isuzu': ['исузу'],
    'Ford': ['форд'],
    'Volkswagen': ['фольксваген', 'вольксваген', 'фольцваген'],
    'BMW': ['бмв'],
    'Mercedes-Benz': ['мерседес', 'мерс'],
    'Audi': ['ауди'],
    'Skoda': ['шкода'],
    'Opel': ['опель'],
    'Renault': ['рено'],
    'Peugeot': ['пежо'],
    'Citroen': ['ситроен'],
    'Volvo': ['вольво'],
    'Hyundai': ['хендай', 'хундай', 'хёндай', 'хендэ'],
    'Kia': ['киа', 'кия'],
    'Chevrolet': ['шевроле'],
    'Land Rover': ['ленд ровер', 'лендровер'],
    'Porsche': ['порше'],
    'Jeep': ['джип'],
    'Mini': ['мини'],
    'Chery': ['чери', 'черри'],
    'Geely': ['джили', 'жили'],
    'Haval': ['хавал', 'хавейл'],
}

# Модель → её русские написания. Ключ — пара «марка, модель», потому что
# одно имя бывает у разных марок, а сводить их в одно значит отвечать
# продавцу деталями чужой машины.
MODELS = {
    # --- Toyota, верхушка живого склада ---
    ('Toyota', 'Camry'): ['камри', 'кэмри'],
    ('Toyota', 'Harrier'): ['харриер', 'хариер', 'харьер'],
    ('Toyota', 'RAV4'): ['рав4', 'рав 4', 'равчик', 'рафик'],
    ('Toyota', 'Caldina'): ['калдина', 'кальдина'],
    ('Toyota', 'Ipsum'): ['ипсум'],
    ('Toyota', 'Allion'): ['аллион', 'алион'],
    ('Toyota', 'Carina'): ['карина'],
    ('Toyota', 'Carina ED'): ['карина ед', 'карина эд'],
    ('Toyota', 'Vista'): ['виста'],
    ('Toyota', 'Vista Ardeo'): ['виста ардео', 'ардео'],
    ('Toyota', 'Avensis'): ['авенсис'],
    ('Toyota', 'ist'): ['ист'],
    ('Toyota', 'Vanguard'): ['вангард', 'вэнгард'],
    ('Toyota', 'Corolla'): ['королла', 'корола', 'каролла'],
    ('Toyota', 'Corolla Fielder'): ['королла филдер', 'филдер', 'фиелдер'],
    ('Toyota', 'Corolla Spacio'): ['королла спасио', 'спасио', 'спацио'],
    ('Toyota', 'Corolla Runx'): ['королла ранкс', 'ранкс'],
    ('Toyota', 'Corolla Levin'): ['королла левин', 'левин'],
    ('Toyota', 'Corolla Ceres'): ['королла церес', 'церес'],
    ('Toyota', 'Corolla Axio'): ['королла аксио', 'аксио'],
    ('Toyota', 'Allex'): ['аллекс', 'алекс'],
    ('Toyota', 'Chaser'): ['чайзер', 'чейзер'],
    ('Toyota', 'Mark II'): ['марк 2', 'марк два', 'маркушник'],
    ('Toyota', 'Mark II Wagon Qualis'): ['марк 2 квалис', 'квалис'],
    ('Toyota', 'Mark X Zio'): ['марк х зио', 'зио'],
    ('Toyota', 'Wish'): ['виш', 'вишь'],
    ('Toyota', 'Opa'): ['опа'],
    ('Toyota', 'Camry Gracia'): ['камри грация', 'грация'],
    ('Toyota', 'Raum'): ['раум'],
    ('Toyota', 'Alphard'): ['альфард', 'алфард'],
    ('Toyota', 'Premio'): ['премио'],
    ('Toyota', 'Lite Ace Noah'): ['лит айс ноах', 'лит айс'],
    ('Toyota', 'Town Ace Noah'): ['таун айс ноах', 'таун айс'],
    ('Toyota', 'Corona'): ['корона'],
    ('Toyota', 'Corona Premio'): ['корона премио'],
    ('Toyota', 'Gaia'): ['гайя', 'гая'],
    ('Toyota', 'C-HR'): ['цхр', 'схр', 'ц-хр'],
    ('Toyota', 'Noah'): ['ноах', 'ноа'],
    ('Toyota', 'Windom'): ['виндом'],
    ('Toyota', 'Vitz'): ['витц', 'виц', 'витз'],
    ('Toyota', 'Sprinter'): ['спринтер'],
    ('Toyota', 'Sprinter Carib'): ['спринтер кариб', 'кариб'],
    ('Toyota', 'Crown'): ['краун', 'кроун'],
    ('Toyota', 'Duet'): ['дуэт'],
    ('Toyota', 'Platz'): ['платц', 'плац'],
    ('Toyota', 'Voxy'): ['вокси'],
    ('Toyota', 'Grand Hiace'): ['гранд хайс', 'хайс'],
    ('Toyota', 'Funcargo'): ['функарго', 'фанкарго'],
    ('Toyota', 'Curren'): ['куррен', 'курен'],
    ('Toyota', 'Cresta'): ['креста'],
    ('Toyota', 'Probox'): ['пробокс'],
    ('Toyota', 'Succeed'): ['саксид', 'суксид'],
    ('Toyota', 'Nadia'): ['надиа', 'надя'],
    ('Toyota', 'Prius'): ['приус', 'приюс'],
    ('Toyota', 'Land Cruiser'): ['ленд крузер', 'крузак', 'лк'],
    ('Toyota', 'Land Cruiser Prado'): ['прадо', 'ленд крузер прадо'],
    ('Toyota', 'Hilux Surf'): ['хайлюкс сурф', 'сурф'],
    ('Toyota', 'Estima'): ['эстима'],
    ('Toyota', 'Passo'): ['пассо'],
    ('Toyota', 'Belta'): ['белта', 'бэлта'],
    ('Toyota', 'Auris'): ['аурис'],
    ('Toyota', 'Yaris'): ['ярис'],
    ('Toyota', 'Highlander'): ['хайлендер', 'хайландер'],
    ('Toyota', 'Fortuner'): ['фортунер'],
    ('Toyota', 'Verossa'): ['веросса', 'вероса'],
    ('Toyota', 'Brevis'): ['бревис'],
    ('Toyota', 'Progres'): ['прогрес', 'прогресс'],
    ('Toyota', 'Sienta'): ['сиента'],
    ('Toyota', 'Porte'): ['порте'],
    ('Toyota', 'bB'): ['бб', 'би би'],
    ('Toyota', 'Will Vs'): ['вилл вс', 'вилл'],
    ('Toyota', 'Vellfire'): ['велфаер', 'вэлфайр'],

    # --- Honda ---
    ('Honda', 'CR-V'): ['срв', 'црв', 'ср-в', 'цр-в'],
    ('Honda', 'HR-V'): ['хрв', 'хр-в'],
    ('Honda', 'Civic'): ['цивик', 'сивик'],
    ('Honda', 'Civic Ferio'): ['цивик ферио', 'ферио'],
    ('Honda', 'Torneo'): ['торнео'],
    ('Honda', 'Accord'): ['аккорд', 'акорд'],
    ('Honda', 'Stepwgn'): ['степвагон', 'степ вагон', 'степвгн'],
    ('Honda', 'Stream'): ['стрим'],
    ('Honda', 'Inspire'): ['инспаер', 'инспайр'],
    ('Honda', 'Fit'): ['фит'],
    ('Honda', 'Freed'): ['фрид'],
    ('Honda', 'Vezel'): ['везел', 'вэзел'],
    ('Honda', 'Shuttle'): ['шаттл'],
    ('Honda', 'Odyssey'): ['одиссей', 'одисей'],
    ('Honda', 'N-WGN'): ['н вагон', 'эн вагон'],
    ('Honda', 'Airwave'): ['эйрвейв', 'аирвейв'],
    ('Honda', 'Mobilio'): ['мобилио'],
    ('Honda', 'Insight'): ['инсайт'],
    ('Honda', 'Legend'): ['легенд'],
    ('Honda', 'Pilot'): ['пилот'],
    ('Honda', 'Partner'): ['партнер', 'партнёр'],
    ('Honda', 'Capa'): ['капа'],
    ('Honda', 'Logo'): ['лого'],

    # --- Nissan ---
    ('Nissan', 'X-Trail'): ['икстрейл', 'икс трейл', 'х трейл', 'хтрейл'],
    ('Nissan', 'Sunny'): ['санни', 'сани'],
    ('Nissan', 'Cefiro'): ['цефиро', 'сефиро'],
    ('Nissan', 'Bluebird'): ['блюберд', 'блюбёрд'],
    ('Nissan', 'Bluebird Sylphy'): ['блюберд силфи', 'силфи', 'сильфи'],
    ('Nissan', 'Teana'): ['теана', 'тиана'],
    ('Nissan', 'Primera'): ['примера'],
    ('Nissan', 'Serena'): ['серена'],
    ('Nissan', 'Wingroad'): ['вингроад', 'вингровд'],
    ('Nissan', 'Juke'): ['джук'],
    ('Nissan', 'Dualis'): ['дуалис'],
    ('Nissan', 'AD'): ['ад', 'а д'],
    ('Nissan', 'Cube'): ['куб', 'кубик'],
    ('Nissan', 'March'): ['марч'],
    ('Nissan', 'Note'): ['ноут', 'нот'],
    ('Nissan', 'Qashqai'): ['кашкай'],
    ('Nissan', 'Tiida'): ['тиида', 'тида'],
    ('Nissan', 'Almera'): ['альмера', 'алмера'],
    ('Nissan', 'Murano'): ['мурано'],
    ('Nissan', 'Patrol'): ['патрол', 'патруль'],
    ('Nissan', 'Skyline'): ['скайлайн'],
    ('Nissan', 'Liberty'): ['либерти'],
    ('Nissan', 'Presage'): ['пресаж'],
    ('Nissan', 'Lafesta'): ['лафеста'],

    # --- Mitsubishi ---
    ('Mitsubishi', 'Outlander'): ['аутлендер', 'оутлендер', 'аутлэндер'],
    ('Mitsubishi', 'Galant Fortis'): ['галант фортис', 'фортис'],
    ('Mitsubishi', 'Lancer'): ['лансер', 'ланцер'],
    ('Mitsubishi', 'Challenger'): ['челленджер', 'чалленджер'],
    ('Mitsubishi', 'Airtrek'): ['аиртрек', 'эйртрек'],
    ('Mitsubishi', 'Delica'): ['делика'],
    ('Mitsubishi', 'RVR'): ['рвр', 'р в р'],
    ('Mitsubishi', 'Legnum'): ['легнум'],
    ('Mitsubishi', 'Pajero Mini'): ['паджеро мини'],
    ('Mitsubishi', 'Pajero'): ['паджеро', 'поджеро'],
    ('Mitsubishi', 'Galant'): ['галант'],
    ('Mitsubishi', 'ASX'): ['асх', 'а с икс'],
    ('Mitsubishi', 'Colt'): ['кольт', 'колт'],
    ('Mitsubishi', 'Dion'): ['дион'],
    ('Mitsubishi', 'Chariot'): ['шариот', 'чариот'],

    # --- Subaru ---
    ('Subaru', 'Forester'): ['форестер', 'форрестер'],
    ('Subaru', 'Legacy'): ['легаси', 'легацы'],
    ('Subaru', 'Legacy Lancaster'): ['легаси ланкастер', 'ланкастер'],
    ('Subaru', 'Levorg'): ['леворг'],
    ('Subaru', 'Impreza'): ['импреза'],
    ('Subaru', 'Outback'): ['аутбек', 'оутбек'],
    ('Subaru', 'Exiga'): ['эксига'],
    ('Subaru', 'Tribeca'): ['трибека'],
    ('Subaru', 'XV'): ['икс в', 'хв'],

    # --- Mazda ---
    ('Mazda', 'CX-5'): ['цх5', 'сх5', 'сх 5', 'цх 5'],
    ('Mazda', 'Familia S-Wagon'): ['фамилия с вагон', 'фамилия'],
    ('Mazda', 'Capella'): ['капелла', 'капела'],
    ('Mazda', 'MPV'): ['мпв', 'м п в'],
    ('Mazda', 'Demio'): ['демио'],
    ('Mazda', 'Premacy'): ['премаси', 'премаци'],
    ('Mazda', 'Tribute'): ['трибьют', 'трибут'],
    ('Mazda', 'Axela'): ['аксела', 'акселла'],
    ('Mazda', 'Atenza'): ['атенза'],
    ('Mazda', 'CX-7'): ['цх7', 'сх7'],
    ('Mazda', 'CX-9'): ['цх9', 'сх9'],
    ('Mazda', 'Bongo'): ['бонго'],

    # --- Suzuki ---
    ('Suzuki', 'Escudo'): ['эскудо'],
    ('Suzuki', 'Grand Escudo'): ['гранд эскудо'],
    ('Suzuki', 'Aerio'): ['аэрио', 'аерио'],
    ('Suzuki', 'Swift'): ['свифт'],
    ('Suzuki', 'Jimny'): ['джимни'],
    ('Suzuki', 'SX4'): ['сх4', 'эс икс 4'],
    ('Suzuki', 'Wagon R'): ['вагон р'],
    ('Suzuki', 'Solio'): ['солио'],

    # --- Прочие, встречающиеся на живом складе ---
    ('Daihatsu', 'Terios Kid'): ['териос кид', 'териос'],
    ('Infiniti', 'FX35'): ['фх35', 'эф икс 35'],
    ('Infiniti', 'FX45'): ['фх45', 'эф икс 45'],
    ('Ford', 'Focus'): ['фокус'],
    ('Ford', 'Kuga'): ['куга'],
    ('Ford', 'Mondeo'): ['мондео'],
    ('Ford', 'Explorer'): ['эксплорер'],
    ('BMW', 'X3'): ['икс3', 'х3'],
    ('BMW', 'X5'): ['икс5', 'х5'],
    ('BMW', 'X6'): ['икс6', 'х6'],
    ('BMW', '3-Series'): ['3 серия', 'третья серия'],
    ('BMW', '5-Series'): ['5 серия', 'пятая серия'],
    ('Volkswagen', 'Tiguan'): ['тигуан'],
    ('Volkswagen', 'Polo'): ['поло'],
    ('Volkswagen', 'Passat'): ['пассат', 'пасат'],
    ('Volkswagen', 'Golf'): ['гольф', 'голф'],
    ('Volkswagen', 'Touareg'): ['туарег'],
    ('Skoda', 'Fabia'): ['фабия'],
    ('Skoda', 'Octavia'): ['октавия'],
    ('Hyundai', 'Elantra'): ['элантра'],
    ('Hyundai', 'Solaris'): ['солярис'],
    ('Hyundai', 'Tucson'): ['туксон'],
    ('Hyundai', 'Santa Fe'): ['санта фе', 'сантафе'],
    ('Hyundai', 'Creta'): ['крета'],
    ('Hyundai', 'Accent'): ['акцент', 'ацент'],
    ('Kia', 'Rio'): ['рио'],
    ('Kia', 'Sportage'): ['спортейдж', 'спортаж'],
    ('Kia', 'Ceed'): ['сид', 'цеед'],
    ('Kia', 'Sorento'): ['соренто'],
    ('Porsche', 'Boxster'): ['бокстер'],
    ('Porsche', 'Cayenne'): ['кайен', 'каенн'],
}


def quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main() -> None:
    print('--liquibase formatted sql')
    print()
    print('--changeset partsflow:catalog-018-vehicle-aliases runOnChange:false')
    print('--comment Русские написания марок и моделей: покупатель звонит и говорит «камри»')
    print()
    print("""-- Поиск по складу шёл только по латинице: «Camry» находил 1218 позиций,
-- «камри» — ноль. А спрашивают по телефону, и продавец набирает как слышит.
--
-- Написаний у одного имени несколько («Аутлендер» и «Оутлендер», «СРВ»
-- и «ЦРВ»), поэтому это список, а не колонка: правило, выдающее одно
-- написание, промахивается ровно там, где человек написал второе.
--
-- Марка в ключе модели не для порядка: «Civic» есть у Honda, а имена
-- моделей у разных марок совпадают — сведя их, продавец получил бы
-- в ответ детали чужой машины.""")
    print()
    print("""CREATE TABLE catalog.brand_alias (
    brand_id bigint NOT NULL REFERENCES catalog.brand (id) ON DELETE CASCADE,
    alias    text   NOT NULL,
    PRIMARY KEY (brand_id, alias)
);""")
    print()
    print("""CREATE TABLE catalog.model_alias (
    model_id bigint NOT NULL REFERENCES catalog.model (id) ON DELETE CASCADE,
    alias    text   NOT NULL,
    PRIMARY KEY (model_id, alias)
);""")
    print()
    print('-- Ищут по написанию, а не по идентификатору: индекс нужен на нём.')
    print('CREATE INDEX brand_alias_ix ON catalog.brand_alias (alias);')
    print('CREATE INDEX model_alias_ix ON catalog.model_alias (alias);')
    print()

    print('-- Марки')
    for name, aliases in sorted(BRANDS.items()):
        values = ', '.join('(%s)' % quote(alias) for alias in sorted(set(aliases)))
        print('INSERT INTO catalog.brand_alias (brand_id, alias)')
        print('SELECT b.id, a.alias FROM catalog.brand b,')
        print('       (VALUES %s) AS a(alias)' % values)
        print(' WHERE b.name = %s' % quote(name))
        print('ON CONFLICT DO NOTHING;')
    print()

    print('-- Модели')
    for (brand, model), aliases in sorted(MODELS.items()):
        values = ', '.join('(%s)' % quote(alias) for alias in sorted(set(aliases)))
        print('INSERT INTO catalog.model_alias (model_id, alias)')
        print('SELECT m.id, a.alias FROM catalog.model m')
        print('  JOIN catalog.brand b ON b.id = m.brand_id,')
        print('       (VALUES %s) AS a(alias)' % values)
        print(' WHERE b.name = %s AND m.name = %s' % (quote(brand), quote(model)))
        print('ON CONFLICT DO NOTHING;')

    print()
    print('--rollback DROP TABLE catalog.model_alias;')
    print('--rollback DROP TABLE catalog.brand_alias;')


if __name__ == '__main__':
    main()
