plugins {
    id("com.android.application")
}

val configuredBaseUrl = providers.gradleProperty("baseUrl")
    .orElse("http://10.0.2.2:8000/")
    .get()
val escapedBaseUrl = configuredBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val configuredVersionCode = providers.gradleProperty("appVersionCode")
    .orElse("1")
    .get()
    .toIntOrNull()
    ?: error("appVersionCode must be a positive integer")
val configuredVersionName = providers.gradleProperty("appVersionName")
    .orElse("1.0.0")
    .get()
    .trim()
    .ifEmpty { error("appVersionName must not be empty") }

android {
    namespace = "com.catsu.ictu.pmams"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.catsu.ictu.pmams"
        minSdk = 26
        targetSdk = 35
        versionCode = configuredVersionCode
        versionName = configuredVersionName

        buildConfigField("String", "BASE_URL", "\"$escapedBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
