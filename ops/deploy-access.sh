#!/usr/bin/env bash
# Доступ кнопки выкладки к стенду: ключ, отпечаток хоста, окружение GitHub.
#
#   ops/deploy-access.sh ключ prom
#   ops/deploy-access.sh стенд prom --host 1.2.3.4 --отпечаток SHA256:…
#   ops/deploy-access.sh секрет prom DEPLOY_SMOKE      # значение со stdin
#   ops/deploy-access.sh проверить prom
#   ops/deploy-access.sh --selftest
#
# Зачем он есть. До него доступ к стенду заводился руками: сгенерировать
# ключ, вспомнить про `restrict`, не забыть отпечаток хоста, разложить шесть
# секретов и три переменные по окружению в браузере — и всё это знал ровно
# один человек. Такой порядок не переживает ни отпуска, ни второго стенда,
# ни тестировщика, которому нужен свой ИФТ: «выложить может только владелец»
# — это не безопасность, а единственная точка отказа. Шаги здесь названы
# командами, значит их может повторить любой, у кого есть права на репозиторий
# и доступ к машине.
#
# ЧТО РЕШЕНО ЗА ВЛАДЕЛЬЦА (исполнитель, задача 0140 — сказать вслух):
#
# 1. Ключ СВОЙ НА КАЖДЫЙ СТЕНД, а не один на все три. Общий ключ означает,
#    что нажатие на ИФТ технически может дойти до ПРОМ: разделение стендов
#    держалось бы тогда на одном лишь тексте workflow. Ключей три, они
#    независимы, и отозвать ключ ИФТ можно, не трогая боевую ячейку.
#
# 2. `restrict` в authorized_keys, но БЕЗ принудительной команды. `restrict`
#    снимает проброс портов, агента, X11 и pty — всё, чего выкладке не нужно.
#    Принудительная команда (`command="…"`) была бы строже, но выкладка
#    запускает не одну команду, а несколько разных (rsync настроек, чтение
#    .env, ops/deploy.sh), — значит понадобился бы диспетчер-обёртка, то есть
#    НОВЫЙ КОД НА ПУТИ ВЫКЛАДКИ, который надо держать правдивым и который
#    сам станет местом отказа. Цена решения названа: ключ, утёкший из GitHub,
#    даёт оболочку на машине, а не только выкладку.
#
# 3. Отпечаток хоста СВЕРЯЕТСЯ, а не берётся молча. `ssh-keyscan` отвечает
#    тем, что ответил адрес, — то есть при перехвате адреса он честно принесёт
#    ключ перехватчика. Поэтому скрипт требует `--отпечаток`, прочитанный
#    на САМОЙ машине, и отказывается, если снятое не совпало. Без этого
#    `DEPLOY_SSH_KNOWN_HOSTS` был бы обрядом, а не проверкой.
#
# 4. Закрытая часть ключа НЕ ОСТАЁТСЯ в репозитории и не печатается. Она
#    живёт в ~/.parts-deploy-keys/<стенд> с правами 600 и уезжает в секрет
#    потоком из файла. Команда `забыть` стирает её, когда доступ настроен.
#
# Имена переменных латиницей не по вкусу, а по необходимости: bash не
# допускает кириллицу в именах и читает `стенд=prom` как команду «стенд».
set -euo pipefail

SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

# Точки подмены — только для самопроверки: ей нужно прогнать скрипт, не
# трогая ни настоящих ключей, ни настоящего репозитория, ни сети.
HOME_DIR="${DEPLOY_ACCESS_HOME:-$HOME}"
KEYSCAN="${DEPLOY_ACCESS_KEYSCAN:-ssh-keyscan}"
GH="${DEPLOY_ACCESS_GH:-gh}"
KEYS="$HOME_DIR/.parts-deploy-keys"

red()   { printf '\033[1;31m%s\033[0m\n' "$1" >&2; }
green() { printf '\033[1;32m%s\033[0m\n' "$1"; }
step()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

fail() { red "$1"; exit 1; }

check_stand() {
  case "$1" in
    ift|psi|prom) ;;
    *) fail "Стенд «${1}» не из трёх: ift, psi, prom." ;;
  esac
}

repo_name() {
  "$GH" repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null \
    || fail "Не понял, какой это репозиторий. Запускать из рабочей копии, войдя в gh."
}

