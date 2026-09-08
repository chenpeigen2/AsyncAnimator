// lib/build.gradle.kts — synced to D:\CLauncher\TclLauncher\animationlib style
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.asyncanimator"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.assertj.core)
}
