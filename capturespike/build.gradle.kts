// Throwaway spike for issue #132 (stage 2). Never merged or released.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.fate_grand_automata.capturespike"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "io.github.fate_grand_automata.capturespike"
        // VpnService.Builder.setHttpProxy needs API 29.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
    }
}

dependencies {
    testImplementation(libs.junit4)
}
