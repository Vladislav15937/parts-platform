# Codex: db

Общие инструкции — корневой `AGENTS.md`; перевод механизмов — `docs/codex.md`.
Перед изменениями здесь прочитай `db/CLAUDE.md` целиком.
Только роль migrator меняет `db/changelog`, строго по одному. Применённые changeset неизменяемы целиком. Проверка: `./db/verify.sh`.

Из корня: `python3 tools/codex-context.py db/<файл>`.
Если запуск сделан из другого cwd, сначала найди корень через `git rev-parse --show-toplevel`; пути документов указаны от него.
