plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skyautoplayer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.skyautoplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        // W27: the promote path is covered by a plain JVM test, and the seeder
        // logs through android.util.Log - without this every call throws
        // "Method i in android.util.Log not mocked". Defaults are returned
        // instead; no dependency is added.
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
