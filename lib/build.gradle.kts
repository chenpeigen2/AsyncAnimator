// lib/build.gradle.kts — synced to D:\CLauncher\TclLauncher\animationlib style
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.asyncanimator"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

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

    buildFeatures { buildConfig = true }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Resolve the Robolectric SDK through Gradle once, then use its local resolver.
// This avoids a second, unmanaged runtime download by the test runner.
val robolectricSdk by configurations.creating
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.usePreinstrumentedJars", "false")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}

dependencies {
    implementation(libs.androidx.dynamicanimation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // SDK 37 signature-only stubs cannot load Application/AnimatorSet in the JVM.
    testRuntimeOnly(libs.robolectric.android)
    robolectricSdk(libs.robolectric.android)
    testImplementation(libs.assertj.core)
}
