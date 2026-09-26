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
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "API_BASE_URL", "\"https://api.asifbot.com\"")
        buildConfigField("String", "SUBSCRIPTION_PRODUCT_ID", "\"asifbot_monthly\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.android.billingclient:billing:8.0.0")
}
