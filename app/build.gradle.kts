plugins {
    id("com.android.application")
}

// Toolchain requirements (same as the verified upstream build):
//   JDK 21, Android SDK platform 35, libxposed API 101 jar.
// Copy api-101.0.1-classes.jar to app/libs/api-101.jar before building.
// It is compileOnly: LSPosed supplies io.github.libxposed.api at runtime.
android {
    namespace = "com.magicglass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magicglass"
        minSdk = 33
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.6-p1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // These three files must land at the APK root as
    // META-INF/xposed/{module.prop, java_init.list, scope.list}.
    // After the first build verify with: unzip -l app-release-unsigned.apk | grep xposed
    // If your AGP version drops java resources, add them to the APK root
    // manually and re-sign (the approach used by the upstream build.ps1).
    sourceSets.getByName("main").resources.setSrcDirs(listOf("src/main/resources"))
}

dependencies {
    compileOnly(files("libs/api-101.jar"))
}
