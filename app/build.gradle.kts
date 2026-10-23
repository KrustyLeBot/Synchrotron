plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// versionCode = CI run number, versionName = 0.1.<build> (see the release workflow).
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val rcloneVersion = file("../rclone-bridge/go.mod").readLines()
    .first { it.contains("github.com/rclone/rclone ") }.trim().split(" ").last()

android {
    namespace = "app.synchrotron"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.synchrotron"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "RCLONE_VERSION", "\"$rcloneVersion\"")
        buildConfigField("String", "UPDATE_REPO", "\"KrustyLeBot/Synchrotron\"")
        // The embedded rclone is only built for 64-bit ARM phones.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Fixed release key, injected by CI from GitHub secrets. Never committed.
        val keystore = System.getenv("SYNCHROTRON_KEYSTORE")
        if (keystore != null && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SYNCHROTRON_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SYNCHROTRON_KEY_ALIAS") ?: "synchrotron"
                keyPassword = System.getenv("SYNCHROTRON_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // gomobile's JNI glue relies on reflection-ish lookups
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint { abortOnError = false }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    // Embedded rclone, built by scripts/build-librclone.sh (CI) into app/libs.
    implementation(files("libs/librclone.aar"))
    implementation(project(":client"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
