# parts-platform

Учётная система для авторазборок и продавцов б/у автозапчастей.
Мультиарендная SaaS: приёмка, склад, продажи, выгрузка объявлений на Авито и Дром.

## Запуск

Нужны JDK 21, Maven и Docker.

```bash
docker compose up -d          # postgres, kafka, minio

cd db && ./verify.sh && cd .. # накатить миграции и проверить инварианты
mvn spring-boot:run           # приложение на :8080
```

Профиль по умолчанию `local`: транспорт событий in-memory, Kafka не нужна.
Для реального брокера — `mvn spring-boot:run -Dspring-boot.run.profiles=kafka`.

Проверка:

```bash
curl -X POST localhost:8080/api/parts \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: 42' \
  -d '{"categoryId":1,"title":"Фара левая Camry V50","price":8500,"quantity":1}'

curl 'localhost:8080/api/parts/search?q=фара' -H 'X-Tenant-Id: 42'
```

Заголовок `X-Tenant-Id` — временная заглушка до аутентификации. Убрать при
внедрении Spring Security, иначе любой сможет читать чужой склад.

## Тесты

```bash
mvn test
```

`TenantIsolationTest` требует Docker (Testcontainers) и проверяет, что данные
арендаторов не пересекаются при работе через общий пул соединений. Это
сторожевой тест самой опасной ошибки архитектуры — не отключай его.

## Что внутри

```
src/main/java/ru/partsflow/
├── platform/tenant/   маршрутизация по схемам арендаторов
├── platform/outbox/   Outbox, релей, транспорт событий
├── inventory/         запчасти, склад, движения
├── publishing/        выгрузки: Авито (pull-фид), Дром (pull-фид + API-дельты)
db/                    миграции Liquibase, см. db/README.md
docs/                  архитектура и доменная модель
CLAUDE.md              контекст для продолжения в Claude Code
```

## Документация

- `docs/architecture-and-plan.md` — архитектура, обоснования, план на 6 месяцев
- `docs/domain-model.md` — предметная область и решения по модели данных
- `docs/drom-integration.md` — как на самом деле устроен обмен с Дромом
- `docs/bazon-parity.md` — карта функционала Bazon и список пробелов
- `db/README.md` — соглашения по миграциям
- `CLAUDE.md` — ловушки, на которых проект сломается; прочти перед правками
