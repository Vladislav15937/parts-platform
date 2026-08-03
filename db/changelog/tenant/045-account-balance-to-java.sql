--liquibase formatted sql

--changeset platform:tenant-045-drop-customer-balance-trigger
--comment Остаток лицевого счёта переезжает в Java — первый шаг правила
--comment «логика в приложении, база хранит данные и связи»
--comment (docs/triggers-to-java.md, пункт 3).
--comment
--comment Переносить, впрочем, оказалось нечего: SalesService.accountBalance
--comment считает остаток по журналу с самого начала, вьюхи
--comment v_customer_settlement и v_account_discrepancy — тоже. Колонку
--comment customer.balance не читал никто.
--comment
--comment Хуже: она врала. Знак операции живёт в Java (CustomerAccountEntry
--comment .signedAmount: пополнение прибавляет, выдача вычитает), а в базе
--comment суммы лежат положительными — так они и читаются глазами в журнале.
--comment Триггер же складывал amount как есть, поэтому выдача с лицевого
--comment счёта увеличивала колонку. Замерено на живой схеме: пополнение
--comment 1000 и выдача 400 дают в колонке 1400 при верных 600.
--comment
--comment Это ровно та беда, ради которой правило и заведено: код считает
--comment одно, база показывает другое, и разница объясняется файлом,
--comment который никто не открывал.
DROP TRIGGER IF EXISTS customer_balance_apply_trg ON ${tenant.schema}.customer_account_entry;
DROP FUNCTION IF EXISTS ${tenant.schema}.customer_balance_apply();
--comment
--comment Откат воссоздаёт и функцию, а не только триггер. Снесены они одним
--comment changeset'ом, значит и вернуть их обязан он один: триггер, созданный
--comment на несуществующую функцию, — это отказ Postgres, то есть откат,
--comment который не откатывается. Ловится это только полным разворотом
--comment на чистой базе (db/verify.sh), и ровно на нём стенд и краснел.
--rollback CREATE OR REPLACE FUNCTION ${tenant.schema}.customer_balance_apply() RETURNS trigger LANGUAGE plpgsql AS $fn$ BEGIN UPDATE ${tenant.schema}.customer SET balance = balance + NEW.amount WHERE id = NEW.customer_id; RETURN NEW; END $fn$;
--rollback CREATE TRIGGER customer_balance_apply_trg AFTER INSERT ON ${tenant.schema}.customer_account_entry FOR EACH ROW EXECUTE FUNCTION ${tenant.schema}.customer_balance_apply();

--changeset platform:tenant-045-drop-customer-balance-column
--comment Колонку убираем следом. Оставленная без триггера, она замерла бы
--comment на случайном значении и стала бы ловушкой для следующего, кто
--comment решит, что остаток лежит здесь.
--comment
--comment Данные при этом не теряются: остаток и так выводится из журнала,
--comment а журнал неизменяем.
ALTER TABLE ${tenant.schema}.customer DROP COLUMN IF EXISTS balance;
--rollback ALTER TABLE ${tenant.schema}.customer ADD COLUMN balance numeric(14,2) NOT NULL DEFAULT 0;

--changeset platform:tenant-045-drop-customer-reserved-amount
--comment Соседка по той же миграции и с той же судьбой: reserved_amount
--comment не пишет и не читает никто — ни триггер, ни код, ни вьюха. Ноль
--comment во всех строках, который выглядит как «у клиента ничего не отложено»
--comment и однажды будет так прочитан. Отложенное считается по сделкам
--comment в статусе RESERVED.
ALTER TABLE ${tenant.schema}.customer DROP COLUMN IF EXISTS reserved_amount;
--rollback ALTER TABLE ${tenant.schema}.customer ADD COLUMN reserved_amount numeric(14,2) NOT NULL DEFAULT 0;
