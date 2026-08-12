# Правила R8/ProGuard для :ui. С 09-08 боевой buildType prodRelease собирается с minify+shrink
# (дев-вариант `release` — по-прежнему без, см. ui/build.gradle.kts). ВАЖНО: обфускации нет —
# в proguard-android-optimize.txt стоит -dontobfuscate (как у апстрима WireGuard/AmneziaWG),
# то есть R8 только ВЫКИДЫВАЕТ неиспользуемое; все правила ниже защищают от УДАЛЕНИЯ того,
# до чего R8 не дотягивается статически (рефлексия, генерённые сериализаторы, классы из XML).
#
# Чего здесь НЕТ и почему:
# - JNI (GoBackend.awg*): native-методы держит -keepclasseswithmembernames в
#   proguard-android-optimize.txt, а сам GoBackend зовётся из кода напрямую — не удалится.
# - Компоненты манифеста и activity-alias маскировки: AGP генерит keep-правила из манифеста
#   и layout-XML сам (aapt2), алиасы — записи манифеста, не классы.
# - BouncyCastle (ветка доставки, F-T8): используется ТОЛЬКО lightweight API
#   (org.bouncycastle.crypto.signers.Ed25519Signer — прямые ссылки, R8 видит их статически),
#   правил не нужно, библиотека честно ужимается до используемых классов. ⚠️ Если кто-то перейдёт
#   на JCA-путь (Security.addProvider(BouncyCastleProvider()) + Signature.getInstance("Ed25519")) —
#   провайдер грузит реализации Class.forName'ом по имени, и БЕЗ keep-правил это молча сломается.

# Крэш-репорты от людей должны быть читаемыми: R8 инлайнит и сдвигает строки даже без обфускации,
# retrace по mapping.txt (лежит рядом с APK) возвращает настоящие кадры стека.
-keepattributes SourceFile,LineNumberTable

# Tink (внутри androidx.security.crypto) ссылается на JSR-305-аннотации (@Nullable, @GuardedBy),
# которых в рантайме Android нет и не было — они compileOnly. Это ссылки ТОЛЬКО из аннотаций,
# в исполняемый код не попадают; без -dontwarn R8 валит сборку «Missing class javax.annotation...».
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# --- kotlinx-serialization ---------------------------------------------------------------------
# Без этих keep-правил R8 может выкинуть сгенерённые сериализаторы, и приложение крашнется на
# РАНТАЙМЕ (SerializationException), хотя debug (без minify) работает. Это главный класс поломок
# R8 у нас: @Serializable по всему клиентскому API (:core MayakBackend) и offline-кэшу конфига
# (Paths/PersistedEntry в :ui, драйвер 2026-07-06). Ниже — официальные правила
# kotlinx-serialization (github.com/Kotlin/kotlinx.serialization#android).

# EnclosingMethod добавлен к официальному сниппету: без него свежий R8 валит сборку
# «Attribute InnerClasses requires EnclosingMethod attribute» (поймано при включении 09-08).
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.**

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the generated `$$serializer` classes.
-keep,includedescriptorclasses class **$$serializer { *; }

# --- androidx.preference: классы из res/xml/preferences.xml --------------------------------------
# Кастомные Preference (VersionPreference, ZipExporterPreference, QuickTilePreference, ...)
# инстанцируются БИБЛИОТЕКОЙ по имени класса из XML-тега (рефлексия). aapt2-правила покрывают
# layout и манифест, но на res/xml полагаться нельзя — если правило не сгенерится, R8 выкинет
# «неиспользуемый» класс и экран настроек упадёт при открытии. Держим конструкторы явно.
-keep class org.amnezia.awg.preference.** {
    <init>(...);
}

# --- JS-интерфейс шага «вы человек» (SPEC-0048) ---------------------------------------------------
# Методы, которые зовёт САМА СТРАНИЦА (window.MayakCaptcha.onToken/onError), в коде никто не вызывает
# — статически R8 видит их как мёртвые и в боевой сборке (minify только у prodRelease) выкинул бы.
# Отказ был бы идеальным «сделано, но не работает»: в debug регистрация проходит, у людей виджет
# решается, а токен не доезжает НИКУДА и экран молчит до таймаута.
# Платформенный proguard-android-optimize.txt такое правило обычно несёт — но полагаться на чужой
# файл в вопросе «работает ли регистрация у людей» нельзя, поэтому держим своё, явное.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
