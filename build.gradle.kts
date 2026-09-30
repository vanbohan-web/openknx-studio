plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "be.openknx"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

javafx {
    version = "21.0.5"
    modules = listOf("javafx.controls")
}

dependencies {
    implementation("io.calimero:calimero-core:3.0-M2")
}

application {
    mainClass.set("be.openknx.studio.OpenKnxStudioApp")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
