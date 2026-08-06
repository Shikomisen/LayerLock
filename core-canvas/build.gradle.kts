plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shikomisen.layerlock.canvas"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":scene-schema"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    api(libs.coil.compose)
    api(libs.coil.gif)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.ui)

    implementation(libs.mlkit.segmentation.selfie)

    testImplementation(libs.junit)
}
