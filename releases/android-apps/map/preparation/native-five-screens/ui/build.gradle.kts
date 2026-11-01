plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
val api29Runtime by configurations.creating { isTransitive = false }
val api35Runtime by configurations.creating { isTransitive = false }
val prepareRuntime by tasks.registering(Copy::class) {
    from(api29Runtime, api35Runtime)
    into(layout.buildDirectory.dir("robolectric-runtime"))
}
android {
    namespace = "com.no5.preparation.ui"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareRuntime)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-runtime").get().asFile.absolutePath)
        }
    }
}
// Resolve from this module so both the MAP root and independent harness see the contract.
androidComponents.onVariants { it.sources.kotlin?.addStaticSourceDirectory(project.file("../../../../keyboard-contract/src/main/kotlin").absolutePath) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api29Runtime("org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    api35Runtime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    api(project(":ui-state"))
    api("androidx.compose.material3:material3:1.4.0")
    api("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.activity:activity-compose:1.12.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    testImplementation("androidx.activity:activity-compose:1.12.4")
}

// Shared static No5 header artwork; no root application or weather dependency.
androidComponents.onVariants {
    it.sources.kotlin?.addStaticSourceDirectory(project.file("../../../../ui-state/compose/src/main/kotlin").absolutePath)
    it.sources.kotlin?.addStaticSourceDirectory(project.file("../../../../header-artwork/src/main/kotlin").absolutePath)
    it.sources.assets?.addStaticSourceDirectory(project.file("../../../../header-native/src/main/assets").absolutePath)
}
