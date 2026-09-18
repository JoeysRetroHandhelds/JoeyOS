import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.joeyos.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joeyos.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "1.0.38"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 removes unused code and resources: a smaller APK that starts faster.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    // The Compress ROMs tool runs a bundled native program (chdman, shipped as
    // jniLibs/<abi>/libchdman.so). Android only unpacks native libraries to the executable
    // nativeLibraryDir when legacy packaging is on; without it the file stays inside the APK and
    // can't be run.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }


}

tasks.register<Exec>("fetchGameDatabases") {
    description = "Fetches up-to-date game title databases from GameTDB and titledb before each build."
    val python = if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3"
    commandLine(python, rootProject.file("scripts/fetch_gamedbs.py").absolutePath)
    isIgnoreExitValue = true   // never fail the build — bundled CSVs are the fallback
    errorOutput = System.err
}

tasks.named("preBuild") {
    dependsOn("fetchGameDatabases")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // RetroAchievements romhacks: some patches in RAPatches are stored as .7z (LZMA2, hence xz).
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
