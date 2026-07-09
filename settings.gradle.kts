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
    // Версия ДОЛЖНА совпадать с AGP модулей (libs.versions.toml agp), иначе рассинхрон
    // SettingsExtension → NoSuchMethodError compileSdk(Function1) при apply AGP-плагина.
    id("com.android.settings") version "9.2.1"
}

rootProject.name = "amneziawg-android"

include(":tunnel")
include(":ui")
include(":core")

configure<SettingsExtension> {
    buildToolsVersion = "36.0.0"
    compileSdk = 36
    minSdk = 24
    ndkVersion = "26.1.10909125"
}
