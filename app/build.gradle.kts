plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

/*
 * Android will only install a new version over an old one when both were signed
 * with the same key. A debug build is signed with the throwaway keystore Gradle
 * makes on the spot, and a CI runner is a fresh machine every time — so every
 * build came out signed by a different key, every install demanded an uninstall
 * first, and an uninstall takes the sign-in details and the viewing history
 * with it.
 *
 * With ANDROID_KEYSTORE_* set, the build signs with one key that stays the same
 * from build to build and updates install straight over. Without them nothing
 * changes, so a checkout with no secrets still builds.
 */
val signingStore: String? = System.getenv("ANDROID_KEYSTORE_FILE")
val signingStorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val signingAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")
val stableSigning = !signingStore.isNullOrBlank() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingAlias.isNullOrBlank() &&
    file(signingStore!!).exists()

android {
    namespace = "com.mlayfer.iptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mlayfer.iptv"
        minSdk = 21
        targetSdk = 35
        // Each build is a version of its own, so a phone can tell an update
        // from a reinstall. A local build stays at 1.
        versionCode = (System.getenv("ANDROID_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (stableSigning) {
            create("stable") {
                storeFile = file(signingStore!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                // A key with no password of its own uses the store's.
                keyPassword = signingKeyPassword?.takeIf { it.isNotBlank() } ?: signingStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // The build people actually install, so it is the one that needs a
            // key that outlives the machine that built it.
            if (stableSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Media3 and AndroidX use APIs newer than minSdk allows on old devices.
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        // Media3's player APIs are all marked @UnstableApi; opting in once here
        // beats annotating every call site.
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }

    testOptions {
        unitTests {
            // Robolectric renders against the app's real resources and theme.
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        // So the app can say which build it is. Without that, "is this the new
        // one?" is a question neither of us can answer from a photograph.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    // org.json ships with Android, but the unit-test android.jar only stubs it.
    testImplementation(libs.json)

    // Screens rendered to PNG on the JVM. No emulator and no device, so it runs
    // in the same job as the unit tests and finishes in seconds.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
