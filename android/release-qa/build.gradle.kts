import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.cloudmusic.releaseqa"
    compileSdk = 35
    defaultConfig { applicationId = "app.cloudmusic.releaseqa"; minSdk = 26; targetSdk = 35 }
    if (providers.gradleProperty("productionQa").isPresent) {
        val path = System.getenv("CLOUD_MUSIC_SIGNING_PROPERTIES")?.let { file(it) } ?: rootProject.file("signing.properties")
        check(path.isFile) { "Private signing configuration is required for production QA" }
        val values = Properties().apply { path.inputStream().use { load(it) } }
        val qaSigning = signingConfigs.create("productionQa") {
            storeFile = file(values.getProperty("storeFile")); storePassword = values.getProperty("storePassword")
            keyAlias = values.getProperty("keyAlias"); keyPassword = values.getProperty("keyPassword")
        }
        buildTypes.getByName("debug").signingConfig = qaSigning
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.annotation:annotation:1.8.1")
    implementation("androidx.tracing:tracing:1.2.0")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("junit:junit:4.13.2")
}
