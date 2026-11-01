plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Pin Android runtimes alongside Robolectric so host rendering works in read-only-home CI.
// Add -Pno5OceanApi33=true for the extra minimum-SDK Ocean test matrix.
val oceanApi33 = providers.gradleProperty("no5OceanApi33").orNull == "true"
val api29RenderAndroidRuntime by configurations.creating { isTransitive = false }
val nativeRenderAndroidRuntime by configurations.creating { isTransitive = false }
val api33RenderAndroidRuntime by configurations.creating { isTransitive = false }
val prepareNativeRenderAndroidRuntime by tasks.registering(Copy::class) {
    from(nativeRenderAndroidRuntime, api29RenderAndroidRuntime)
    if (oceanApi33) { from(api33RenderAndroidRuntime) }
    into(layout.buildDirectory.dir("robolectric-runtime"))
}

android {
    namespace = "com.no5.helm"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/sonar/CMakeLists.txt"); version = "3.31.6" } }

    defaultConfig {
        applicationId = "com.no5.helm"
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            arguments += "-DNO5_SONAR_FAULT_TEST=" + if (providers.gradleProperty("no5.sonarPrivateFixture").orNull == "true") "ON" else "OFF"
            arguments += "-DNO5_SONAR_COMPARISON=OFF"
            arguments += "-DNO5_SONAR_QUIET_BASELINE=OFF"
        } }
        targetSdk = 35
        versionCode = 206
        versionName = "0.8.134-sonar-start-depth-controls"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (providers.gradleProperty("no5.sonarCredentialProbe").orNull == "true") {
            testApplicationId = "com.no5.helm.sonarcredentialtest"
        }
    }

    if (providers.gradleProperty("no5SonarBench").orNull == "true") {
        buildTypes.create("benchmark") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".sonarquietbench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            externalNativeBuild.cmake.arguments += "-DNO5_SONAR_QUIET_BASELINE=ON"
        }
    }
    // A separate test package; never installs over the frozen production HELM.
    if (providers.gradleProperty("no5.sonarPrivateFixture").orNull == "true") {
        buildTypes.getByName("debug") { applicationIdSuffix = ".sonarfixture" }
        defaultConfig {
            testApplicationId = "com.no5.helm.sonarfixture.test"
        }
    }

    // Separate offscreen fixture tests depend on debug-only fault hooks. They
    // must never enter a production/release instrumentation compilation.
    if (providers.gradleProperty("no5.sonarPrivateFixture").orNull != "true") {
        sourceSets.getByName("androidTest").java.exclude(
            "**/SonarGpuParityMemoryDeviceTest.kt", "**/CurrentWindowRecoveryDeviceTest.java",
            "**/SonarReadReconnectDeviceTest.kt", "**/RecoveryLedgerDeviceTest.kt",
            "**/OriginClockElapsedDeviceTest.kt", "**/OwnProcessRelaunchDeviceTest.kt", "**/OwnRestartMechanismDeviceTest.kt",
            "**/OriginProofConsumerDeviceTest.kt"
        )
    }

    // Explicit read-only native TLS proof before first normal release resume.
    // Targets the real newly installed UID so existing Home signature checks apply.
    if (providers.gradleProperty("no5.originLiveReleaseProof").orNull == "true") {
        check(providers.gradleProperty("no5.instrumentReleaseProof").orNull == "true")
        defaultConfig { testApplicationId = "com.no5.helm.originproof" }
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/originProof/java"))
    }

    sourceSets.getByName("main").java.srcDir(rootProject.file("navigation/src/main/kotlin"))
    sourceSets.getByName("main").java.srcDir(rootProject.file("../keyboard-contract/src/main/kotlin"))
    sourceSets.getByName("androidTest").java.srcDir(layout.buildDirectory.dir("generated/special-keyboard-fixture"))
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("contracts/fixtures"))
    buildFeatures { compose = true; buildConfig = true }
    // Physical proof uses the installed release ABI without enabling debug code.
    if (providers.gradleProperty("no5.instrumentReleaseProof").orNull == "true") {
        testBuildType = "release"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(prepareNativeRenderAndroidRuntime)
            it.systemProperty("no5.renderOutputDir", rootProject.file("verification/renders").absolutePath)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.enabledSdks", if (oceanApi33) "29,33,35" else "29,35")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-runtime").get().asFile.absolutePath)
            // Real simulator/fixture changes must invalidate the Kotlin transport integration tests.
            it.inputs.files(rootProject.fileTree("simulator") { include("**/*.py", "**/*.json") })
            it.inputs.files(rootProject.fileTree("contracts") { include("**/*.json") })
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // Compress DEX in the directly distributed APK; keep attachments below 20 MB.
        dex { useLegacyPackaging = true }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin { jvmToolchain(17) }
