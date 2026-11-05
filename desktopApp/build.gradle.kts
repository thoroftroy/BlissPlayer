import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("com.blissplayer.desktop.DesktopMain")
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // Same schema as Android, desktop driver (no ORM, no migration of users).
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    // Same tag engine as Android (pure Java, no native deps).
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}

// Bake the shared app version into the jar (no dirty git files).
val writeVersion = tasks.register("writeVersion") {
    val out = layout.buildDirectory.file("generated/resources/bliss-version.txt")
    outputs.file(out)
    doLast {
        val props = Properties()
        rootProject.file("app/version.properties").inputStream().use { props.load(it) }
        out.get().asFile.apply {
            parentFile.mkdirs()
            writeText(props.getProperty("versionName", "0.11.x"))
        }
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources"))
}

tasks.named("processResources") { dependsOn(writeVersion) }

// One distributable jar (unsigned deps merged; signatures stripped).
val fatJar = tasks.register<Jar>("fatJar") {
    archiveBaseName.set("blissplayer-linux")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "com.blissplayer.desktop.DesktopMain") }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
