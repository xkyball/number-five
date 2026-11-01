plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
val api29Runtime by configurations.creating { isTransitive = false }
val api33Runtime by configurations.creating { isTransitive = false }
val api35Runtime by configurations.creating { isTransitive = false }
val prepareRuntime by tasks.registering(Copy::class) {
    from(api29Runtime, api33Runtime, api35Runtime)
    into(layout.buildDirectory.dir("robolectric-runtime"))
}
android {
    namespace = "com.no5.helm.ui_shell"
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
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED", "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":ui-state"))
    api29Runtime("org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    api33Runtime("org.robolectric:android-all-instrumented:13-robolectric-9030017-i7")
    api35Runtime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    api(project(":chart-api"))
    api(project(":chart-library"))
    api(project(":helm-model"))
    implementation(project(":control-ring"))
    implementation(project(":native-screens"))
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui:1.10.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    testImplementation("androidx.activity:activity-compose:1.12.4")
}

// Read-only renderer diagnostic predicates/collector; never compiled into a production variant.
androidComponents.onVariants { variant ->
    variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.sources?.kotlin
        ?.addStaticSourceDirectory(rootProject.file("renderer-test-common/kotlin").absolutePath)
}