if (providers.gradleProperty("no5.originLiveReleaseProof").orNull == "true") {
    kotlin.sourceSets.getByName("androidTest").kotlin.setSrcDirs(listOf("src/originProof/java"))
}


dependencies {
    if(providers.gradleProperty("no5SonarBench").orNull=="true")add("benchmarkImplementation",testFixtures(project(":domain")))
    implementation(project(":ui-state"))
    api29RenderAndroidRuntime("org.robolectric:android-all-instrumented:10-robolectric-5803371-i7")
    nativeRenderAndroidRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    api33RenderAndroidRuntime("org.robolectric:android-all-instrumented:13-robolectric-9030017-i7")
    implementation(project(":domain"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(testFixtures(project(":domain")))
    androidTestImplementation(testFixtures(project(":domain")))
    testImplementation("junit:junit:4.13.2")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Runs the production Android Canvas painters with Skia and packaged font/assets on the host.
    testImplementation("org.robolectric:robolectric:4.15.1")
    // Android supplies org.json at runtime; JVM protocol tests need the real parser.
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test-junit"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
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
tasks.matching { it.name == "preDebugAndroidTestBuild" || it.name == "preReleaseAndroidTestBuild" }
    .configureEach { dependsOn(prepareSpecialKeyboardFixture) }

// Shared sources only; this does not introduce a root build or project dependency.
apply(from = "../../release-info/android.gradle")

apply(from = "../../gateway-config/android.gradle")
android.sourceSets.getByName("main").java.srcDir("../../gateway-protocol/src/main/kotlin")

// Isolated Lumina candidate: shared native contracts, no root application.
android.sourceSets.getByName("main").java.srcDir("../../control-contract/src/main/kotlin")
android.sourceSets.getByName("main").java.srcDir("../../control-wire/src/main/java")
android.sourceSets.getByName("test").java.srcDir("../../control-contract/src/test/kotlin")
android.sourceSets.getByName("main").java.srcDir("../../app-control/src/main/kotlin")

android.sourceSets.getByName("main").java.srcDir("../../header-android/src/main/kotlin")
android.sourceSets.getByName("main").java.srcDir("../../header-native/src/main/kotlin")
android.sourceSets.getByName("main").assets.srcDir("../../header-native/src/main/assets")
android.sourceSets.getByName("test").java.srcDir("../../header-android/src/test/kotlin")
android.sourceSets.getByName("test").java.srcDir("../../header-native/src/test/kotlin")
android.sourceSets.getByName("test").java.srcDir("../../ui-state/src/robolectricTest/java")
android.sourceSets.getByName("test").java.srcDir("../../release-info/src/test/java")

// Read-only global header export; source groups preserve independent HELM build.
android.sourceSets.getByName("main").java.srcDir("../../header-consumer/src/main/java")
android.sourceSets.getByName("main").java.srcDir("../../header-consumer/src/main/kotlin")

dependencies { implementation(project(":control-ring")) }

// Shared static No5 header artwork; no root application or weather dependency.
android.sourceSets.getByName("main").java.srcDir("../../header-artwork/src/main/kotlin")
android.sourceSets.getByName("main").java.srcDir("../../ui-state/compose/src/main/kotlin")

// Build a pinned Rust/JNI source snapshot offline; never consume an adjacent Pi tree.
val sharedEqOutput = layout.buildDirectory.dir("shared-eq")
val buildSharedEq by tasks.registering(Exec::class) {
    inputs.dir(rootProject.file("../shared-eq"))
    inputs.file(rootProject.file("../tools/build_shared_eq.py"))
    outputs.dir(sharedEqOutput.map{it.dir("jni")})
    outputs.file(sharedEqOutput.map{it.file("receipt.json")})
    commandLine("python3",rootProject.file("../tools/build_shared_eq.py").absolutePath,"--output",sharedEqOutput.get().asFile.absolutePath)
}
android.sourceSets.getByName("main").jniLibs.srcDir(sharedEqOutput.map{it.dir("jni")})
tasks.matching{it.name.contains("merge")&&it.name.endsWith("JniLibFolders")}.configureEach{dependsOn(buildSharedEq)}
