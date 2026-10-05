plugins {
    alias(libs.plugins.android.library)
}

// Termux terminal-emulator v0.118.0 (Apache-2.0).
// Sources are copied verbatim from termux/termux-app; only build-script
// values are adapted to the DSHBox project (compileSdk/minSdk/ABI/JDK,
// publishing removed). Do not modify anything under src/.
android {
    namespace = "com.termux.emulator"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        // ---- LOCAL BUILD ADAPTATION (aarch64 container) ----
        // The Android NDK ships no aarch64-Linux host toolchain, so ndkBuild cannot
        // run here. Both externalNativeBuild blocks stay disabled so that
        // :app:assembleDebug can execute.
        // Historically libtermux.so was therefore NOT built and was absent from the
        // produced APK (terminal JNI unavailable). That is no longer the case as of
        // commit 657d6b2: the two prebuilt libraries are now tracked in the
        // repository (app/src/main/jniLibs/{arm64-v8a,x86_64}/libtermux.so,
        // byte-identical to the official v1.4.0 release APK) and ship with the APK
        // through app/src/main/jniLibs/**, so terminal JNI works even with ndkBuild
        // off. Do not conclude "libtermux.so is missing from the APK" from this block.
        // The three patched files are untouched; this is an unrelated build-script
        // adaptation for the local container build only.

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    // externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }  // disabled, see above

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
