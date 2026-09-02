--liquibase formatted sql

--changeset partsflow:catalog-018-vehicle-aliases runOnChange:false
--comment Русские написания марок и моделей: покупатель звонит и говорит «камри»

-- Поиск по складу шёл только по латинице: «Camry» находил 1218 позиций,
-- «камри» — ноль. А спрашивают по телефону, и продавец набирает как слышит.
--
-- Написаний у одного имени несколько («Аутлендер» и «Оутлендер», «СРВ»
-- и «ЦРВ»), поэтому это список, а не колонка: правило, выдающее одно
-- написание, промахивается ровно там, где человек написал второе.
--
-- Марка в ключе модели не для порядка: «Civic» есть у Honda, а имена
-- моделей у разных марок совпадают — сведя их, продавец получил бы
-- в ответ детали чужой машины.

CREATE TABLE catalog.brand_alias (
    brand_id bigint NOT NULL REFERENCES catalog.brand (id) ON DELETE CASCADE,
    alias    text   NOT NULL,
    PRIMARY KEY (brand_id, alias)
);

CREATE TABLE catalog.model_alias (
    model_id bigint NOT NULL REFERENCES catalog.model (id) ON DELETE CASCADE,
    alias    text   NOT NULL,
    PRIMARY KEY (model_id, alias)
);

-- Ищут по написанию, а не по идентификатору: индекс нужен на нём.
CREATE INDEX brand_alias_ix ON catalog.brand_alias (alias);
CREATE INDEX model_alias_ix ON catalog.model_alias (alias);

-- Марки
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('ауди')) AS a(alias)
 WHERE b.name = 'Audi'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('бмв')) AS a(alias)
 WHERE b.name = 'BMW'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('чери'), ('черри')) AS a(alias)
 WHERE b.name = 'Chery'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('шевроле')) AS a(alias)
 WHERE b.name = 'Chevrolet'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('ситроен')) AS a(alias)
 WHERE b.name = 'Citroen'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('дайхатсу'), ('дайхацу')) AS a(alias)
 WHERE b.name = 'Daihatsu'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('форд')) AS a(alias)
 WHERE b.name = 'Ford'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('джили'), ('жили')) AS a(alias)
 WHERE b.name = 'Geely'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('хавал'), ('хавейл')) AS a(alias)
 WHERE b.name = 'Haval'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('хонда')) AS a(alias)
 WHERE b.name = 'Honda'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('хендай'), ('хендэ'), ('хундай'), ('хёндай')) AS a(alias)
 WHERE b.name = 'Hyundai'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('инфинипи'), ('инфинити')) AS a(alias)
 WHERE b.name = 'Infiniti'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('исузу')) AS a(alias)
 WHERE b.name = 'Isuzu'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('джип')) AS a(alias)
 WHERE b.name = 'Jeep'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('киа'), ('кия')) AS a(alias)
 WHERE b.name = 'Kia'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('ленд ровер'), ('лендровер')) AS a(alias)
 WHERE b.name = 'Land Rover'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('лексус')) AS a(alias)
 WHERE b.name = 'Lexus'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('мазда')) AS a(alias)
 WHERE b.name = 'Mazda'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('мерс'), ('мерседес')) AS a(alias)
 WHERE b.name = 'Mercedes-Benz'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('мини')) AS a(alias)
 WHERE b.name = 'Mini'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('митсубиси'), ('митсубиши'), ('мицубиси'), ('мицубиши')) AS a(alias)
 WHERE b.name = 'Mitsubishi'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('нисан'), ('ниссан')) AS a(alias)
 WHERE b.name = 'Nissan'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('опель')) AS a(alias)
 WHERE b.name = 'Opel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('пежо')) AS a(alias)
 WHERE b.name = 'Peugeot'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('порше')) AS a(alias)
 WHERE b.name = 'Porsche'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('рено')) AS a(alias)
 WHERE b.name = 'Renault'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('шкода')) AS a(alias)
 WHERE b.name = 'Skoda'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('субару')) AS a(alias)
 WHERE b.name = 'Subaru'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('судзуки'), ('сузуки')) AS a(alias)
 WHERE b.name = 'Suzuki'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('таета'), ('тоета'), ('тойота')) AS a(alias)
 WHERE b.name = 'Toyota'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('вольксваген'), ('фольксваген'), ('фольцваген')) AS a(alias)
 WHERE b.name = 'Volkswagen'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.brand_alias (brand_id, alias)
