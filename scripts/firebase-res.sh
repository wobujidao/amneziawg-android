#!/usr/bin/env bash
# Кладёт конфигурацию Firebase в БОЕВОЙ вариант сборки строковыми ресурсами.
#
# Что это и зачем именно так, а не плагином `com.google.gms.google-services`:
#
#  1) 🔑 КЛЮЧ В ПУБЛИЧНОМ РЕПОЗИТОРИИ. `google-services.json` несёт значение вида AIzaSy… , а форк
#     приложения лежит на GitHub открыто. Такой коммит поднимает сканеры секретов (у нас это уже
#     было — GitGuardian ловит ФОРМУ записи, а не содержимое). Поэтому файл живёт только на kz-serv
#     в ~/.mayak-secrets/firebase/, а сгенерированный xml — в .gitignore. В git попадает ЭТОТ скрипт.
#
#  2) 🧨 ПЛАГИН ПАДАЕТ НА НАШИХ ВАРИАНТАХ СБОРКИ. Он сверяет applicationId с package_name из json и
#     на несовпадении валит сборку («No matching client found for package name»). У нас debug живёт
#     под mayaknetworks.app.debug, то есть плагин сломал бы обычную отладочную сборку целиком.
#
#  3) 🧩 ПЛАГИН НИЧЕГО БОЛЬШЕГО И НЕ ДЕЛАЕТ. Его работа — положить значения из json в строковые
#     ресурсы, откуда их читает FirebaseOptions.fromResource() (имена ресурсов заданы САМИМ SDK:
#     google_app_id, gcm_defaultSenderId, google_api_key, project_id). Ровно это делает скрипт.
#
# Следствие, которое надо знать: Firebase жив ТОЛЬКО в сборке prodRelease. В debug/release ресурсов
# нет → FirebaseApp не инициализируется (FirebaseInitProvider это лишь пишет в лог, не роняет
# приложение), MayakPush молчит одной строкой, ящик сообщений работает опросом, как и раньше.
#
# Запуск: scripts/firebase-res.sh [путь-к-google-services.json]
# По умолчанию берёт ~/.mayak-secrets/firebase/google-services.json.
set -euo pipefail

SRC="${1:-$HOME/.mayak-secrets/firebase/google-services.json}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/ui/src/prodRelease/res/values/firebase.xml"

# applicationId боевой сборки — берём из gradle.properties, а не переписываем константой рядом:
# разъедется, и мы сгенерируем конфигурацию для чужого пакета, ничего не заметив.
PKG="$(sed -n 's/^mayakApplicationId=//p' "$REPO/gradle.properties" | tr -d '[:space:]')"
[ -n "$PKG" ] || { echo "не нашёл mayakApplicationId в gradle.properties" >&2; exit 1; }

[ -f "$SRC" ] || {
    echo "нет файла $SRC" >&2
    echo "Он не в git осознанно: это ключ. Взять в консоли Firebase (Project settings → Your apps →" >&2
    echo "Android → google-services.json) и положить в ~/.mayak-secrets/firebase/." >&2
    exit 1
}

mkdir -p "$(dirname "$OUT")"

SRC="$SRC" PKG="$PKG" OUT="$OUT" python3 - <<'PY'
import json, os, xml.sax.saxutils as x

src, pkg, out = os.environ["SRC"], os.environ["PKG"], os.environ["OUT"]
cfg = json.load(open(src, encoding="utf-8"))

# Клиент ИМЕННО нашего боевого пакета. Плагин на этом месте валит сборку; мы говорим словами, что
# искали и что нашли, — эта ошибка стоила людям целых вечеров.
clients = cfg.get("client", [])
match = [c for c in clients
         if c.get("client_info", {}).get("android_client_info", {}).get("package_name") == pkg]
if not match:
    have = [c.get("client_info", {}).get("android_client_info", {}).get("package_name") for c in clients]
    raise SystemExit(f"в {src} нет клиента для пакета {pkg} (есть: {have})")
client = match[0]

app_id = client["client_info"]["mobilesdk_app_id"]
api_key = client["api_key"][0]["current_key"]
sender = cfg["project_info"]["project_number"]
project = cfg["project_info"]["project_id"]

# ⚠️ Имена ресурсов задаёт SDK (FirebaseOptions.fromResource), переименовывать нельзя — Firebase
# читает их через getIdentifier() по строке, и опечатка проявится не сборкой, а тишиной на телефоне.
values = {
    "google_app_id": app_id,
    "gcm_defaultSenderId": str(sender),
    "google_api_key": api_key,
    "project_id": project,
}

lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    "<!-- СГЕНЕРИРОВАНО scripts/firebase-res.sh — НЕ ПРАВИТЬ РУКАМИ И НЕ КОММИТИТЬ (в .gitignore).",
    f"     Источник: ~/.mayak-secrets/firebase/google-services.json, пакет {pkg}.",
    "     Имена строк заданы Firebase SDK (FirebaseOptions.fromResource) — переименование = тишина. -->",
    "<resources>",
]
for name, value in values.items():
    # translatable="false": это не текст интерфейса, переводить его нельзя ни в каком виде.
    lines.append(f'    <string name="{name}" translatable="false">{x.escape(str(value))}</string>')
lines += ["</resources>", ""]

with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print(f"записал {out} (пакет {pkg}, проект {project})")
PY

echo "Готово. Дальше — обычная боевая сборка: ./gradlew :ui:assembleProdRelease"
