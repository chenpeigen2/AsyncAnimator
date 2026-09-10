// lib/build.gradle.kts — synced to D:\CLauncher\TclLauncher\animationlib style
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
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

    // KSP 文档是构建产物，不应作为运行时资源进入 AAR/APK。
    packaging.resources.excludes.add("public-api.md")

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
    add("kspDebug", project(":api-doc-processor"))
    add("kspRelease", project(":api-doc-processor"))
    implementation(libs.androidx.dynamicanimation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // SDK 37 signature-only stubs cannot load Application/AnimatorSet in the JVM.
    testRuntimeOnly(libs.robolectric.android)
    robolectricSdk(libs.robolectric.android)
    testImplementation(libs.assertj.core)
}

// 文档作为变体编译的产物生成；不引入运行时处理器依赖。
ksp {
    arg("publicApi.sourceRoot", layout.projectDirectory.dir("src/main/java").asFile.absolutePath)
}

val generatePublicApiDocs by tasks.registering {
    group = "documentation"
    description = "Generate annotated public API documentation for Debug and Release."
}

listOf("Debug", "Release").forEach { variant ->
    val variantDirectory = variant.lowercase()
    val documentation = tasks.register<Sync>("generate${variant}PublicApiDocs") {
        group = "documentation"
        description = "Generate $variant public API Markdown from @PublicApi and Chinese KDoc."
        dependsOn("ksp${variant}Kotlin")
        from(layout.buildDirectory.file("generated/ksp/$variantDirectory/resources/public-api.md"))
        into(layout.buildDirectory.dir("docs/public-api/$variantDirectory"))
    }
    generatePublicApiDocs.configure { dependsOn(documentation) }
    tasks.matching { it.name == "compile${variant}Kotlin" }.configureEach {
        dependsOn(documentation)
    }
    // 在库资源源头排除文档，覆盖项目依赖场景；不能仅依赖最终 AAR 的 packaging 排除项。
    tasks.withType<Sync>().matching { it.name == "process${variant}JavaRes" }.configureEach {
        exclude("public-api.md")
    }
    tasks.withType<Test>().matching { it.name == "test${variant}UnitTest" }.configureEach {
        val javaResources = tasks.named<Sync>("process${variant}JavaRes")
        dependsOn(documentation, javaResources, ":api-doc-processor:test")
        systemProperty("publicApi.javaResources", javaResources.get().destinationDir.absolutePath)
        systemProperty(
            "publicApi.documentation",
            layout.buildDirectory.file("docs/public-api/$variantDirectory/public-api.md").get().asFile.absolutePath
        )
    }
}
