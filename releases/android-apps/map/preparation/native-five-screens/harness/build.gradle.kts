plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.no5.preparation.harness"
    compileSdk = 37
    defaultConfig { applicationId = "com.no5.preparation.harness"; minSdk = 29; targetSdk = 36; versionCode = 3; versionName = "0.3-dock-gesture-contract"; ndk { abiFilters += "arm64-v8a" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":ui"))
    implementation("androidx.activity:activity-compose:1.12.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
}

// Shared sources only; this does not introduce a root build or project dependency.
apply(from = "../../../../release-info/android.gradle")
