plugins {
    id("com.android.application")
}

android {
    namespace = "com.samawa.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.samawa.tvhome"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
