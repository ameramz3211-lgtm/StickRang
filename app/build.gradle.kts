import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stickrang.app"
    compileSdk = 36

    defaultConfig {
        // Change this before publishing. It becomes your Play Store package name forever.
        applicationId = "com.stickrang.app"
        minSdk = 24 // ML Kit subject segmentation needs 24+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        val authority = "$applicationId.stickercontentprovider"
        manifestPlaceholders["contentProviderAuthority"] = authority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$authority\"")
    }

    // Release signing: reads keystore.properties (kept out of version control).
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { buildConfig = true }

    // WhatsApp reads sticker files straight from assets, so they must stay uncompressed.
    androidResources { noCompress += listOf("webp", "png", "json") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    // On-device background removal (model delivered by Google Play services, not bundled in the APK)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
}
