# Codex: frontend

Общие инструкции — корневой `AGENTS.md`; перевод механизмов — `docs/codex.md`.
Перед изменениями здесь прочитай `frontend/CLAUDE.md` целиком.
Правила экранов: прочитай `docs/frontend-rules.md`. Проверки из `frontend/`: `npm test`, `npm run build`.

Из корня: `python3 tools/codex-context.py frontend/<файл>`.
Если запуск сделан из другого cwd, сначала найди корень через `git rev-parse --show-toplevel`; пути документов указаны от него.
