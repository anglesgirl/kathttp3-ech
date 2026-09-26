plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.kathttp3.example"; compileSdk = 37
    buildFeatures {
        compose = true
        buildConfig = true
    }
    val dohUrl = project.findProperty("kathttp3DohUrl")?.toString().orEmpty()
    require(dohUrl.isNotBlank()) { "kathttp3DohUrl must be provided for the ECH test app" }
    defaultConfig {
        applicationId = "dev.kathttp3.example"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-ech"
        buildConfigField("String", "DOH_URL", "\"${dohUrl.replace("\\\"", "\\\\\"")}\"")
    }
}
dependencies {
    implementation(project(":kathttp3"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    // Lifecycle 2.11 requires Android API 37, which is not yet available in
    // the reproducible GitHub Actions SDK image. Keep API 36 support here.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}
