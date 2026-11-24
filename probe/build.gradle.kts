plugins {
    id("com.android.application")
}

// P0 probe: a separate installable module. Enable its scope in LSPosed,
// open the Settings pages, then read the "MagicGlassProbe" tag in the log.
android {
    namespace = "com.magicglass.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magicglass.probe"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-probe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets.getByName("main").resources.setSrcDirs(listOf("src/main/resources"))
}

dependencies {
    compileOnly(files("../app/libs/api-101.jar"))
}
