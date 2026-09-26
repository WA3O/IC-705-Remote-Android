plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ic_705remote2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ic_705remote2"
        minSdk = 26
        targetSdk = 35
        versionCode = 2841
        versionName = "28.41"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/MainActivity_v*.kt")
}

dependencies {
}
