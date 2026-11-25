plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.droidpilot"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.droidpilot"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core"))
    implementation(project(":webserver"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.github.mik3y:usb-serial-for-android:3.12.0")
}
