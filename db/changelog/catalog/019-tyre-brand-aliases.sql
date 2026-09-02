--liquibase formatted sql

--changeset partsflow:catalog-019-tyre-brand-aliases runOnChange:false
--comment Русские написания шинных марок: покупатель звонит и говорит «бриджстоун»

-- Размер поиск колёс уже понимает: «225 55 18» находит то же, что
-- «225/55 R18». А марку — нет: «Bridgestone» отдаёт четыре позиции,
-- «бриджстоун» ни одной. Спрашивают же по телефону обе вместе —
-- «есть зимняя бриджстоун двести двадцать пять на восемнадцать».
--
-- Словарь тот же, что у марок машин: слово запроса приводится к латинскому
-- написанию до поиска. Отдельной таблицы не заводим — у шинной марки нет
-- своей строки в справочнике, и заводить её ради одного столбца значило бы
-- второй справочник производителей рядом с существующим.
CREATE TABLE catalog.tyre_brand_alias (
    alias text NOT NULL PRIMARY KEY,
    name  text NOT NULL
);

CREATE INDEX tyre_brand_alias_name_ix ON catalog.tyre_brand_alias (name);

-- Написаний у одного имени несколько: «Йокохама» и «Ёкохама», «Кама»
-- и «Kama». Правило, выдающее одно, промахивается там, где человек
-- написал второе, — и промах молчаливый.
INSERT INTO catalog.tyre_brand_alias (alias, name) VALUES
    ('бриджстоун', 'Bridgestone'),
    ('бриджстон', 'Bridgestone'),
    ('бридж', 'Bridgestone'),
    ('данлоп', 'Dunlop'),
    ('данлуп', 'Dunlop'),
    ('йокохама', 'Yokohama'),
    ('ёкохама', 'Yokohama'),
    ('йоко', 'Yokohama'),
    ('тойо', 'Toyo'),
    ('мишлен', 'Michelin'),
    ('мишелин', 'Michelin'),
    ('нокиан', 'Nokian'),
    ('нокия', 'Nokian'),
    ('континенталь', 'Continental'),
    ('конти', 'Continental'),
    ('гудиер', 'Goodyear'),
    ('гудьир', 'Goodyear'),
    ('ханкук', 'Hankook'),
    ('кумхо', 'Kumho'),
    ('пирелли', 'Pirelli'),
    ('максис', 'Maxxis'),
    ('нитто', 'Nitto'),
    ('фалкен', 'Falken'),
    ('кама', 'Kama'),
    ('кордиант', 'Cordiant'),
    ('виатти', 'Viatti'),
    ('амтел', 'Amtel'),
    ('матадор', 'Matador'),
    ('белшина', 'Belshina'),
    ('гиславед', 'Gislaved'),
    ('нексен', 'Nexen'),
    ('трайангл', 'Triangle'),
    ('сейлун', 'Sailun'),
    ('энкей', 'Enkei'),
    ('рэйс', 'Rays'),
    ('рейс', 'Rays')
ON CONFLICT DO NOTHING;

--rollback DROP TABLE catalog.tyre_brand_alias;
