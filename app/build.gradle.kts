plugins {
    id("com.android.application")
}

// Release signing comes from the environment (CI secrets or a local shell).
// Without it, release builds fall back to the debug key so they stay installable.
val releaseKeystore: String? = System.getenv("CHRONOTIF_KEYSTORE")

// Reserve the last code in each version's range for stable, above all of its nightlies.
val nightlyBuild = providers.gradleProperty("nightlyBuild").orNull?.toInt()?.also {
    require(it in 1..9998) { "nightlyBuild must be between 1 and 9998" }
}

android {
    namespace = "app.chronotif"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.chronotif"
        minSdk = 30
        targetSdk = 36
        // This is the next stable version. CI adds the nightly suffix and build number.
        versionName = "0.2.4"
        val baseCode = versionName!!.split('.').map(String::toInt).fold(0) { acc, p -> acc * 100 + p }
        versionCode = baseCode * 10000 + (nightlyBuild ?: 9999)
        if (nightlyBuild != null) versionName = "$versionName-nightly.$nightlyBuild"
        buildConfigField("boolean", "NIGHTLY", (nightlyBuild != null).toString())
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("CHRONOTIF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CHRONOTIF_KEY_ALIAS")
                keyPassword = System.getenv("CHRONOTIF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug installs next to stable/nightly instead of colliding with their signing key.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // No Google-encrypted dependency metadata blob in the APK.
    lint {
        // Target 36 on purpose until 37's behaviour changes are tested on real devices.
        disable += setOf("OldTargetApi", "GradleDependency")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
