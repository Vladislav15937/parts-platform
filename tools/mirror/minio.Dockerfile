# syntax=docker/dockerfile:1
#
# Образ хранилища для нашего зеркала: официальный бинарник MinIO в alpine.
#
# ПОЧЕМУ СОБИРАЕМ, А НЕ ПЕРЕКЛАДЫВАЕМ ЧУЖОЙ ОБРАЗ. 26 сентября 2026 образ
# MinIO пропал изо всех реестров, до которых мы дотягиваемся: на `quay.io`
# нет репозитория (не тега — репозитория), Docker Hub отвечает `denied`,
# `ghcr.io/minio/minio` не существует, `dl.min.io` отдаёт 410 на все версии,
# включая нашу. Живым остался ровно один официальный источник — файлы выпуска
# на GitHub (`minio.linux-amd64.<ВЕРСИЯ>` и рядом `.sha256sum`), и они
# проверены: сумма сходится. Значит зеркалим не образ, а сам выпуск,
# а версия остаётся той же, что работает в бою.
#
# Перекладывание образа не подходило ещё по одной причине, и она решающая:
# у разработчика в кэше лежит только `linux/arm64`, а CI и ячейка — amd64.
# Из кэша получилось бы зеркало, которое не работает там, где нужно.
#
# НИ ОДНОГО `RUN`. Слой собирается только `COPY`, поэтому чужая архитектура
# собирается без эмуляции — amd64 и arm64 одним `buildx` на любой машине.
# Права ставит `--chmod`, а не `RUN chmod`, ровно поэтому.
#
# alpine, а не distroless: `ops/restore-cell.sh` ходит внутрь `sh -c`,
# а `docs/deployment.md` — `exec minio minio --version`. Образ без оболочки
# сломал бы и то, и другое.
FROM alpine:3.22

ARG VERSION
LABEL org.opencontainers.image.title="minio (зеркало parts-platform)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/Vladislav15937/parts-platform" \
      org.opencontainers.image.description="Официальный бинарник выпуска MinIO ${VERSION}, сверенный по sha256 и положенный в наш реестр. Зеркало заведено 26.09.2026: официальный образ исчез изо всех доступных реестров."

# Бинарник кладёт сюда tools/mirror-images.sh, сверив sha256 с опубликованной
# суммой выпуска. `TARGETARCH` подставляет buildx — по нему и выбирается файл.
ARG TARGETARCH
COPY --chmod=0755 minio-${TARGETARCH} /usr/bin/minio

# Официальный образ звал бинарник своей точкой входа, а compose передавал ему
# `server /data …` аргументами. Здесь то же самое: аргументы дописываются
# к самому бинарнику, и ни одна команда в compose и в тестах не меняется.
ENTRYPOINT ["/usr/bin/minio"]
