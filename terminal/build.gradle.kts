plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // android.util.Log / Base64 are stubs in JVM unit tests; return defaults instead of throwing (as upstream does).
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}

// Android lint's BidirectionalTextDetector crashes on JDK 17 daemons
// (List.removeLast NoSuchMethodError) when analysing upstream AndroidUtils.java.
android.lint {
    disable += "BidiSpoofing"
}
