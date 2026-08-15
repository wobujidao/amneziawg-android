// Modified 2026 by Mayak Networks. SPDX-License-Identifier: Apache-2.0
import com.android.build.api.dsl.SettingsExtension

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version "9.3.1"
}

rootProject.name = "amneziawg-android"

include(":tunnel")
include(":ui")
include(":core")

configure<SettingsExtension> {
    // compileSdk (против чего КОМПИЛИРУЕМ) намеренно выше targetSdk (какое поведение системы
    // включаем — он 36, см. ui/build.gradle.kts). Это законная и рекомендованная Google связка:
    // свежие androidx (core 1.19) требуют компиляции против 37, а брать поведение Android 17
    // мы не готовы — его не на чем проверить. Приложение при этом остаётся в правилах Play.
    buildToolsVersion = "37.0.0"
    // 37 = android-37.0. Минорную 37.1 сюда не завести: у settings-плагина AGP 9.3.1 нет свойства
    // compileSdkMinor (проверено сборкой: «Unresolved reference»), а разница минорных SDK — только
    // в добавленных API, которыми мы не пользуемся.
    compileSdk = 37
    minSdk = 24
    ndkVersion = "29.0.14206865"
}
