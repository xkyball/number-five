import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }

// AGP's new Kotlin source DSL has no per-file exclude. Compile a generated,
// byte-preserving subset so MAP need not depend on HELM's domain-only builder.
abstract class No5MapHeaderContractSources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val originalSources: DirectoryProperty
    @get:OutputDirectory
    abstract val generatedSources: DirectoryProperty
    @get:Inject
    abstract val fileOperations: FileSystemOperations

    @TaskAction fun prepare() {
        fileOperations.sync {
            from(originalSources)
            into(generatedSources)
            exclude("**/HelmHeaderSnapshotBuilder.kt")
        }
    }
}
val lifecycleAcceptance = providers.gradleProperty("no5.lifecycleAcceptance").orNull == "true"
val sourceCommit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
val api29Runtime by configurations.creating { isTransitive = false }
val api35Runtime by configurations.creating { isTransitive = false }
val prepareRuntime by tasks.registering(Copy::class) {
    from(api29Runtime, api35Runtime)
    into(layout.buildDirectory.dir("robolectric-runtime"))
}
android {
    namespace = "com.no5.helm.app"
    compileSdk = 37
    defaultConfig { minSdk = 29; applicationId = "com.no5.map"; targetSdk = 36; versionCode = 110; versionName = "0.5.42-memory-budget-v8"; buildConfigField("String", "SOURCE_COMMIT", "\"${sourceCommit.get()}\""); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += (providers.gradleProperty("no5.abis").orNull ?: "arm64-v8a").split(",") } }
    buildTypes {
        if (lifecycleAcceptance) create("lifecycleAcceptance") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".lifecycleacceptance"
            versionNameSuffix = "-isolated-lifecycle"
            matchingFallbacks += listOf("debug")
        }
    }
    if (lifecycleAcceptance) {
        testBuildType = "lifecycleAcceptance"
        sourceSets.getByName("androidTest").apply {
            kotlin.setSrcDirs(emptyList<String>())
            java.setSrcDirs(emptyList<String>())
            assets.setSrcDirs(emptyList<String>())
            resources.setSrcDirs(emptyList<String>())
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareRuntime)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-runtime").get().asFile.absolutePath)
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api29Runtime("org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    api35Runtime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation(project(":ui-shell"))
    implementation(project(":native-screens"))
    implementation(project(":chart-engine"))
    implementation(project(":chart-library"))
    implementation(project(":helm-client"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test.ext:junit:1.3.0"); androidTestImplementation("androidx.test:runner:1.7.0"); androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    androidTestImplementation(project(":native-screens"))
}

// Compile the existing native keyboard painter only into the test APK. No IME is enabled.
val prepareSpecialKeyboardFixture by tasks.registering(Copy::class) {
    val baseline = providers.gradleProperty("no5.keyboardBaseline").orNull == "true"
    from(rootProject.file("../systemwindow-ime-test/" + if (baseline)
        "provenance/recovered-original/NarrowTestIme.kt" else
        "src/main/java/com/no5/systemwindowtest/NarrowTestIme.kt"))
    into(layout.buildDirectory.dir("generated/special-keyboard-fixture"))
    filteringCharset = "UTF-8"
    // Only the unused service-theme resource binding changes in this test compilation.
    filter { line: String -> line.replace("com.no5.systemwindowtest.R.style.Theme_No5TestIme", "android.R.style.Theme_InputMethod") }
}
tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach { dependsOn(prepareSpecialKeyboardFixture) }
if (!lifecycleAcceptance) androidComponents.onVariants { it.androidTest?.sources?.kotlin?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/special-keyboard-fixture").get().asFile.absolutePath) }

// Shared sources only; this does not introduce a root build or project dependency.
if (!lifecycleAcceptance) apply(from = "../../release-info/android.gradle")
if (!lifecycleAcceptance) apply(from = "../../gateway-config/capability.gradle")

// Isolated Lumina candidate: shared native contracts, no root application.
android.sourceSets.getByName("main").kotlin.srcDir("../../control-contract/src/main/kotlin")
android.sourceSets.getByName("main").kotlin.srcDir("../../app-control/src/main/kotlin")

android.sourceSets.getByName("main").kotlin.srcDir("../../header-android/src/main/kotlin")
androidComponents.onVariants { variant ->
    val prepareHeader = tasks.register<No5MapHeaderContractSources>(
        "prepare${variant.name.replaceFirstChar { it.uppercase() }}No5HeaderContract"
    ) {
        originalSources.set(rootProject.layout.projectDirectory.dir("../header-contract/src/main/kotlin"))
        generatedSources.set(layout.buildDirectory.dir("generated/no5-header-contract/${variant.name}"))
    }
    requireNotNull(variant.sources.kotlin).addGeneratedSourceDirectory(prepareHeader) { it.generatedSources }
}

dependencies { implementation(project(":control-ring")) }

// Read-only renderer diagnostic predicates/collector; never compiled into a production variant.
if (!lifecycleAcceptance) androidComponents.onVariants { variant ->
    variant.androidTest?.sources?.kotlin
        ?.addStaticSourceDirectory(rootProject.file("renderer-test-common/kotlin").absolutePath)
}
