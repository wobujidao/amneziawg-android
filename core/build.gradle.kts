// :core — переносимый не-UI-модуль клиента «Маяк» (чистый Kotlin/JVM).
// Здесь живёт логика, одинаковая на всех платформах: клиент к API ядра (MayakBackend),
// рендер конфига AWG 2.0 в .conf, платформенные интерфейсы (keygen/хранилище/туннель/проба).
// Платформо-зависимые реализации (Android Keystore, tunnel-crypto, GoBackend) — в :ui/:tunnel.
// Модуль НЕ зависит от Android — собирается и тестируется на обычном JVM-раннере (см. .github/workflows/core.yml).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // api: тип Json фигурирует в публичном API MayakBackend → должен быть виден потребителям (:ui)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnit()
}
