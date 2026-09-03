plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
    id("com.mikepenz.aboutlibraries.plugin")
}

apply(from = "sentry.gradle")

android {
    namespace = "at.least.conch"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.least.conch"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Instrumented tests reach the Docker sshd matrix on the host through
        // the emulator's gateway; pass -Pconch.matrixHost=… for a real device
        // and -Pconch.localSshdTest=true to make a missing matrix FAIL (CI).
        testInstrumentationRunnerArguments["conchMatrixHost"] =
            (project.findProperty("conch.matrixHost") as String?) ?: "10.0.2.2"
        testInstrumentationRunnerArguments["conchLocalSshdTest"] =
            (project.findProperty("conch.localSshdTest") as String?) ?: "false"
    }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            // ads + one-time "remove ads" IAP (Google Play)
        }
        create("foss") {
            dimension = "store"
            // F-Droid / direct APK: no ads, no proprietary blobs
            applicationIdSuffix = ".foss"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Local release builds without an uploaded key store sign with the
            // debug key so the artifact is installable. CI/official releases
            // must provide RELEASE_STOREFILE etc. via ~/.gradle/gradle.properties.
            signingConfig = if (project.hasProperty("RELEASE_STOREFILE")) {
                signingConfigs.create("release") {
                    storeFile = file(project.property("RELEASE_STOREFILE"))
                    storePassword = project.property("RELEASE_STOREPASSWORD") as String
                    keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                    keyPassword = project.property("RELEASE_KEYPASSWORD") as String
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        // Self-hosted Sentry endpoint; empty = crash reporting fully disabled.
        // Inject via local.properties: SENTRY_DSN=..., SENTRY_URL=..., SENTRY_TOKEN=...
        buildConfigField("String", "SENTRY_DSN", "\"${project.findProperty("SENTRY_DSN") ?: ""}\"")
    }

    testOptions {
        // Vendored com.termux.terminal unit tests touch android.util.Log/Base64
        // on non-fatal paths; mirror upstream's build config so JVM tests run.
        unitTests.isReturnDefaultValues = true
        // Robolectric tests read android resources / assets
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * One gate for every variant's lint, with the flavor list DERIVED rather than
 * transcribed into CI: adding a flavor must not silently shrink the gate.
 * Wired into `check` so a local build catches what CI would.
 */
val lintAll = tasks.register("lintAll") {
    group = "verification"
    description = "Runs Android lint on every debug variant."
}
androidComponents.onVariants { variant ->
    if (variant.buildType == "debug") {
        lintAll.configure { dependsOn("lint${variant.name.replaceFirstChar(Char::uppercase)}") }
    }
}
tasks.named("check") { dependsOn(lintAll) }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
    parallel = true
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // XML theme parent (Theme.Material3.DayNight.NoActionBar)
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.hierynomus:sshj:0.38.0")
    // sshj 0.38 declares eddsa runtime-only, so it still ships in the APK for
    // sshj's own Ed25519 handling; no app source compiles against it (the old
    // comment's "SshAgentSigner" class does not exist — grep-verified).
    // Upgrading to 0.40.0 is blocked on upstream issue #1018 (ECDSA PKCS#8
    // parsing + ed25519 pubkey auth regressions, reproduced in our suite).
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    val sentryVersion = "8.14.0"
    implementation("io.sentry:sentry-android-core:$sentryVersion")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OSS license attribution screen; json generated at build time by the
    // aboutlibraries plugin (res/raw/aboutlibraries.json).
    implementation("com.mikepenz:aboutlibraries-compose-m3:11.2.3")

    // Heap-leak detection for long-lived foreground sessions; debug builds only.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // Compose-specific lint checks (unstable params, recomposition, ...).
    lintChecks("com.slack.lint.compose:compose-lint-checks:1.4.2")

    // ktlint-backed auto-format rules for detekt — apply with
    // `./gradlew :app:detekt --auto-correct` (no standalone detektFormat
    // task is registered; the flag is the supported entry point).
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Virtual-time dispatchers for backoff/reconnect coroutine tests.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Robolectric: exercises real SharedPreferences / filesDir paths that
    // isReturnDefaultValues stubbing cannot reach (ExtraKeysConfig, AppLock,
    // HostStore/KeyManager on-disk formats).
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    // MockK: object mocking (SecretsStore) for Android-bound paths that
    // Robolectric cannot back (Android Keystore).
    testImplementation("io.mockk:mockk:1.13.13")

    // In-process SSH server for JVM tests against real SSH interaction.
    testImplementation("org.apache.sshd:sshd-core:2.13.2")
    testImplementation("org.apache.sshd:sshd-sftp:2.13.2")
    // i2p eddsa key types are what sshj 0.38 and MINA sshd 2.13 expect for
    // Ed25519 (TestSshd.generateEd25519); 0.40 would drop this need but is
    // blocked on upstream #1018.
    testImplementation("net.i2p.crypto:eddsa:0.3.0")
    // slf4j-android's Log calls would hit android.jar stubs in JVM tests; use NOP instead.
    // Variant classpaths are named e.g. fossDebugUnitTestRuntimeClasspath.
    configurations.matching { it.name.contains("UnitTest") }.configureEach {
        exclude(group = "org.slf4j", module = "slf4j-android")
    }
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.16")

    // On-device tests (app/src/androidTest): the real Android Keystore-backed
    // SecretsStore, the SAF provider driven through ContentResolver, the
    // foreground SessionService — against the Docker sshd matrix on the host.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Compose UI end-to-end tests (add-host form, terminal activity)
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Espresso 3.6+ — the compose-bom's transitive Espresso calls the removed
    // InputManager.getInstance() and crashes onIdle on recent Android (API 34+).
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    // -Dconch.localSshdTest=true / -Dconch.distroMatrix=true are Gradle-daemon
    // JVM flags; relay them into the test JVM so the opt-in Docker-sshd tests
    // (Docker*Test, see DockerMatrix) can see them.
    for (flag in listOf("conch.localSshdTest", "conch.distroMatrix")) {
        System.getProperty(flag)?.let { systemProperty(flag, it) }
    }
}
