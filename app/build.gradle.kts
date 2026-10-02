import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.x1colegal.materialyt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.x1colegal.materialyt"
        minSdk = 21
        targetSdk = 36
        versionCode = 19
        versionName = "1.3.5"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

val generateEjsAssets by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/ejsAssets")
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile.apply { mkdirs() }
        val files = mapOf(
            "yt.solver.lib.min.js" to Pair("https://github.com/yt-dlp/ejs/releases/download/0.8.0/yt.solver.lib.min.js", "c55987fe697e5b9ee18830163f7af85327e9bb5c3e674b969d38c8d205eaa577"),
            "yt.solver.core.min.js" to Pair("https://github.com/yt-dlp/ejs/releases/download/0.8.0/yt.solver.core.min.js", "18da6ce0758b416e7ae645084f4f8801f9f9d59d6c477c05eaa0ff94ebd8cc00")
        )
        files.forEach { (name, source) ->
            val destination = target.resolve(name)
            if (!destination.exists()) URI(source.first).toURL().openStream().use { input -> destination.outputStream().use(input::copyTo) }
            val digest = MessageDigest.getInstance("SHA-256").digest(destination.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == source.second) { "Unexpected SHA-256 for $name: $digest" }
        }
    }
}

android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/ejsAssets"))
tasks.named("preBuild").configure { dependsOn(generateEjsAssets) }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.2")
    // This Compose line retains Android 5.0 (API 21) support.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation(files("libs/NewPipeExtractor-v0.26.5.jar"))
    // NewPipeExtractor transitive dependencies
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.mozilla:rhino-engine:1.7.15")
    implementation("com.google.protobuf:protobuf-javalite:4.29.3")
    
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")
    implementation("com.google.android.exoplayer:extension-okhttp:2.19.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
