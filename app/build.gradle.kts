plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(from = "sentry.gradle")

android {
    namespace = "at.least.conch"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.least.conch"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.8.1"
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

    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.slf4j:slf4j-android:1.7.36")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    val sentryVersion = "8.14.0"
    implementation("io.sentry:sentry-android-core:$sentryVersion")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