# ── ключ ──────────────────────────────────────────────────────────────────
# Генерирует пару для одного стенда и печатает ровно ту строку, которую
# человек добавит на машине. Повторный запуск НЕ перетирает ключ молча:
# перетёртый ключ означает мгновенно сломанную выкладку этого стенда.
cmd_key() {
  local stand="$1"; check_stand "$stand"
  local file="$KEYS/$stand"

  mkdir -p "$KEYS"; chmod 700 "$KEYS"
  if [ -e "$file" ]; then
    fail "Ключ стенда «${stand}» уже есть: $file
Перевыпуск ломает выкладку этого стенда до момента, пока новый открытый ключ
не окажется на машине. Если это и нужно — уберите файл руками и повторите."
  fi

  ssh-keygen -t ed25519 -N '' -q -C "выкладка-$stand" -f "$file"
  chmod 600 "$file"

  step "1. Эту строку выполнить НА МАШИНЕ стенда «${stand}», под тем пользователем, которым ходит выкладка:"
  printf '\n'
  printf "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '%s' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys\n" \
    "restrict $(cat "$file.pub")"
  printf '\n'
  step "2. Там же прочитать отпечаток хоста — его мы будем сверять, а не принимать на веру:"
  printf '\nssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub\n\n'
  step "3. Вернуться сюда и выполнить (подставив адрес и прочитанный отпечаток):"
  printf '\nops/deploy-access.sh стенд %s --host АДРЕС --отпечаток SHA256:…\n\n' "$stand"
  green "Закрытая часть лежит в $file и никуда не печатается."
}

# ── стенд ─────────────────────────────────────────────────────────────────
# Заводит окружение, сверяет отпечаток и раскладывает секреты с переменными.
cmd_stand() {
  local stand="$1"; shift; check_stand "$stand"
  local host='' user='deploy' port='22' path='parts-platform' url='' fp='' reviewer=''

  while [ $# -gt 0 ]; do
    case "$1" in
      --host)           host="$2"; shift 2 ;;
      --user)           user="$2"; shift 2 ;;
      --port)           port="$2"; shift 2 ;;
      --path)           path="$2"; shift 2 ;;
      --url)            url="$2"; shift 2 ;;
      --отпечаток)      fp="$2"; shift 2 ;;
      --подтверждающий) reviewer="$2"; shift 2 ;;
      *) fail "Не знаю ключа «${1}»." ;;
    esac
  done

  [ -n "$host" ] || fail "Не задан --host: адрес машины стенда."
  local file="$KEYS/$stand"
  [ -f "$file" ] || fail "Нет ключа стенда «${stand}». Сначала: ops/deploy-access.sh ключ $stand"

  # Отпечаток обязателен ВЕЗДЕ, а не только на ПРОМ. Стенд, на который можно
  # въехать перехватом адреса, отдаёт ключ, которым ходят и на соседние.
  [ -n "$fp" ] || fail "Не задан --отпечаток.
Прочитайте его НА САМОЙ МАШИНЕ (ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub)
и передайте сюда. Снятое с сети сверяется с ним: ssh-keyscan принесёт ключ
того, кто ответил по адресу, — при перехвате это будет ключ перехватчика."

  step "Снимаю ключ хоста $host:$port и сверяю с тем, что вы прочитали на машине"
  local scanned
  scanned="$("$KEYSCAN" -p "$port" "$host" 2>/dev/null || true)"
  [ -n "$scanned" ] || fail "Хост $host:$port не ответил ни одним ключом."

  local prints
  prints="$(printf '%s\n' "$scanned" | ssh-keygen -lf - 2>/dev/null | awk '{print $2}')"
  if ! printf '%s\n' "$prints" | grep -qxF "$fp"; then
    fail "Отпечаток НЕ СОВПАЛ.
