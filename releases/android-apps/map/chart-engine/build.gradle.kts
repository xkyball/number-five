plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.serialization") }
android {
    namespace = "com.no5.helm.chart_engine"
    compileSdk = 37
    androidResources { ignoreAssetsPattern = "!.svn:!.git:!CVS:!thumbs.db:!picasa.ini:!*.scc:.*:charts:*.000" }
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    ndkVersion = "29.0.14206865"
    defaultConfig {
        ndk { abiFilters += (providers.gradleProperty("no5.abis").orNull ?: "arm64-v8a").split(",") }
        externalNativeBuild { cmake {
            cppFlags += listOf("-std=c++17")
            arguments += "-DNO5_BUILD_TESTS=${if (providers.gradleProperty("no5.nativeFixtureTests").orNull == "true") "ON" else "OFF"}"
            arguments += "-DNO5_BATCH_SOLID_LINES=${if (providers.gradleProperty("no5.batchSolidLines").orNull == "true") "ON" else "OFF"}"
            arguments += "-DNO5_VIEWPORT_REUSE=${if (providers.gradleProperty("no5.viewportReuse").orNull == "true") "ON" else "OFF"}"
            arguments += "-DNO5_OUTLINE_PROBE=" + if (providers.gradleProperty("no5OutlineProbe").orNull == "true") "ON" else "OFF"
        } }
    }
    externalNativeBuild { cmake { path = file("CMakeLists.txt"); version = "3.31.6" } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":chart-api"))
    implementation(project(":chart-library"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation("junit:junit:4.13.2")
}

dependencies { implementation(project(":control-ring")) }
