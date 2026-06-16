plugins {
    kotlin("jvm") version "2.2.0"
}

group = "org.mavriksc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation ("org.processing:core:3.3.7")
    implementation("org.processing:video:3.3.7")
    implementation("com.googlecode.gstreamer-java:gstreamer-java:1.5")
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

tasks.register<JavaExec>("runUniverseScale") {
    group = "application"
    description = "Run the fullscreen universe scale expansion Processing demo."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.mavriksc.universeScale.UniverseScaleAppKt")
    listOf("universeScale.exportFrames", "universeScale.maxFrames", "universeScale.startSeconds").forEach { propertyName ->
        System.getProperty(propertyName)?.let { systemProperty(propertyName, it) }
    }
}

tasks.register<JavaExec>("runSieveCircles") {
    group = "application"
    description = "Run the rolling circle sieve Processing demo."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.mavriksc.sieve.SieveCirclesKt")
}
