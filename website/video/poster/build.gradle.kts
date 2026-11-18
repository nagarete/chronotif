plugins {
    id("com.android.application") version "9.4.1"
}

// One code base, one made-up app per flavor. Packages sit under app.chronotif.demo., the only
// ones the debug build shows while demo mode is on.
data class DemoApp(val id: String, val label: String, val color: String)

val apps = listOf(
    DemoApp("chat", "Murmur", "#2EAD6B"),
    DemoApp("parcel", "Parcelly", "#E8813A"),
    DemoApp("calendar", "Daybook", "#4C6FE0"),
    DemoApp("bank", "Fernbank", "#14806F"),
    DemoApp("music", "Hum", "#D9435E"),
    DemoApp("steps", "Stride", "#8A56D6"),
)

android {
    namespace = "app.chronotif.demo"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    flavorDimensions += "app"
    productFlavors {
        apps.forEach { app ->
            create(app.id) {
                dimension = "app"
                applicationId = "app.chronotif.demo.${app.id}"
                resValue("string", "app_name", app.label)
                resValue("color", "brand", app.color)
                buildConfigField("String", "DEMO_APP", "\"${app.id}\"")
            }
        }
    }
    buildFeatures { buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
