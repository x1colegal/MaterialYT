plugins {
    id("com.android.library")
    id("com.google.protobuf") version "0.9.5"
}

android {
    namespace = "com.x1colegal.materialyt.sabr"
    compileSdk = 36

    defaultConfig { minSdk = 21 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        // The original SmartTube module targets its ExoPlayer 2.10 fork. MaterialYT uses the
        // protocol/parser core with a small ExoPlayer 2.19 DataSource bridge instead.
        java.exclude("com/google/android/exoplayer2/source/sabr/DefaultSabrChunkSource.java")
        java.exclude("com/google/android/exoplayer2/source/sabr/EventSampleStream.java")
        java.exclude("com/google/android/exoplayer2/source/sabr/PlayerEmsgHandler.java")
        java.exclude("com/google/android/exoplayer2/source/sabr/SabrChunkSource.java")
        java.exclude("com/google/android/exoplayer2/source/sabr/SabrMediaPeriod.java")
        java.exclude("com/google/android/exoplayer2/source/sabr/SabrMediaSource.java")
        java.exclude("com/google/android/exoplayer2/source/sabr/parser/adapter/**")
        java.exclude("com/google/android/exoplayer2/source/sabr/parser/misc/SabrExtractorInput.java")
        java.exclude("com/google/android/exoplayer2/source/sabr/manifest/SabrManifestParser.java")
    }
}

dependencies {
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")
    implementation("com.google.protobuf:protobuf-javalite:4.29.3")
    compileOnly("org.checkerframework:checker-qual:3.49.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.29.3" }
    generateProtoTasks {
        all().configureEach {
            builtins { create("java") { option("lite") } }
        }
    }
}
