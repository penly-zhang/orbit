plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.codexpocket"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.codexpocket"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "dev.codexpocket.PreviewInstrumentation"
        versionCode = 52
        versionName = "0.12.39"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.apache.sshd:sshd-core:2.14.0")
    testImplementation("net.i2p.crypto:eddsa:0.3.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("pocket.root", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
