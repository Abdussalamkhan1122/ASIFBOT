plugins {
    id("com.android.application")
}

android {
    namespace = "com.asifbot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.asifbot.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 7
        versionName = "1.3.0"

        buildConfigField("String", "API_BASE_URL", "\"https://backed-expenses-cast-boulder.trycloudflare.com\"")
        buildConfigField("boolean", "DEMO_MODE", "false")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
}
