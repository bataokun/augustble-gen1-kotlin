plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.bataokun.augustble.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.bataokun.augustble.sample"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        // Same-source versioning: follows the root gradle.properties
        // (task 3.1/3.2 unified 0.16.0 across the library and the sample).
        versionName = providers.gradleProperty("LIBRARY_VERSION").getOrNull()
            ?: throw GradleException("LIBRARY_VERSION must be set in the root gradle.properties")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(project(":android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