Вы назвали: $fp
Адрес отдал: $(printf '%s' "$prints" | tr '\n' ' ')
Это либо опечатка, либо по адресу отвечает не та машина. Секреты не заливаю."
  fi
  green "Отпечаток совпал."

  local R; R="$(repo_name)"

  step "Завожу окружение «${stand}»"
  if [ "$stand" = prom ]; then
    # ПРОМ без подтверждающего — это кнопка, которой живые данные клиента
    # выкладывает любой, у кого есть доступ к Actions. Логин по умолчанию —
    # владелец репозитория.
    local who id
    who="${reviewer:-$("$GH" repo view --json owner --jq .owner.login)}"
    id="$("$GH" api "users/$who" --jq .id)"
    printf '{"reviewers":[{"type":"User","id":%s}],"deployment_branch_policy":null}' "$id" \
      | "$GH" api -X PUT "repos/$R/environments/$stand" --input - >/dev/null
    green "Подтверждающий: $who"
  else
    "$GH" api -X PUT "repos/$R/environments/$stand" >/dev/null
  fi

  step "Раскладываю секреты и переменные"
  "$GH" secret set DEPLOY_SSH_KEY  --env "$stand" --repo "$R" < "$file"
  printf '%s' "$host"    | "$GH" secret set DEPLOY_SSH_HOST --env "$stand" --repo "$R"
  printf '%s' "$user"    | "$GH" secret set DEPLOY_SSH_USER --env "$stand" --repo "$R"
  printf '%s\n' "$scanned" | "$GH" secret set DEPLOY_SSH_KNOWN_HOSTS --env "$stand" --repo "$R"
  "$GH" variable set DEPLOY_SSH_PORT --env "$stand" --repo "$R" --body "$port"
  "$GH" variable set DEPLOY_PATH     --env "$stand" --repo "$R" --body "$path"
  # Через `if`, а не `[ -n … ] && …`: при незаданном адресе такая строка
  # отдаёт ненулевой код, и `set -e` валит скрипт ПОСЛЕ того, как все секреты
  # уже залиты, — доступ настроен, а команда «упала», и человек идёт
  # перезаливать. Поймано самопроверкой, а не чтением.
  if [ -n "$url" ]; then
    "$GH" variable set STAND_URL --env "$stand" --repo "$R" --body "$url"
  fi

  green "Готово. Осталось два секрета, которые знает только человек:"
  printf '  ops/deploy-access.sh секрет %s DEPLOY_FEED_URLS   # ссылки на прайсы через пробел, либо слово «нет»\n' "$stand"
  printf '  ops/deploy-access.sh секрет %s DEPLOY_SMOKE       # три строки: код компании, логин продавца, пароль\n' "$stand"
  printf '  ops/deploy-access.sh проверить %s\n' "$stand"
}

# ── секрет ────────────────────────────────────────────────────────────────
# Значение читается со stdin и НЕ принимается аргументом: аргументы видны
# в `ps` любому на машине и оседают в истории оболочки. Тем же доводом живут
# ops/migrate-tenants.sh и шаги кнопки, кладущие .deploy-feeds и .deploy-smoke.
cmd_secret() {
  local stand="$1" name="$2"; check_stand "$stand"
  shift 2
  [ $# -eq 0 ] || fail "Значение секрета аргументом не принимается — его видно в «ps» и в истории команд.
Наберите:  ops/deploy-access.sh секрет $stand $name
и введите значение, закончив Ctrl-D."
  case "$name" in
    DEPLOY_FEED_URLS|DEPLOY_SMOKE|DEPLOY_SSH_KEY|DEPLOY_SSH_HOST|DEPLOY_SSH_USER|DEPLOY_SSH_KNOWN_HOSTS) ;;
    *) fail "Секрета «${name}» кнопка не читает. Список — docs/deploy-button.md." ;;
  esac
  local R; R="$(repo_name)"
  "$GH" secret set "$name" --env "$stand" --repo "$R"
  green "Секрет $name окружения «${stand}» записан."
}

