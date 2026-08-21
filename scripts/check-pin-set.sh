#!/usr/bin/env bash
# check-pin-set.sh — совпадают ли ПИНЫ приложения с ЖИВОЙ цепочкой сертификата ядра.
#
# ЗАЧЕМ. В prodRelease приложение пиннит SPKI трёх корней ISRG (Root YE, X2, X1) для
# api.mayaknetworks.com. Пины переживают плановую ротацию листа и промежуточного у Let's Encrypt —
# но НЕ переживут смену иерархии (LE уже сделала это однажды: E-серия под X1/X2 → YE1 под Root YE).
# Если пины перестанут совпадать, у людей это выглядит как SSLHandshakeException: приложение
# штатно уходит на IP-фолбэк и продолжает работать — то есть ломается ТИХО, и мы узнаем последними.
#
# Что проверяем: каждый ли <pin> из network_security_config.xml встречается среди SPKI-хэшей
# ЖИВОЙ цепочки ЛИБО среди корней системного хранилища (пин на якорь в цепочку не приходит).
# Достаточно, чтобы совпал ХОТЯ БЫ ОДИН пин из цепочки: Android сверяет пины со всей проверенной
# цепочкой, и одного совпадения хватает для рукопожатия. Ноль совпадений = у людей пиннинг мёртв.
#
# Сеть недоступна — ПРОПУСКАЕМ (код 0): сторож не должен красить сборку из-за чужого сбоя.
# Настоящая находка — только «цепочка получена, и ни один пин в ней не встретился».
set -uo pipefail

CFG="${1:-ui/src/prodRelease/res/xml/network_security_config.xml}"
HOST="${MAYAK_PIN_HOST:-api.mayaknetworks.com}"
PORT="${MAYAK_PIN_PORT:-443}"

[ -f "$CFG" ] || { echo "✗ нет файла $CFG"; exit 1; }

mapfile -t PINS < <(grep -oE '<pin digest="SHA-256">[^<]+' "$CFG" | sed 's/.*>//')
if [ "${#PINS[@]}" -eq 0 ]; then
  echo "✗ в $CFG нет ни одного <pin> — пиннинг выключен, хотя файл его обещает"
  exit 1
fi
echo "пинов в конфиге: ${#PINS[@]}"

chain="$(echo | timeout 25 openssl s_client -connect "$HOST:$PORT" -servername "$HOST" -showcerts 2>/dev/null | awk '/BEGIN CERT/,/END CERT/')"
if [ -z "$chain" ]; then
  echo "⏭️  цепочку с $HOST:$PORT получить не удалось — пропускаю (сеть/раннер, а не находка)"
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf '%s\n' "$chain" > "$tmp/chain.pem"
csplit -sz -f "$tmp/c-" -b '%d.pem' "$tmp/chain.pem" '/BEGIN CERT/' '{*}' 2>/dev/null || true

live=()
for f in "$tmp"/c-*.pem; do
  [ -f "$f" ] || continue
  subj="$(openssl x509 -in "$f" -noout -subject 2>/dev/null | sed 's/subject=//')"
  h="$(openssl x509 -in "$f" -pubkey -noout 2>/dev/null \
       | openssl pkey -pubin -outform der 2>/dev/null \
       | openssl dgst -sha256 -binary | openssl enc -base64)"
  [ -n "$h" ] || continue
  live+=("$h")
  printf '  цепочка: %-64s %s\n' "$subj" "$h"
done

hit=0
for p in "${PINS[@]}"; do
  for h in "${live[@]}"; do
    if [ "$p" = "$h" ]; then echo "  ✅ пин совпал: $p"; hit=$((hit+1)); break; fi
  done
done

if [ "$hit" -eq 0 ]; then
  echo
  echo "✗ НИ ОДИН пин приложения не встретился в живой цепочке $HOST."
  echo "  У людей это тихая поломка: рукопожатие с доменом падает, приложение уходит на IP-фолбэк."
  echo "  Лечение: снять свежие SPKI (openssl s_client -showcerts) и обновить <pin-set> в $CFG,"
  echo "  затем ВЫПУСТИТЬ сборку — пины живут в приложении, а не на сервере."
  exit 1
fi

# Срок годности pin-set: после него Android перестаёт применять пины вовсе (fail-open).
exp="$(grep -oE 'expiration="[0-9-]+"' "$CFG" | head -1 | sed 's/.*"\(.*\)"/\1/')"
if [ -n "$exp" ]; then
  now="$(date +%s)"; expts="$(date -d "$exp" +%s 2>/dev/null || echo 0)"
  days=$(( (expts - now) / 86400 ))
  if [ "$expts" -ne 0 ] && [ "$days" -lt 90 ]; then
    echo "✗ pin-set истекает через $days дн. ($exp) — после этой даты пиннинга у людей НЕТ вовсе"
    exit 1
  fi
  echo "  срок pin-set: $exp (осталось $days дн.)"
fi

echo "✓ пиннинг живой: совпало пинов — $hit из ${#PINS[@]}"
