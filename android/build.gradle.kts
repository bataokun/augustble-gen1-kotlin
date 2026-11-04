plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

// Published coordinates come from the root gradle.properties so the
// version cannot drift between the two library modules (task 3.1).
val libraryGroup = "io.github.bataokun"
val libraryVersion = providers.gradleProperty("LIBRARY_VERSION").getOrNull()
    ?: throw GradleException("LIBRARY_VERSION must be set in the root gradle.properties")

group = libraryGroup
version = libraryVersion

android {
    namespace = "io.github.bataokun.augustble.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = libraryGroup
            artifactId = "augustble-android"
            version = libraryVersion
            pom {
                name = "augustble-android"
                description =
                    "Android BLE transport for the August Smart Lock (1st gen): scanning, the " +
                        "GATT channel implementation (serialised operations, indications, the " +
                        "Gen1 connection recipe), runtime permissions, and the AugustAndroid entry point."
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

// The release software component only exists once the android block has
// been evaluated. AGP attaches the per-variant sources jar
// (`releaseSourcesJar`) through components["release"] on its own — adding
// it a second time would publish two artifacts with the same classifier
// (task 3.1).
afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("release") {
                from(components["release"])
            }
        }
    }
}
