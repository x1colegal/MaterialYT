plugins {
    `java-library`
    id("com.google.protobuf")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.jar {
    exclude("**/*.proto")
    includeEmptyDirs = false
}

dependencies {
    api("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    api("org.jsoup:jsoup:1.18.3")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    api("com.google.protobuf:protobuf-javalite:4.29.3")
    api("org.mozilla:rhino:1.8.1")
    api("org.mozilla:rhino-engine:1.8.1")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.29.3" }
    generateProtoTasks {
        all().configureEach {
            builtins { named("java") { option("lite") } }
        }
    }
}