# ── проверить ─────────────────────────────────────────────────────────────
# Отвечает на вопрос «доедет ли кнопка»: перечисляет, чего не хватает,
# и отказывает, если не хватает хоть чего-нибудь.
cmd_check() {
  local stand="$1"; check_stand "$stand"
  local R; R="$(repo_name)"
  local bad=0

  step "Окружение «${stand}» в $R"
  local have name
  have="$("$GH" api "repos/$R/environments/$stand/secrets" --jq '.secrets[].name' 2>/dev/null || true)"
  for name in DEPLOY_SSH_KEY DEPLOY_SSH_HOST DEPLOY_SSH_USER DEPLOY_SSH_KNOWN_HOSTS DEPLOY_FEED_URLS DEPLOY_SMOKE; do
    if printf '%s\n' "$have" | grep -qxF "$name"; then
      printf '  ✓ %s\n' "$name"
    else
      printf '  ✗ %s — не задан\n' "$name"; bad=1
    fi
  done

  local vars
  vars="$("$GH" api "repos/$R/environments/$stand/variables" --jq '.variables[]|"\(.name)=\(.value)"' 2>/dev/null || true)"
  printf '  переменные: %s\n' "${vars:-нет ни одной}"

  if [ "$stand" = prom ]; then
    local revs
    revs="$("$GH" api "repos/$R/environments/$stand" \
      --jq '[.protection_rules[]?|select(.type=="required_reviewers")|.reviewers[]?.reviewer.login]|join(", ")' 2>/dev/null || true)"
    if [ -n "$revs" ]; then printf '  ✓ подтверждают: %s\n' "$revs"
    else printf '  ✗ подтверждающего нет — на ПРОМ выложит любой\n'; bad=1; fi
  fi

  # Вход ключом отсюда не проверяется, и это сказано вслух, а не скрыто:
  # адрес машины лежит СЕКРЕТОМ, прочитать его через API нельзя, а врать
  # «доступ есть» на непроверенном входе хуже, чем не проверять вовсе.
  local file="$KEYS/$stand"
  if [ -f "$file" ]; then
    step "Вход проверьте сами — адрес лежит секретом и мне не виден:"
    printf '  ssh -i %s -o BatchMode=yes <пользователь>@<адрес> "echo доступ есть"\n' "$file"
  else
    printf '\n  закрытого ключа стенда здесь нет (%s) — это нормально, если доступ заводили с другой машины\n' "$file"
  fi

  [ "$bad" = 0 ] || fail "Кнопка на «${stand}» сейчас не доедет — смотрите отметки ✗ выше."
  green "Всё на месте."
}

# ── забыть ────────────────────────────────────────────────────────────────
cmd_forget() {
  local stand="$1"; check_stand "$stand"
  local file="$KEYS/$stand"
  [ -f "$file" ] || fail "Закрытого ключа стенда «${stand}» здесь и нет."
  rm -f "$file" "$file.pub"
  green "Закрытый ключ стенда «${stand}» стёрт с этой машины. В GitHub он остался — выкладка работает."
}

