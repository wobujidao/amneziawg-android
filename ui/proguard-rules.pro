# Правила R8/ProGuard для :ui. Релиз собирается с minify+shrink (isMinifyEnabled=true) —
# без этих keep-правил R8 может выкинуть/переименовать сгенерённые сериализаторы kotlinx-serialization,
# и приложение крашнется на РАНТАЙМЕ (SerializationException), хотя debug (без minify) работает.
# Драйвер: 2026-07-06 — offline-кэш конфига (@Serializable Paths/PersistedEntry в :ui). Ниже —
# официальные правила kotlinx-serialization (github.com/Kotlin/kotlinx.serialization#android).

-keepattributes *Annotation*, InnerClasses
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
