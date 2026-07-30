# Сборка фронтенда.
#
# Отдельным этапом, а не рядом с бэкендом: node нужен только здесь, и тащить
# его в рабочий образ незачем.
FROM node:22-alpine AS frontend
WORKDIR /frontend

# Зависимости отдельным слоем: package.json меняется редко, исходники — каждый
# коммит, и без этого разделения npm ci перезапускается на каждой сборке.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build


# Сборка бэкенда.
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build

COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./
# Прогреваем локальный репозиторий тем же слоем, что и pom: иначе каждая правка
# кода тянет полсотни мегабайт зависимостей заново.
RUN mvn -B -ntp dependency:go-offline

COPY src src
COPY db db

# PWA собирается в статику приложения: в бою фронтенд и API живут на одном
# домене — сессия в cookie и CSRF-токен из cookie иначе не работают.
COPY --from=frontend /frontend/dist src/main/resources/static

# Тесты здесь не гоняются: они поднимают Postgres через Testcontainers,
# то есть требуют доступа к докеру изнутри сборки образа. Их место в CI,
# и там они уже есть.
RUN mvn -B -ntp -DskipTests package


FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Не от root: приложению не нужны права ни на что, кроме своего каталога.
RUN addgroup -S partsflow && adduser -S partsflow -G partsflow
USER partsflow

COPY --from=backend /build/target/*.jar app.jar

EXPOSE 8080

# Проверка живости: балансировщик и docker compose ждут её до выпуска трафика.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"'

# MaxRAMPercentage вместо фиксированного Xmx: предел памяти задаётся
# контейнеру, и JVM обязана считать кучу от него, а не от памяти хоста —
# иначе она увидит всю машину и её убьёт OOM killer.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
