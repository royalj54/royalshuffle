import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()
        ?.use(::load)
}
val spotifyClientId = providers.gradleProperty("SPOTIFY_CLIENT_ID")
    .orElse(localProperties.getProperty("SPOTIFY_CLIENT_ID", ""))

android {
    namespace = "com.royalshuffle.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.royalshuffle.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField(
            "String",
            "SPOTIFY_CLIENT_ID",
            "\"${spotifyClientId.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
}
