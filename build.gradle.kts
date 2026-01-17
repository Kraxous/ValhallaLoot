plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.7.3"
}

group = "dev.waystone"
version = "1.0.0"
description = "ValhallaLoot - custom loot generation for ValhallaMMO"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    paperDevBundle("1.21.1-R0.1-SNAPSHOT")
    
    // SQLite for persistent data (fallback storage)
    implementation("org.xerial:sqlite-jdbc:3.45.0.0")
    
    // Optional: for better YAML parsing if needed (Paper uses SnakeYAML built-in)
    // Already included in Paper API
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Set default locale to en-GB (British English)
tasks.register("setLocale") {
    doFirst {
        System.setProperty("user.language", "en")
        System.setProperty("user.country", "GB")
    }
}

// Apply locale to all JVM tasks
allprojects {
    tasks.withType<JavaCompile> {
        doFirst {
            System.setProperty("user.language", "en")
            System.setProperty("user.country", "GB")
        }
    }
}
