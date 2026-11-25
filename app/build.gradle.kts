plugins {
    id("com.android.application")
}

// Release signing comes from the environment (CI secrets or a local shell).
// Without it, release builds fall back to the debug key so they stay installable.
val releaseKeystore: String? = System.getenv("CHRONOTIF_KEYSTORE")

// PostHog (EU) project key for Usage data, compiled into Stable and Nightly builds only.
val USAGE_DATA_KEY = "phc_zWiU4QUsQFcsXwSrzyUB9BFvagv2B2YE4tLq5xpjtEEr"

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
        versionName = "0.3.1"
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
            // The debug build sends no Usage data: test runs on a developer's phone aren't use.
            buildConfigField("String", "USAGE_DATA_KEY", "\"\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // PostHog's project key for Usage data (ADR 0016). It can only send events, not read
            // them, so it's public like the rest of the code.
            buildConfigField("String", "USAGE_DATA_KEY", "\"$USAGE_DATA_KEY\"")
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

    lint {
        // Target 36 on purpose until 37's behaviour changes are tested on real devices.
        disable += setOf("OldTargetApi", "GradleDependency")
    }

    // No Google-encrypted dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}

/**
 * Bundles the website's release notes into the APK, so What's new can show them offline after an
 * update. One file, website/src/releases.json, is the source for both.
 */
abstract class ReleaseNotesAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = output.get().asFile
        out.mkdirs()
        source.get().asFile.copyTo(File(out, "releases.json"), overwrite = true)
    }
}

val releaseNotesAsset = tasks.register<ReleaseNotesAsset>("releaseNotesAsset") {
    source.set(rootProject.layout.projectDirectory.file("website/src/releases.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(releaseNotesAsset, ReleaseNotesAsset::output)
    }
}
