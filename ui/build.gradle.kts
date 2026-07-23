@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val pkg: String = providers.gradleProperty("amneziawgPackageName").get()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    // kotlinx-serialization: нужен, т.к. в :ui есть @Serializable-классы (Paths/PersistedEntry для
    // offline-кэша конфига). Без плагина serializer НЕ генерится → runtime SerializationException на
    // старте (краш «не запускается», 2026-07-06). В :core плагин уже есть — здесь добавляем для :ui.
    alias(libs.plugins.kotlin.serialization)
}

android {
    buildFeatures {
        buildConfig = true
        dataBinding = true
        viewBinding = true
    }
    // namespace (пакет исходников/R/BuildConfig) остаётся org.amnezia.awg — код на него завязан.
    namespace = pkg
    defaultConfig {
        // applicationId (идентификатор УСТАНОВЛЕННОГО приложения — светится в диалоге VPN и системе)
        // отвязан от namespace: убираем «amnezia» из видимого пакета (бренд + анти-фингерпринт РКН,
        // 2026-07-06 по запросу владельца). Код уже поддерживает applicationId ≠ namespace (debug-вариант,
        // MayakDisguise через context.packageName, FileProvider authority = ${applicationId}).
        applicationId = providers.gradleProperty("mayakApplicationId").get()
        targetSdk = 35
        // Версия ПРИЛОЖЕНИЯ Маяк (наша, не движка AmneziaWG). См. gradle.properties / CHANGELOG.md.
        versionCode = providers.gradleProperty("mayakVersionCode").get().toInt()
        versionName = providers.gradleProperty("mayakVersionName").get()
        buildConfigField("int", "MIN_SDK_VERSION", minSdk.toString())
        // Флаг НОВОГО дизайна (DESIGN-VISION: живой фон-карта + премиум-кнопка). Владелец одобрил на устройстве
        // 2026-07-22 → сделали ДЕФОЛТОМ (true) для всех сборок, включая прод/релиз. Флаг оставлен на случай
        // будущего A/B или быстрого отката. Ветвление — в коде через BuildConfig.NEW_DESIGN (живой фон-карта).
        buildConfigField("boolean", "NEW_DESIGN", "true")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    signingConfigs {
        // Фиксированный ключ для debug-сборок «Маяк»: даёт СТАБИЛЬНУЮ подпись между CI-сборками,
        // чтобы обновление ставилось ПОВЕРХ старого без удаления (дефолтный debug.keystore на CI
        // генерится заново каждый раз → разная подпись → Android блокирует апдейт). Это не секрет.
        create("mayakdebug") {
            storeFile = file("mayak-debug.p12")
            storePassword = "mayakdebug"
            keyAlias = "mayak"
            keyPassword = "mayakdebug"
            storeType = "PKCS12"
            // Явно включаем ВСЕ схемы подписи (v1 JAR + v2 + v3) — максимальная совместимость сайдлоада на
            // разных прошивках (MIUI/EMUI/Android 24…35). По умолчанию для minSdk 24 AGP отключал v1 → APK
            // был v2-only; часть инсталляторов при сайдлоаде даёт «Приложение не установлено» (2026-07-22).
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            // БЕЗ signingConfig в gradle → assembleRelease даёт unsigned APK, а CI подписывает его
            // ЗАЩИЩЁННЫМ релиз-ключом из секретов (ANDROID_KEYSTORE_BASE64, не в репозитории — mayak-debug.p12
            // публичен и утёк в GitGuardian, для релиза больше НЕ используем). См. .github/workflows/build.yml.
            // minify/shrink ВЫКЛ: текущее прод-приложение не минифицировано; R8 + сериализация в релизе не
            // проверены — не рискуем на этой сборке (включим minify отдельно после теста). Правила в
            // proguard-rules.pro оставлены на будущее (при minify=false игнорируются).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
            packaging {
                resources {
                    excludes += "DebugProbesKt.bin"
                    excludes += "kotlin-tooling-metadata.json"
                    excludes += "META-INF/*.version"
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("mayakdebug")
        }
        create("googleplay") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
        // DEV-сборка НОВОГО дизайна: свой applicationId (mayaknetworks.app.dev) → ставится РЯДОМ с прод-
        // приложением, чтобы владелец сравнил старый и новый дизайн на одном устройстве. Подписана стабильным
        // mayakdebug-ключом (не секрет) → assembleDev даёт готовый к установке APK. Метка «Mayak dev» — из
        // ui/src/dev/res. NEW_DESIGN=true → включает ветку нового дизайна в коде. matchingFallbacks=release →
        // :tunnel/:core берут release-вариант (namespace org.amnezia.awg общий, JNI не зависит от applicationId).
        create("dev") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("mayakdebug")
            buildConfigField("boolean", "NEW_DESIGN", "true")
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "LongLogTag"
        warning += "MissingTranslation"
        warning += "ImpliedQuantity"
    }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(project(":core"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx) // тихий еженедельный телеметри-бикон (MayakTelemetryWorker)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.material)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugarJdkLibs)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.isDeprecation = true
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
