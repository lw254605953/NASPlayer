plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    api("androidx.media3:media3-common:1.9.1")
    api("androidx.media3:media3-decoder:1.9.1")
    implementation("androidx.media3:media3-extractor:1.9.1")
    implementation("androidx.media3:media3-exoplayer:1.9.1")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    compileOnly("org.checkerframework:checker-qual:3.42.0")
}
