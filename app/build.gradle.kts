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
    // sshj declares eddsa runtime-only, so it ships in the APK but is not on
    // the compile classpath; SshAgentSigner references EdDSA types directly.
    implementation("net.i2p.crypto:eddsa:0.3.0")
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
    // i2p eddsa key types are what sshj (and this MINA version) expect for Ed25519.
    testImplementation("net.i2p.crypto:eddsa:0.3.0")
    // slf4j-android's Log calls would hit android.jar stubs in JVM tests; use NOP instead.
    // Variant classpaths are named e.g. fossDebugUnitTestRuntimeClasspath.
    configurations.matching { it.name.contains("UnitTest") }.configureEach {
        exclude(group = "org.slf4j", module = "slf4j-android")
    }
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

tasks.withType<Test>().configureEach {
    // -Dconch.localSshdTest=true is a Gradle-daemon JVM flag; relay it into the
    // test JVM so opt-in Docker-sshd tests (DockerSshdAuthTest) can see it.
    System.getProperty("conch.localSshdTest")?.let { systemProperty("conch.localSshdTest", it) }
}
