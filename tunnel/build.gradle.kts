@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.testing.logging.TestLogEvent

val pkg: String = providers.gradleProperty("amneziawgPackageName").get()
// 🔴 Каталоги, куда движок пишет на устройстве, зовутся по applicationId УСТАНОВЛЕННОГО приложения,
// а не по namespace. До 20-08 здесь стоял namespace (`org.amnezia.awg`), и в собранную .so уезжал
// путь `/data/data/org.amnezia.awg/cache/amneziawg` — каталог пакета, которого на телефоне нет.
// Итог виден в диаг-логах КАЖДОГО человека при КАЖДОМ подключении:
//   E AmneziaWG/mayak: UAPIOpen: mkdir /data/data/org.amnezia.awg: permission denied
// Туннель при этом работает (управляющий сокет движка на Android не используется), но красная
// строка в логе стоит первой, и разбор чужой поломки начинается с неё — то есть с ложного следа.
// applicationId берём той же настройкой, что и модуль ui (`mayakApplicationId`), а `.debug` ниже
// повторяет его `applicationIdSuffix`.
val appId: String = providers.gradleProperty("mayakApplicationId").get()
val cmakeAndroidPackageName: String = providers.environmentVariable("ANDROID_PACKAGE_NAME").getOrElse(appId)

plugins {
    alias(libs.plugins.android.library)
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "${pkg}.tunnel"
    externalNativeBuild {
        cmake {
            path("tools/CMakeLists.txt")
        }
    }
    testOptions.unitTests.all {
        it.testLogging { events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED) }
    }
    buildTypes {
        all {
            externalNativeBuild {
                cmake {
                    // 🔴 Собираем ТОЛЬКО движок. libwg.so и libwg-quick.so — это amneziawg-tools,
                    // то есть код под GPL-2.0 внутри нашего Apache-2.0 приложения, и нужны они
                    // единственному пути — AwgQuickBackend (режим «ядерный модуль + root»).
                    // В «Маяке» этот путь НЕДОСТИЖИМ: включается он галочкой на апстримном экране
                    // SettingsActivity, а тот открывается только из апстримной MainActivity, которая
                    // в манифесте exported=false и не запускается ниоткуда (лаунчер ведёт в
                    // MayakActivity). Знание UserKnobs.enableKernelModule больше нигде не меняется.
                    // Возвращать эти цели — только вместе с живым путём в интерфейсе.
                    // Минус ~0,45 МБ несжатого в APK и минус GPL-бинари в поставке (замер 17-08).
                    targets("libwg-go.so")
                    arguments("-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}")
                }
            }
        }
        release {
            externalNativeBuild {
                cmake {
                    arguments("-DANDROID_PACKAGE_NAME=${cmakeAndroidPackageName}")
                }
            }
        }
        debug {
            externalNativeBuild {
                cmake {
                    arguments("-DANDROID_PACKAGE_NAME=${cmakeAndroidPackageName}.debug")
                }
            }
        }
    }
    lint {
        disable += "LongLogTag"
        disable += "NewApi"
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    compileOnly(libs.jsr305)
    testImplementation(libs.junit)
}
