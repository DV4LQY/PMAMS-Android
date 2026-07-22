plugins {
    id("com.android.application")
}

val configuredBaseUrl = providers.gradleProperty("baseUrl")
    .orElse("http://10.0.2.2:8000/")
    .get()
val escapedBaseUrl = configuredBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.catsu.ictu.pmams"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.catsu.ictu.pmams"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

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
