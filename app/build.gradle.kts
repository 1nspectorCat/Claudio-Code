import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing lives OUTSIDE the repo (keystore.properties is gitignored).
// Missing file -> release falls back to the debug key, so local builds never break.
// Личные значения (адрес релея, отпечаток) живут в local.properties и не попадают в git.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.vladiko.voicebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vladiko.voicebridge"
        minSdk = 26
        // 35: Play requirement for new apps since 31.08.2025. NOTE: submissions after
        // 31.08.2026 will need 36 (Android 16) — that needs AGP 8.9+/Gradle 8.11+.
        targetSdk = 35
        // Single source of truth for the version: the app reads BuildConfig.VERSION_NAME.
        // Bump BOTH on every delivered build (Play requires a growing versionCode).
        versionCode = 148
        versionName = "1.48"
    }

    // Two builds from one codebase. "personal" is the owner's build: his relay baked in
    // as defaults, battery-exemption request allowed (personal manifest adds the permission).
    // "store" is the Google Play build: NO personal data compiled in (empty defaults),
    // no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (Play forbids it without a core-function
    // justification), first-run setup hints enabled.
    flavorDimensions += "dist"
    productFlavors {
        create("personal") {
            dimension = "dist"
            // Личный релей НЕ в репозитории: значения берутся из local.properties
            // (gitignored). Нет файла — флейвор собирается с пустыми дефолтами.
            buildConfigField("String", "DEF_URL", "\"${localProps.getProperty("relayUrl", "")}\"")
            buildConfigField("String", "DEF_PIN", "\"${localProps.getProperty("relayPin", "")}\"")
            // The owner runs whisper.cpp on his relay — server-side recognition is his default.
            buildConfigField("boolean", "DEF_WHISPER", "true")
            buildConfigField("boolean", "DEF_HANDSFREE", "false")
            buildConfigField("boolean", "DEF_AUTOSEND", "true")
        }
        // Hub test build: the owner's relay like "personal", but a separate app (own package,
        // own settings, own name) that installs NEXT TO his working walkie-talkie, not over it.
        create("hubtest") {
            dimension = "dist"
            applicationIdSuffix = ".hubtest"
            versionNameSuffix = "-hub"
            buildConfigField("String", "DEF_URL", "\"${localProps.getProperty("relayUrl", "")}\"")
            buildConfigField("String", "DEF_PIN", "\"${localProps.getProperty("relayPin", "")}\"")
            buildConfigField("boolean", "DEF_WHISPER", "true")
            buildConfigField("boolean", "DEF_HANDSFREE", "true")
            buildConfigField("boolean", "DEF_AUTOSEND", "false")
        }
        create("store") {
            dimension = "dist"
            buildConfigField("String", "DEF_URL", "\"\"")
            buildConfigField("String", "DEF_PIN", "\"\"")
            // v0.67 (the user caught this): a store newcomer has NO whisper backend — with
            // whisper on by default their dictation would upload into the void and die
            // silently. Android's recognizer works out of the box and keeps the headset
            // button usable; whisper stays an opt-in in the advanced section.
            buildConfigField("boolean", "DEF_WHISPER", "false")
            buildConfigField("boolean", "DEF_HANDSFREE", "false")
            buildConfigField("boolean", "DEF_AUTOSEND", "true")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: Play flags unshrunk builds and the APK carries okhttp/appcompat dead weight.
            // Rules kept conservative (see proguard-rules.pro) — reflection surface is tiny.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // org.json comes with the Android platform itself — no Maven copy needed
}
