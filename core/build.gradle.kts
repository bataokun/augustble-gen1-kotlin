plugins {
    kotlin("jvm")
    `maven-publish`
}

// Published coordinates come from the root gradle.properties so the
// version cannot drift between the two library modules (task 3.1).
val libraryGroup = "io.github.bataokun"
val libraryVersion = providers.gradleProperty("LIBRARY_VERSION").getOrNull()
    ?: throw GradleException("LIBRARY_VERSION must be set in the root gradle.properties")

group = libraryGroup
version = libraryVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = libraryGroup
            artifactId = "augustble-core"
            version = libraryVersion
            from(components["java"])
            pom {
                name = "augustble-core"
                description =
                    "Pure JVM protocol implementation for the August Smart Lock (1st gen) over BLE: " +
                        "framing, checksums, AES channels with stateful CBC, handshake, and " +
                        "response/event routing. Transport-agnostic via GattChannel; no Android dependencies."
                url = "https://github.com/bataokun/augustble-gen1-kotlin"
                licenses {
                    license {
                        name = "MIT"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
            }
        }
    }
    repositories {
        // GitHub Packages target. Credentials are read only when a publish
        // to this repository is actually invoked; `publishToMavenLocal`
        // ignores them and works without any setup.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/bataokun/augustble-gen1-kotlin")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .getOrElse("")
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .getOrElse("")
            }
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

tasks.test {
    useJUnitPlatform()
}
