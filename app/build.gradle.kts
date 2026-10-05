plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}
android {
    namespace = "com.pocketai.app"
    compileSdk = 36
    ndkVersion = "29.0.13113456"
    defaultConfig {
        applicationId = "com.pocketai.app.vulkanfix"
        minSdk = 33
        targetSdk = 36
        versionCode = 421
        versionName = "4.2.1-vulkanfix"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation(project(":lib"))
    implementation("io.noties.markwon:core:4.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
}

// Resolve the test Android runtimes through Gradle's writable cache. Robolectric's
// Maven resolver otherwise writes a lock in the host user's read-only directory.
val robolectricSdks = listOf("13-robolectric-9030017-i7", "15-robolectric-13954326-i7").mapIndexed { index, version ->
    configurations.create("robolectricSdk$index") { isTransitive = false }.also {
        dependencies.add(it.name, "org.robolectric:android-all-instrumented:$version")
    }
}
val prepareRobolectricSdks by tasks.registering(Copy::class) {
    from(robolectricSdks)
    into(layout.buildDirectory.dir("robolectric-sdks"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdks)
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdks").get().asFile.absolutePath)
}
