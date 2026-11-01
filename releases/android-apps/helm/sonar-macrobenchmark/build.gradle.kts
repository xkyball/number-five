plugins { id("com.android.test"); id("org.jetbrains.kotlin.android") }
android {
 namespace="com.no5.sonar.benchmark"
 compileSdk=35
 defaultConfig { minSdk=29; targetSdk=35; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }
 targetProjectPath=":app"
 buildTypes { create("benchmark") { isDebuggable=true;signingConfig=signingConfigs.getByName("debug");matchingFallbacks+=listOf("release") } }
 experimentalProperties["android.experimental.self-instrumenting"]=true
}
kotlin {jvmToolchain(17)}
dependencies {
 implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
 implementation("androidx.test.ext:junit:1.2.1")
 implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
