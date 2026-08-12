// Modified 2026 by Mayak Networks. SPDX-License-Identifier: Apache-2.0
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
        // Версия движка для экрана «О приложении» — ЧИТАЕТСЯ из go.mod, а не пишется руками.
        // Аудит 2026-07-31, п. 19: в приложении годами стояло «v0.2.18», а собиралось v0.2.19 —
        // константу забыли обновить при апгрейде. Мелочь, но это ровно та неправда, из-за которой
        // потом не веришь и остальным цифрам на экране. Не нашли версию — не врём, показываем пусто.
        buildConfigField(
            "String",
            "AWG_GO_VERSION",
            "\"" + (rootProject.file("tunnel/tools/libwg-go/go.mod").readLines()
                // С линии 3.0 путь модуля несёт суффикс мажора (amneziawg-go/v3) — регэксп обязан
                // понимать оба написания, иначе экран «О приложении» снова показывал бы пустую версию.
                .firstNotNullOfOrNull { Regex("""amneziawg-go(?:/v\d+)?\s+(v\S+)""").find(it)?.groupValues?.get(1) }
                ?: "") + "\"",
        )
        // Разводит ДВА бэкенда (дев mayakvpn.ru / прод mayaknetworks.com — своя корневая CA у каждого,
        // 2026-08-06). ДЕФОЛТ = false (дев, как было всегда); buildType prodRelease переопределяет на
        // true. Единственная точка ветвления — MayakHostList (адреса/CA берутся из buildType-варианта,
        // а не из константы рядом с константой: 2026-08-05 поймали 4 бага ровно на вшитых списках).
        buildConfigField("boolean", "MAYAK_PROD_TARGET", "false")
        // Адрес поддержки СВОЕГО контура. Раньше он был вшит прямо в строку перевода, один на обе
        // сборки, — и боевое приложение отправляло человека писать на дев-домен (найдено 07-08).
        buildConfigField("String", "MAYAK_SUPPORT_EMAIL", "\"support@mayakvpn.ru\"")
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
            // minify/shrink здесь ВЫКЛ и это осознанно: `release` — дев-вариант под снятый контур
            // (07-08), людям не уходит. Боевой prodRelease НИЖЕ включает R8 поверх initWith —
            // проверено живым прогоном на эмуляторе 09-08 (вход, список стран, туннель до
            // «Защищено», «О приложении»). Правила — proguard-rules.pro.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
            packaging {
                resources {
                    excludes += "DebugProbesKt.bin"
                    excludes += "kotlin-tooling-metadata.json"
                    excludes += "META-INF/*.version"
                    // BouncyCastle (ветка доставки, F-T8): R8 ужимает его КОД до ~60 КБ, но
                    // classpath-ресурсы minify не трогает — и в APK въезжали таблицы пост-квантового
                    // Picnic (org/bouncycastle/pqc/.../lowmc*.bin.properties, 1,2 МБ) и сообщения
                    // PKIX-ревьюера (~90 КБ), чей код R8 уже выкинул (замер 09-08). Мы используем
                    // только Ed25519 (classic crypto, lightweight API) — PQC-данные мёртвый груз.
                    // Пока BouncyCastle в зависимостях нет, маски просто ничему не матчатся.
                    excludes += "org/bouncycastle/pqc/**"
                    excludes += "org/bouncycastle/x509/CertPathReviewerMessages*"
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("mayakdebug")
        }
        // 🔴 buildType `googleplay` СНЯТ 08-08. Он наследовал `release` и НЕ ставил
        // MAYAK_PROD_TARGET=true — то есть под именем «для Google Play» собирал сборку под ДЕВ-контур,
        // снятый 07-08 (мёртвый домен, чужой CA). Никто его не звал: и APK для сайта, и AAB для Play
        // идут через prodRelease (docs/RELEASE-PUBLISHING.md), ссылок на него не было ни в скриптах,
        // ни в CI, ни в доке. Заряженный фугас без единого потребителя — убран, а не починен.
        // Сборка под ПРОД-ядро de1/mayaknetworks.com (поднято 2026-08-06), отдельное от дева
        // mayakvpn.ru. Тот же release (unsigned → подписываем релиз-ключом руками/CI), но
        // MAYAK_PROD_TARGET=true переключает MayakHostList на MayakProdHosts, а ui/src/prodRelease/res
        // подменяет res/raw/mayak_ca.pem и res/xml/network_security_config.xml на прод-CA (CN=Mayak
        // Prod CA) и IP прод-ядра — AGP берёт ресурс из buildType-варианта поверх main автоматически,
        // дублировать/редактировать main не нужно. Задача :ui:assembleProdRelease (аддитивная — старые
        // :ui:assembleRelease/:ui:assembleDebug и их пути вывода не меняются, CI build.yml не трогаем).
        create("prodRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "MAYAK_PROD_TARGET", "true")
            buildConfigField("String", "MAYAK_SUPPORT_EMAIL", "\"support@mayaknetworks.com\"")
            // R8 включён 09-08 ТОЛЬКО для боевой сборки (задача «Включить R8/minify», APP-BACKLOG):
            // без него каждая новая библиотека едет к людям целиком (BouncyCastle ветки доставки —
            // +2,9 МБ). Обфускации НЕТ (-dontobfuscate в proguard-android-optimize.txt, как у
            // апстрима WireGuard/AmneziaWG): R8 только выкидывает неиспользуемый код и ресурсы,
            // имена классов не трогает — рефлексия/JNI/крэш-репорты живут с настоящими именами.
            // Опасные места (kotlinx.serialization, Preference из res/xml) прикрыты правилами в
            // proguard-rules.pro; mapping.txt копируется к APK задачей copyProdReleaseMapping ниже.
            isMinifyEnabled = true
            isShrinkResources = true
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
    // JVM-юнит-тесты :ui (первый — сторож пиннинга в network_security_config прод-варианта).
    testImplementation(libs.junit)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.isDeprecation = true
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// mapping.txt от R8 — версионной копией в build/outputs/apk-mapping/. Без маппинга крэш-репорт
// от человека нечитаем (R8 инлайнит/сдвигает строки даже без обфускации имён), а штатный
// outputs/mapping/prodRelease/mapping.txt перезаписывается каждой сборкой — копия с версией в
// имени переживает следующий релиз. Забирая APK на выкладку, забирать и её (retrace: команда
// $ANDROID_SDK_ROOT/cmdline-tools/*/bin/retrace <mapping> <stacktrace>).
// ⚠️ Каталог назначения НЕ outputs/apk/prodRelease: он принадлежит задачам AGP, и Copy туда
// валит сборку («uses this output ... without declaring an explicit dependency», поймано 09-08).
androidComponents {
    onVariants(selector().withName("prodRelease")) { variant ->
        val mappingFile = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE)
        val versionTag = providers.gradleProperty("mayakVersionName").get() +
            "-" + providers.gradleProperty("mayakVersionCode").get()
        val copyMapping = tasks.register<Copy>("copyProdReleaseMapping") {
            // .map{listOf}.orElse(empty): при ВЫКЛЮЧЕННОМ minify у артефакта нет значения, и голый
            // from(mappingFile) валит сборку «property has no value» — то есть аварийный откат R8
            // (выключить одну строку) ломал бы сборку в другом месте. Нет маппинга — нет копии, не ошибка.
            from(mappingFile.map { listOf(it) }.orElse(emptyList()))
            into(layout.buildDirectory.dir("outputs/apk-mapping/prodRelease"))
            rename { "mapping-prodRelease-$versionTag.txt" }
        }
        tasks.matching { it.name == "assembleProdRelease" }.configureEach { finalizedBy(copyMapping) }
    }
}

// Сторожи, которые читают РЕСУРСЫ и КОНФИГИ из дерева исходников (MayakMessagesWordsTest,
// NetworkSecurityConfigPinTest), Gradle сам инпутами не считает: правка текста строки не меняет ни
// одного .class, задача остаётся «up-to-date», и тест НЕ ЗАПУСКАЕТСЯ вовсе. Проверено 12-08:
// нарочно испорченная строка («VPN отключён») дала BUILD SUCCESSFUL — то есть сторож молчал ровно
// в том случае, ради которого заведён. Объявляем эти файлы входами задачи тестов явно.
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree("src/main/res") { include("values*/strings.xml") },
        fileTree("src/prodRelease/res") { include("xml/*.xml") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mayakGuardedResources")
}
