--liquibase formatted sql

--changeset platform:catalog-021-session-store
--comment Хранилище серверных сессий ячейки. Служебное, бизнес-данных не несёт.
--comment
--comment До этого changeset'а сессии жили в памяти процесса, и это делало
--comment неправдой два уже данных обещания. Первое: перезапуск приложения
--comment выкидывает всех вошедших — наблюдали живьём на офлайн-прогоне
--comment приёмки, — а порядок выкладки владельца от 12 сентября 2026 держит
--comment старую сборку живой ровно затем, чтобы простоя не было. Второе
--comment важнее: решение владельца от 8 сентября 2026 требует, чтобы отзыв
--comment доступа убивал уже открытую сессию немедленно. На одном экземпляре
--comment это работало; со вторым сессия умирала только там, где её отозвали,
--comment а сосед пускал по той же cookie до истечения.
--comment
--comment Таблицы — стандартные Spring Session JDBC (schema-postgresql.sql
--comment из spring-session-jdbc 3.3.3), слово в слово, кроме схемы public
--comment в имени. Переписывать их «под наш стиль» нельзя: запросы к ним
--comment пишет не наш код, а JdbcIndexedSessionRepository, и любое
--comment расхождение в именах колонок — отказ на первом же входе.
--comment
--comment Почему public, а не схема арендатора. Сессию надо найти РАНЬШЕ,
--comment чем известен арендатор: в cookie лежит только идентификатор сессии,
--comment а схема — уже внутри неё. Положив сессии в схему клиента, мы бы
--comment искали их обходом всех схем ячейки на каждый запрос. Изоляция
--comment при этом держится не правами (роль у приложения одна на всех
--comment арендаторов и так), а тем, что идентификатор сессии — случайный
--comment секрет, а арендатор лежит внутри самой сессии: подставить чужую
--comment схему запросом нельзя, её оттуда берёт TenantFilter. Отдельная
--comment польза от public: восстановление одного клиента из бэкапа не
--comment воскрешает его сессии — сессия не бизнес-данные.
CREATE TABLE public.spring_session (
    primary_id            char(36) NOT NULL,
    session_id            char(36) NOT NULL,
    creation_time         bigint   NOT NULL,
    last_access_time      bigint   NOT NULL,
    max_inactive_interval int      NOT NULL,
    expiry_time           bigint   NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON public.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON public.spring_session (principal_name);

CREATE TABLE public.spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk
        PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk
        FOREIGN KEY (session_primary_id) REFERENCES public.spring_session (primary_id)
        ON DELETE CASCADE
);

--rollback DROP TABLE public.spring_session_attributes;
--rollback DROP TABLE public.spring_session;
