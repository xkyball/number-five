plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.2.10"
}
val api29Runtime by configurations.creating { isTransitive = false }
val api35Runtime by configurations.creating { isTransitive = false }
val prepareRuntime by tasks.registering(Copy::class) {
    from(api29Runtime, api35Runtime)
    into(layout.buildDirectory.dir("robolectric-runtime"))
}
android {
    namespace = "com.no5.home"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.no5.home"
        minSdk = 29
        targetSdk = 35
        versionCode = 31
        versionName = "0.5.12-ui-parity-v8"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareRuntime)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-runtime").get().asFile.absolutePath)
            it.systemProperty("no5.renderOutputDir", rootProject.file("verification/renders").absolutePath)
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":ui-state"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    api29Runtime("org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    api35Runtime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
}

// Shared sources only; this does not introduce a root build or project dependency.
apply(from = "../release-info/android.gradle")
apply(from = "../gateway-config/android.gradle")
android.sourceSets.getByName("test").java.srcDir("../release-info/src/test/java")

android.sourceSets.getByName("main").java.srcDir("../helm/domain/src/main/kotlin")

android.sourceSets.getByName("main").java.srcDir("../gateway-protocol/src/main/kotlin")

// Isolated Lumina candidate: shared native contracts, no root application.
android.sourceSets.getByName("main").java.srcDir("../control-contract/src/main/kotlin")
android.sourceSets.getByName("main").java.srcDir("../app-control/src/main/kotlin")

android.sourceSets.getByName("main").java.srcDir("../header-contract/src/main/kotlin")
android.sourceSets.getByName("main").java.srcDir("../header-android/src/main/kotlin")

dependencies { implementation(project(":control-ring")) }

// Shared static No5 header artwork; no root application or weather dependency.
android.sourceSets.getByName("main").java.srcDir("../header-artwork/src/main/kotlin")
android.sourceSets.getByName("main").assets.srcDir("../header-native/src/main/assets")