# ── самопроверка ──────────────────────────────────────────────────────────
# Проверяется то, что нельзя установить чтением: отказы. Скрипт, который
# не отказывает на подделке, хуже отсутствующего — он выдаёт настроенный
# доступ там, где доступа нет.
selftest() {
  local base; base="$(mktemp -d)"
  local bad=0
  probe() {
    local title="$1"; shift
    if "$@" >/dev/null 2>&1; then
      red "  ✗ $title — прошло, а должно было отказать"; bad=$((bad+1))
    else
      printf '  ✓ %s\n' "$title"
    fi
  }

  printf 'Самопроверка ops/deploy-access.sh\n'

  probe "стенд не из трёх — отказ" env DEPLOY_ACCESS_HOME="$base" "$SELF" ключ бухгалтерия
  probe "незнакомая команда — отказ" env DEPLOY_ACCESS_HOME="$base" "$SELF" разложить-всё

  env DEPLOY_ACCESS_HOME="$base" "$SELF" ключ ift >/dev/null 2>&1 || true
  if [ -f "$base/.parts-deploy-keys/ift" ]; then
    printf '  ✓ ключ заведён\n'
    # Порядок не произволен: на Linux `stat -f` — это НЕ формат, а
    # `--file-system`. Он печатает сведения о файловой системе и выходит
    # НУЛ�ём, то есть `||` не срабатывает, и в mode попадает простыня про
    # блоки и иноды — проверка сравнивает её с «600» и краснеет на исправных
    # правах. Сначала GNU (`-c`), потом BSD (`-f`): на macOS `-c` неизвестен
    # и отдаёт ненулевой код, значит откат сработает. Поймано красным CI —
    # у себя на macOS проверка была зелёной.
    local mode
    mode="$(stat -c '%a' "$base/.parts-deploy-keys/ift" 2>/dev/null \
         || stat -f '%Lp' "$base/.parts-deploy-keys/ift" 2>/dev/null)"
    if [ "$mode" = 600 ]; then printf '  ✓ закрытая часть 600\n'
    else red "  ✗ закрытая часть $mode, а должна быть 600"; bad=$((bad+1)); fi
    # Не конвейером в `grep -q`: тот закрывает поток на первом совпадении,
    # печатающая сторона получает EPIPE, и `pipefail` объявляет отказом
    # как раз успешный случай — первая же редакция этой пробы так и покраснела.
    local printed
    printed="$(env DEPLOY_ACCESS_HOME="$base" "$SELF" ключ psi 2>/dev/null || true)"
    case "$printed" in
      *"restrict ssh-ed25519 "*) printf '  ✓ строка authorized_keys несёт restrict\n' ;;
      *) red "  ✗ в напечатанной строке authorized_keys нет restrict"; bad=$((bad+1)) ;;
    esac
  else
    red "  ✗ ключ не завёлся"; bad=$((bad+1))
  fi

  probe "повторный выпуск не перетирает ключ молча" \
    env DEPLOY_ACCESS_HOME="$base" "$SELF" ключ ift

  probe "стенд без --отпечаток — отказ" \
    env DEPLOY_ACCESS_HOME="$base" "$SELF" стенд ift --host 192.0.2.1

  # Подменяем keyscan: пусть отвечает ключом, отпечаток которого заведомо
  # не тот, что назвал человек. Заливки быть не должно.
  local fake="$base/keyscan"
  {
    printf '#!/bin/sh\n'
    printf 'sed "s|^|192.0.2.1 |" "%s/.parts-deploy-keys/ift.pub"\n' "$base"
  } > "$fake"; chmod +x "$fake"
  probe "чужой отпечаток — отказ, секреты не заливаются" \
    env DEPLOY_ACCESS_HOME="$base" DEPLOY_ACCESS_KEYSCAN="$fake" \
        "$SELF" стенд ift --host 192.0.2.1 --отпечаток 'SHA256:заведомо-не-тот'

  # А на СОВПАВШЕМ отпечатке дело обязано дойти до заливки — иначе сторож
  # отказывает всегда и ничего не проверяет. Подменяем и gh.
  local fakegh="$base/gh"
  printf '#!/bin/sh\necho "ВЫЗВАН: $*" >> "%s/gh.log"\nexit 0\n' "$base" > "$fakegh"; chmod +x "$fakegh"
  local real_fp
  real_fp="$(ssh-keygen -lf "$base/.parts-deploy-keys/ift.pub" | awk '{print $2}')"
  if env DEPLOY_ACCESS_HOME="$base" DEPLOY_ACCESS_KEYSCAN="$fake" DEPLOY_ACCESS_GH="$fakegh" \
       "$SELF" стенд ift --host 192.0.2.1 --отпечаток "$real_fp" >/dev/null 2>&1 \
     && grep -q 'secret set DEPLOY_SSH_KEY' "$base/gh.log"; then
    printf '  ✓ совпавший отпечаток — секреты заливаются\n'
  else
    red "  ✗ на совпавшем отпечатке заливка не дошла: сторож отказывает всегда"; bad=$((bad+1))
  fi

  probe "значение секрета аргументом не принимается" \
    env DEPLOY_ACCESS_HOME="$base" "$SELF" секрет ift DEPLOY_SMOKE пароль123

  probe "незнакомый секрет — отказ" \
    env DEPLOY_ACCESS_HOME="$base" "$SELF" секрет ift ЧУЖОЙ_СЕКРЕТ

  rm -rf "$base"
  if [ "$bad" = 0 ]; then green "Самопроверка пройдена."; else fail "Самопроверка провалена: $bad."; fi
}

what="${1:---help}"
case "$what" in
  --selftest) selftest ;;
  ключ)      shift; [ $# -ge 1 ] || fail "Какой стенд? ops/deploy-access.sh ключ prom"; cmd_key "$@" ;;
  стенд)     shift; [ $# -ge 1 ] || fail "Какой стенд?"; cmd_stand "$@" ;;
  секрет)    shift; [ $# -ge 2 ] || fail "ops/deploy-access.sh секрет <стенд> <ИМЯ>"; cmd_secret "$@" ;;
  проверить) shift; [ $# -ge 1 ] || fail "Какой стенд?"; cmd_check "$@" ;;
  забыть)    shift; [ $# -ge 1 ] || fail "Какой стенд?"; cmd_forget "$@" ;;
  --help|-h) sed -n '2,9p' "$SELF" ;;
  *) fail "Не знаю команды «${what}». Есть: ключ, стенд, секрет, проверить, забыть, --selftest." ;;
esac
