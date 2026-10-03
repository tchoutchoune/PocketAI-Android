plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}
android {
    namespace = "com.pocketai.app"
    compileSdk = 36
    ndkVersion = "29.0.13113456"
    defaultConfig {
        applicationId = "io.github.tchoutchoune.pocketai.preview"
        minSdk = 33
        targetSdk = 36
        versionCode = 451
        versionName = "4.5.1-vulkan-probes"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    val previewKeystore = rootProject.file(".ci-signing/preview-debug.keystore")
    signingConfigs {
        if (previewKeystore.exists()) {
            create("preview") {
                storeFile = previewKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            if (previewKeystore.exists()) signingConfig = signingConfigs.getByName("preview")
        }
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
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
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
