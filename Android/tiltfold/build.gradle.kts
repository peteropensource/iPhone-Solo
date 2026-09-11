plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tiltfold"
    compileSdk = 34

    defaultConfig {
        // 26 is what the rest of the library needs. The blur ladder needs 31; below that the
        // effect degrades to a fold and a dissolve with no defocus. See the README.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Must match the Kotlin version in the root build file. 1.5.8 goes with Kotlin 1.9.22.
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    testOptions {
        // TiltFoldMath.kt and TiltFoldConfig.kt touch no Android API at all, so the unit tests
        // are plain JVM and need neither Robolectric nor a device. If a test ever does reach an
        // android.* class, this keeps it from throwing "not mocked" and hides a real problem
        // instead; better to move the logic somewhere pure than to rely on it.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.core:core-ktx:1.12.0")
    // rememberTiltMonitor() registers and unregisters against the lifecycle. compose-ui already
    // pulls lifecycle-runtime in transitively, but the dependency is declared here because this
    // module names Lifecycle and LifecycleEventObserver directly.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
