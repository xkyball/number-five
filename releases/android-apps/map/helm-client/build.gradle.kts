plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.serialization") }
android {
    namespace = "com.no5.helm.helm_client"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":helm-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

apply(from = "../../gateway-config/android.gradle")
apply(from = "gateway-sources.gradle")
