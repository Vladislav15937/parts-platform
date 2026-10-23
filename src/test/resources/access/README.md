# Эталон прежних проходимостей (задача 0044)

`legacy-access-b07449e9.tsv` — для каждого пути кабинета (`/api/…`, «МЕТОД шаблон»)
какие из семи прежних ролей (`OWNER`, `MANAGER`, `STOREKEEPER`, `SELLER`, `VIEWER`,
`AUDITOR`, `ADMIN`) его проходили **прежним кодом**: `SecurityConfig` (правила путей,
запрет изменяющих методов, `ACCESS_CONTROL`) и `@PreAuthorize` по контроллерам на
коммите `b07449e9` — первом родителе слияния `8dfaccde`, то есть `main` до PR 506.

Читает его `LegacyAccessAfterDeployTest`: сотрудники, заведённые до наката
`tenant/100`–`101`, обязаны проходить ровно то же.

## Как снят

Тем же классом, которым спрашивается нынешнее приложение, —
`src/test/java/ru/partsflow/platform/security/AccessMatrix.java`, скопированным в прежний
код без правки. Рядом с ним в прежнем коде лежал снимающий тест
`LegacyAccessBaselineDump.java.txt` (здесь — его текст; в нынешнем коде он
не компилируется: у прежнего `TenantPrincipal` семь полей). Вошедший каждой роли —
прежний `TenantPrincipal` с несуществующим номером сотрудника, так что правило «своё»
(`#id == principal.memberId`) при пустых аргументах не срабатывает ни у кого — так же,
как в проверке.

## Как повторить

```bash
git worktree add --detach ../old-b07449e9 b07449e9
cp src/test/java/ru/partsflow/platform/security/AccessMatrix.java \
   ../old-b07449e9/src/test/java/ru/partsflow/platform/security/
cp src/test/resources/access/LegacyAccessBaselineDump.java.txt \
   ../old-b07449e9/src/test/java/ru/partsflow/platform/security/LegacyAccessBaselineDump.java
cd ../old-b07449e9 && ./mvnw -o -q test -Dtest=LegacyAccessBaselineDump \
   -Dsurefire.failIfNoSpecifiedTests=false -Dbaseline.out=/tmp/legacy-access.tsv
```

Вывод — строки таблицы без заголовка; 220 путей, ни одного `error`.
Руками таблицу не править: снятый путь, сменившийся шаблон или решение владельца
о новом наборе ролей записываются в тесте (`DECIDED_CHANGES` с решением), а эталон
остаётся снимком прежнего кода.
