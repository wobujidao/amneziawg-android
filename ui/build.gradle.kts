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
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