SELECT b.id, a.alias FROM catalog.brand b,
       (VALUES ('вольво')) AS a(alias)
 WHERE b.name = 'Volvo'
ON CONFLICT DO NOTHING;

-- Модели
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('3 серия'), ('третья серия')) AS a(alias)
 WHERE b.name = 'BMW' AND m.name = '3-Series'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('5 серия'), ('пятая серия')) AS a(alias)
 WHERE b.name = 'BMW' AND m.name = '5-Series'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('икс3'), ('х3')) AS a(alias)
 WHERE b.name = 'BMW' AND m.name = 'X3'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('икс5'), ('х5')) AS a(alias)
 WHERE b.name = 'BMW' AND m.name = 'X5'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('икс6'), ('х6')) AS a(alias)
 WHERE b.name = 'BMW' AND m.name = 'X6'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('териос'), ('териос кид')) AS a(alias)
 WHERE b.name = 'Daihatsu' AND m.name = 'Terios Kid'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('эксплорер')) AS a(alias)
 WHERE b.name = 'Ford' AND m.name = 'Explorer'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фокус')) AS a(alias)
 WHERE b.name = 'Ford' AND m.name = 'Focus'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('куга')) AS a(alias)
 WHERE b.name = 'Ford' AND m.name = 'Kuga'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('мондео')) AS a(alias)
 WHERE b.name = 'Ford' AND m.name = 'Mondeo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аккорд'), ('акорд')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Accord'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аирвейв'), ('эйрвейв')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Airwave'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ср-в'), ('срв'), ('цр-в'), ('црв')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'CR-V'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('капа')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Capa'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сивик'), ('цивик')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Civic'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ферио'), ('цивик ферио')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Civic Ferio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фит')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Fit'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фрид')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Freed'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('хр-в'), ('хрв')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'HR-V'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('инсайт')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Insight'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('инспаер'), ('инспайр')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Inspire'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('легенд')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Legend'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('лого')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Logo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('мобилио')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Mobilio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('н вагон'), ('эн вагон')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'N-WGN'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('одисей'), ('одиссей')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Odyssey'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('партнер'), ('партнёр')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Partner'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('пилот')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Pilot'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('шаттл')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Shuttle'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('степ вагон'), ('степвагон'), ('степвгн')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Stepwgn'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('стрим')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Stream'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('торнео')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Torneo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('везел'), ('вэзел')) AS a(alias)
 WHERE b.name = 'Honda' AND m.name = 'Vezel'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('акцент'), ('ацент')) AS a(alias)
 WHERE b.name = 'Hyundai' AND m.name = 'Accent'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('крета')) AS a(alias)
 WHERE b.name = 'Hyundai' AND m.name = 'Creta'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('элантра')) AS a(alias)
 WHERE b.name = 'Hyundai' AND m.name = 'Elantra'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('санта фе'), ('сантафе')) AS a(alias)
 WHERE b.name = 'Hyundai' AND m.name = 'Santa Fe'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('солярис')) AS a(alias)
 WHERE b.name = 'Hyundai' AND m.name = 'Solaris'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('туксон')) AS a(alias)
 WHERE b.name = 'Hyundai' AND m.name = 'Tucson'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фх35'), ('эф икс 35')) AS a(alias)
 WHERE b.name = 'Infiniti' AND m.name = 'FX35'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фх45'), ('эф икс 45')) AS a(alias)
 WHERE b.name = 'Infiniti' AND m.name = 'FX45'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сид'), ('цеед')) AS a(alias)
 WHERE b.name = 'Kia' AND m.name = 'Ceed'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('рио')) AS a(alias)
 WHERE b.name = 'Kia' AND m.name = 'Rio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('соренто')) AS a(alias)
 WHERE b.name = 'Kia' AND m.name = 'Sorento'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('спортаж'), ('спортейдж')) AS a(alias)
 WHERE b.name = 'Kia' AND m.name = 'Sportage'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('атенза')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Atenza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аксела'), ('акселла')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Axela'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('бонго')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Bongo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сх 5'), ('сх5'), ('цх 5'), ('цх5')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'CX-5'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сх7'), ('цх7')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'CX-7'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сх9'), ('цх9')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'CX-9'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('капела'), ('капелла')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Capella'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('демио')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Demio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фамилия'), ('фамилия с вагон')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Familia S-Wagon'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('м п в'), ('мпв')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'MPV'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('премаси'), ('премаци')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Premacy'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('трибут'), ('трибьют')) AS a(alias)
 WHERE b.name = 'Mazda' AND m.name = 'Tribute'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('а с икс'), ('асх')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'ASX'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аиртрек'), ('эйртрек')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Airtrek'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('чалленджер'), ('челленджер')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Challenger'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('чариот'), ('шариот')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Chariot'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('колт'), ('кольт')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Colt'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('делика')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Delica'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('дион')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Dion'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('галант')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Galant'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('галант фортис'), ('фортис')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Galant Fortis'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('лансер'), ('ланцер')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Lancer'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('легнум')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Legnum'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аутлендер'), ('аутлэндер'), ('оутлендер')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Outlander'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('паджеро'), ('поджеро')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Pajero'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('паджеро мини')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'Pajero Mini'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('р в р'), ('рвр')) AS a(alias)
 WHERE b.name = 'Mitsubishi' AND m.name = 'RVR'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('а д'), ('ад')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'AD'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('алмера'), ('альмера')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Almera'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('блюберд'), ('блюбёрд')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Bluebird'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('блюберд силфи'), ('силфи'), ('сильфи')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Bluebird Sylphy'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сефиро'), ('цефиро')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Cefiro'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('куб'), ('кубик')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Cube'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('дуалис')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Dualis'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('джук')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Juke'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('лафеста')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Lafesta'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('либерти')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Liberty'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('марч')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'March'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('мурано')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Murano'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('нот'), ('ноут')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Note'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('патрол'), ('патруль')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Patrol'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('пресаж')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Presage'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('примера')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Primera'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('кашкай')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Qashqai'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('серена')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Serena'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('скайлайн')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Skyline'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сани'), ('санни')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Sunny'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('теана'), ('тиана')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Teana'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('тида'), ('тиида')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Tiida'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('вингроад'), ('вингровд')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'Wingroad'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('икс трейл'), ('икстрейл'), ('х трейл'), ('хтрейл')) AS a(alias)
 WHERE b.name = 'Nissan' AND m.name = 'X-Trail'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('бокстер')) AS a(alias)
 WHERE b.name = 'Porsche' AND m.name = 'Boxster'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('каенн'), ('кайен')) AS a(alias)
 WHERE b.name = 'Porsche' AND m.name = 'Cayenne'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фабия')) AS a(alias)
 WHERE b.name = 'Skoda' AND m.name = 'Fabia'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('октавия')) AS a(alias)
 WHERE b.name = 'Skoda' AND m.name = 'Octavia'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('эксига')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Exiga'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('форестер'), ('форрестер')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Forester'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('импреза')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Impreza'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('легаси'), ('легацы')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Legacy'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ланкастер'), ('легаси ланкастер')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Legacy Lancaster'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('леворг')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Levorg'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аутбек'), ('оутбек')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Outback'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('трибека')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'Tribeca'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('икс в'), ('хв')) AS a(alias)
 WHERE b.name = 'Subaru' AND m.name = 'XV'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аерио'), ('аэрио')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'Aerio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('эскудо')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'Escudo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('гранд эскудо')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'Grand Escudo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('джимни')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'Jimny'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сх4'), ('эс икс 4')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'SX4'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('солио')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'Solio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('свифт')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'Swift'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('вагон р')) AS a(alias)
 WHERE b.name = 'Suzuki' AND m.name = 'Wagon R'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('алекс'), ('аллекс')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Allex'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('алион'), ('аллион')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Allion'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('алфард'), ('альфард')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Alphard'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аурис')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Auris'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('авенсис')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Avensis'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('белта'), ('бэлта')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Belta'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('бревис')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Brevis'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('схр'), ('ц-хр'), ('цхр')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'C-HR'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('калдина'), ('кальдина')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Caldina'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('камри'), ('кэмри')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Camry'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('грация'), ('камри грация')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Camry Gracia'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('карина')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Carina'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('карина ед'), ('карина эд')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Carina ED'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('чайзер'), ('чейзер')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Chaser'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('каролла'), ('корола'), ('королла')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corolla'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('аксио'), ('королла аксио')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corolla Axio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('королла церес'), ('церес')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corolla Ceres'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('королла филдер'), ('фиелдер'), ('филдер')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corolla Fielder'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('королла левин'), ('левин')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corolla Levin'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('королла ранкс'), ('ранкс')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corolla Runx'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('королла спасио'), ('спасио'), ('спацио')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corolla Spacio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('корона')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corona'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('корона премио')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Corona Premio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('креста')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Cresta'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('краун'), ('кроун')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Crown'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('курен'), ('куррен')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Curren'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('дуэт')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Duet'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('эстима')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Estima'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фортунер')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Fortuner'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('фанкарго'), ('функарго')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Funcargo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('гайя'), ('гая')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Gaia'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('гранд хайс'), ('хайс')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Grand Hiace'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('хариер'), ('харриер'), ('харьер')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Harrier'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('хайландер'), ('хайлендер')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Highlander'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сурф'), ('хайлюкс сурф')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Hilux Surf'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ипсум')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Ipsum'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('крузак'), ('ленд крузер'), ('лк')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Land Cruiser'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ленд крузер прадо'), ('прадо')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Land Cruiser Prado'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('лит айс'), ('лит айс ноах')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Lite Ace Noah'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('марк 2'), ('марк два'), ('маркушник')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Mark II'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('квалис'), ('марк 2 квалис')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Mark II Wagon Qualis'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('зио'), ('марк х зио')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Mark X Zio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('надиа'), ('надя')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Nadia'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ноа'), ('ноах')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Noah'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('опа')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Opa'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('пассо')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Passo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('платц'), ('плац')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Platz'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('порте')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Porte'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('премио')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Premio'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('приус'), ('приюс')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Prius'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('пробокс')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Probox'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('прогрес'), ('прогресс')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Progres'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('рав 4'), ('рав4'), ('равчик'), ('рафик')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'RAV4'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('раум')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Raum'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('сиента')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Sienta'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('спринтер')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Sprinter'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('кариб'), ('спринтер кариб')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Sprinter Carib'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('саксид'), ('суксид')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Succeed'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('таун айс'), ('таун айс ноах')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Town Ace Noah'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('вангард'), ('вэнгард')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Vanguard'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('велфаер'), ('вэлфайр')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Vellfire'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('вероса'), ('веросса')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Verossa'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('виста')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Vista'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ардео'), ('виста ардео')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Vista Ardeo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('витз'), ('витц'), ('виц')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Vitz'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('вокси')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Voxy'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('вилл'), ('вилл вс')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Will Vs'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('виндом')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Windom'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('виш'), ('вишь')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Wish'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ярис')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'Yaris'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('бб'), ('би би')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'bB'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('ист')) AS a(alias)
 WHERE b.name = 'Toyota' AND m.name = 'ist'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('голф'), ('гольф')) AS a(alias)
 WHERE b.name = 'Volkswagen' AND m.name = 'Golf'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('пасат'), ('пассат')) AS a(alias)
 WHERE b.name = 'Volkswagen' AND m.name = 'Passat'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('поло')) AS a(alias)
 WHERE b.name = 'Volkswagen' AND m.name = 'Polo'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('тигуан')) AS a(alias)
 WHERE b.name = 'Volkswagen' AND m.name = 'Tiguan'
ON CONFLICT DO NOTHING;
INSERT INTO catalog.model_alias (model_id, alias)
SELECT m.id, a.alias FROM catalog.model m
  JOIN catalog.brand b ON b.id = m.brand_id,
       (VALUES ('туарег')) AS a(alias)
 WHERE b.name = 'Volkswagen' AND m.name = 'Touareg'
ON CONFLICT DO NOTHING;

--rollback DROP TABLE catalog.model_alias;
--rollback DROP TABLE catalog.brand_alias;
