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
        versionCode = 6
        versionName = "1.2.2"

        buildConfigField("String", "API_BASE_URL", "\"https://backed-expenses-cast-boulder.trycloudflare.com\"")
        buildConfigField("String", "SUBSCRIPTION_PRODUCT_ID", "\"asifbot_monthly\"")
        buildConfigField("boolean", "DEMO_MODE", "false")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.android.billingclient:billing:8.0.0")
}
