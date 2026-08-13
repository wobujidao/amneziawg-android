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
    buildToolsVersion = "35.0.0"
    compileSdk = 35
    minSdk = 24
    ndkVersion = "26.1.10909125"
}
