plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jiesa.xvideocatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jiesa.xvideocatcher"
        // Matches the host: X 12.13 declares minSdk 28, so a lower floor here could never be
        // exercised — the module only ever runs inside that process.
        minSdk = 28
        targetSdk = 35
        versionCode = 23
        versionName = "1.13.0"
    }

    buildFeatures { buildConfig = true }

    // A fixed signing key, so every build installs over the previous one. Android refuses an
    // update whose signature differs, and the auto-generated debug keystore is per-machine — on
    // a CI runner it is regenerated every run, which made each build uninstallable over the last.
    //
    // Supplied via env (CI secrets). Absent locally the build still compiles and tests, but
    // produces a debug-key APK that cannot upgrade in place; CI fails rather than publish one.
    val keystorePath = System.getenv("XVC_KEYSTORE_PATH")
    val keystorePass = System.getenv("XVC_KEYSTORE_PASSWORD")
    val keyAliasName = System.getenv("XVC_KEY_ALIAS") ?: "xvc"
    val keyPassword = System.getenv("XVC_KEY_PASSWORD") ?: keystorePass
    val hasFixedKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists() &&
        !keystorePass.isNullOrBlank()

    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = keyAliasName
                this.keyPassword = keyPassword
                // v1 kept for file managers that install via the legacy verifier.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // Shrinking stays off. R8 would rename this module's own classes, and the entry point in
        // assets/xposed_init is resolved by name at load time — a renamed entry class means
        // LSPosed silently loads nothing. Keeping names intact also keeps logcat readable, which
        // matters more than APK size for a payload with no dependencies.
        debug {
            isMinifyEnabled = false
            if (hasFixedKey) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (hasFixedKey) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            // Robolectric supplies real framework classes, so the URL/naming logic is tested
            // against actual behaviour instead of stubs.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Xposed API: compile-only by definition. The framework provides these classes at runtime;
    // packaging them would collide with the host's copy and break loading.
    compileOnly("de.robv.android.xposed:api:82")

    // Nothing else. HTTP is HttpURLConnection, storage is MediaStore, host access is reflection.
    // A DI/JSON/networking library here would ship into X's process for work the platform does.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
