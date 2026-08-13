#!/usr/bin/env bash
# ПРОВЕРКА СБОРКИ ДЛЯ GOOGLE PLAY. Запускать ПЕРЕД каждой заливкой .aab.
#
# Зачем: 13-08 к владельцу уехало падающее приложение. Правило R8 проверили на APK из assemble, а в
# Play ушёл БАНДЛ из прошлого прогона — это разные артефакты и разные задачи Gradle. Плюс Play не
# отдаёт бандл целиком: он режет его на части по ABI, языку и плотности экрана, и проверять надо
# результат нарезки, а не то, что собралось рядом.
#
# Использование: scripts/check-play-build.sh ui/build/outputs/bundle/storeRelease/ui-storeRelease.aab
# Нужен bundletool: https://github.com/google/bundletool/releases (положить рядом или задать BT=путь).
#
# Проверка поддержки 16-килобайтных страниц ТАК ЖЕ, как это делает Play:
# из AAB собираются те же split-APK (bundletool), и у каждого проверяется выравнивание архива на 16 КБ.
# Плюс отдельно — выравнивание сегментов внутри каждой библиотеки (llvm-readelf, LOAD должен быть 0x4000).
set -euo pipefail
AAB="${1:?укажи путь к .aab}"
SP="${BT_DIR:-$(dirname "$0")}"   # где лежит bundletool.jar
source ~/mayak-app-build.env
JAVA="$JAVA_HOME/bin/java"
ZA=~/android-sdk/build-tools/35.0.0/zipalign
READELF=~/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
W="${TMPDIR:-/tmp}/mayak-16k"; rm -rf "$W"; mkdir -p "$W"

echo "── 1. Сегменты внутри библиотек (должно быть 0x4000)"
unzip -q -o "$AAB" 'base/lib/*' -d "$W/aab"
bad=0; tot=0
for f in $(find "$W/aab/base/lib" -name '*.so'); do
  tot=$((tot+1))
  al=$($READELF -l "$f" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ' ')
  case "$al" in *0x1000*) bad=$((bad+1)); echo "   ✘ $(echo "$f" | sed 's|.*/lib/||') $al";; esac
done
echo "   библиотек $tot, невыровненных $bad"

echo "── 2. Те же APK, что соберёт Play (bundletool $($JAVA -jar "$SP/bundletool.jar" version))"
"$JAVA" -jar "$SP/bundletool.jar" build-apks --bundle="$AAB" --output="$W/out.apks" \
  --mode=default --overwrite >/dev/null
unzip -q -o "$W/out.apks" -d "$W/apks"
fail=0; n=0
for apk in $(find "$W/apks" -name '*.apk' | sort); do
  # интересуют только те, где есть нативные библиотеки
  if unzip -l "$apk" | grep -q '\.so$'; then
    n=$((n+1))
    if $ZA -c -P 16 4 "$apk" >/dev/null 2>&1; then
      echo "   ✔ $(basename "$apk")"
    else
      fail=$((fail+1)); echo "   ✘ $(basename "$apk") — архив НЕ выровнен на 16 КБ"
    fi
  fi
done
echo "   APK с библиотеками: $n, непрошедших: $fail"

echo "── ИТОГ"
if [ "$bad" -eq 0 ] && [ "$fail" -eq 0 ] && [ "$n" -gt 0 ]; then
  echo "   ✅ 16 КБ поддерживаются: и сегменты, и упаковка"
else
  echo "   ❌ НЕ поддерживаются (сегменты: $bad плохих, упаковка: $fail плохих из $n)"
  exit 1
fi
