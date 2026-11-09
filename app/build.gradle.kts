import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One patch increment per APK/build invocation, including connected-device tests.
// Plain compilation, JVM tests, lint, IDE sync and --dry-run do not consume versions.
val versionFile = layout.projectDirectory.file("version.properties").asFile
val versionValues = Properties().apply {
    providers.fileContents(layout.projectDirectory.file("version.properties")).asText.get()
        .reader().use { load(it) }
}
var appVersionName = versionValues.getProperty("versionName")
var appVersionCode = versionValues.getProperty("versionCode").toInt()
val buildsApk = gradle.startParameter.taskNames.any { path ->
    val task = path.substringAfterLast(':').lowercase()
    task == "build" || listOf("assemble", "bundle", "package", "install", "connected").any { task.startsWith(it) }
}
if (buildsApk && !gradle.startParameter.isDryRun) {
    val parts = appVersionName.split('.').map(String::toInt)
    appVersionName = "${parts[0]}.${parts[1]}.${parts[2] + 1}"
    appVersionCode++
    val temporary = Files.createTempFile(versionFile.parentFile.toPath(), "bliss-version-", ".tmp")
    Files.writeString(temporary, "versionCode=$appVersionCode\nversionName=$appVersionName\n")
    try {
        Files.move(temporary, versionFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary, versionFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    logger.lifecycle("Building BlissPlayer $appVersionName (code $appVersionCode)")
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
    }
}

dependencies {
    // Playback uses ExoPlayer core only with a custom XP UI. That keeps the APK small.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media:media:1.7.0")

    // Tag read/write for edit-metadata (pure Java, no native deps).
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Shared domain/rules/backend contracts (pure JVM, no Android APIs).
    implementation(project(":core"))

    // JVM unit tests for analyzer + shuffle math (run with testDebugUnitTest).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
