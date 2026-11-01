plugins { id("com.android.library") }
android {
    namespace = "com.no5.control.commands"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
apply(from = "../gateway-config/control-ring.gradle")
