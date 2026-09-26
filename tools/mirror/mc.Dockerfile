# syntax=docker/dockerfile:1
#
# Клиент хранилища для нашего зеркала: официальный бинарник `mc` в alpine.
# Почему собираем из выпуска, почему без `RUN` и почему alpine — всё то же,
# что и у minio.Dockerfile рядом; там это расписано один раз.
#
# Оболочка здесь нужна не «на всякий случай»: `ops/restore-cell.sh` зовёт
# `docker compose exec -T mc sh -c "mc mirror …"`. Образ без `sh` сломал бы
# возврат снимков — то есть выяснилось бы это в аварию.
FROM alpine:3.22

ARG VERSION
LABEL org.opencontainers.image.title="mc (зеркало parts-platform)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/Vladislav15937/parts-platform" \
      org.opencontainers.image.description="Официальный бинарник выпуска MinIO Client ${VERSION}, сверенный по sha256 и положенный в наш реестр."

ARG TARGETARCH
COPY --chmod=0755 mc-${TARGETARCH} /usr/bin/mc

# Боевой compose задаёт `entrypoint: ["mc"]` и `command: ["--version"]` —
# то есть зовёт клиента по имени, из PATH. `/usr/bin` в PATH есть, и эти
# строки не меняются. Здесь точка входа продублирована, чтобы образ,
# запущенный без compose, тоже вёл себя как клиент, а не как alpine.
ENTRYPOINT ["/usr/bin/mc"]
CMD ["--version"]
