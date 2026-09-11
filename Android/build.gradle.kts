// Versions are pinned deliberately conservatively. The one pairing you cannot change freely is
// Kotlin against the Compose compiler extension in `tiltfold/build.gradle.kts` and
// `sample/build.gradle.kts`: 1.9.22 goes with 1.5.8. The table lives at
// https://developer.android.com/jetpack/androidx/releases/compose-kotlin
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
