plugins {
    id("com.android.application")
}

fun buildConfigString(value: String): String {
    val escaped = value.trim().trimEnd('/')
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val asifbotApiBaseUrl = providers.gradleProperty("ASIFBOT_API_BASE_URL")
    .orElse(providers.environmentVariable("ASIFBOT_API_BASE_URL"))
    .orElse("http://asifbot.duckdns.org:8080")
    .get()

val asifbotDemoMode = providers.gradleProperty("ASIFBOT_DEMO_MODE")
    .orElse(providers.environmentVariable("ASIFBOT_DEMO_MODE"))
    .orElse("false")
    .get()
    .equals("true", ignoreCase = true)

android {
    namespace = "com.asifbot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.asifbot.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 8
        versionName = "1.3.1"

        buildConfigField("String", "API_BASE_URL", buildConfigString(asifbotApiBaseUrl))
        buildConfigField("boolean", "DEMO_MODE", asifbotDemoMode.toString())
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
}
