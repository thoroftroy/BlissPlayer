plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versioned like every other Wonderdesk app: the number comes from versions.json (key "bliss-player") and only changes when someone changes it there.
val registry = rootDir.resolve("versions.json")
check(registry.isFile) { "Missing central versions.json; do not guess release versions" }
val appVersionName: String = ((groovy.json.JsonSlurper().parse(registry) as Map<*, *>)["apps"] as Map<*, *>)["bliss-player"] as? String ?: error("Missing independent app version: bliss-player")
require(appVersionName.matches(Regex("0\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "App versions must be numeric 0.x.x" }
val versionParts = appVersionName.split('.').map { it.toInt() }
require(versionParts[2] < 1000) { "Android versionCode limit; bump the minor version before patch 1000" }
val appVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1000 + versionParts[2]

// Keep the newest installable APK in the repo root so it can be attached to a release; older versioned APKs in the root are pruned.
tasks.register("collectRootApk") {
    dependsOn("assembleDebug")
    doLast {
        val outDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val built = outDir.resolve("app-debug.apk")
        val name = "BlissPlayer-v$appVersionName-debug.apk"
        val keep = rootDir.resolve(name)
        built.copyTo(keep, overwrite = true)
        rootDir.listFiles { file: java.io.File -> file.isFile && file.name.startsWith("BlissPlayer-v") && file.name.endsWith("-debug.apk") && file.name != name }?.forEach { it.delete() }
    }
}
afterEvaluate {
    tasks.named("assembleDebug") { finalizedBy("collectRootApk") }
}

android {
    namespace = "com.blissplayer.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blissplayer.music"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            // Instrumentation needs Kotlin/test APIs which app-only shrinking removes.
            // Release builds remain fully shrunk; debug is the reproducible test build.
            isMinifyEnabled = false
            isShrinkResources = false
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
        viewBinding = false
        compose = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    // Playback uses ExoPlayer core only with a custom XP UI. That keeps the APK small.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media:media:1.7.0")

    // Tag read/write for edit-metadata (pure Java, no native deps).
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Shared domain/rules/backend contracts (pure JVM, no Android APIs).
    implementation(project(":core"))
    // The Wonderdesk kit: used for the suite-style Settings and About screens.
    implementation(project(":shared"))

    // JVM unit tests for analyzer + shuffle math (run with testDebugUnitTest).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
