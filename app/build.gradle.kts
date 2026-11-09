plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.botvi.lordscapture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.botvi.lordscapture"
        minSdk = 24
        targetSdk = 34
        // يزيد تلقائياً مع كل بناء على GitHub حتى يقبله أندرويد كتحديث
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 100
        versionName = "1.0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("lords.jks")
            storePassword = "lords123"
            keyAlias = "lords"
            keyPassword = "lords123"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

base.archivesName.set("LordsCapture_v1")
