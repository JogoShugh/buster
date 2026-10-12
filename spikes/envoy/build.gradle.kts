plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.artie:buster")
    // Envoy's generated API (ext_proc v3 included), so no protoc step.
    implementation("io.envoyproxy.controlplane:api:1.0.46")
    implementation("io.grpc:grpc-netty-shaded:1.66.0")
    implementation("io.grpc:grpc-stub:1.66.0")
    implementation("io.grpc:grpc-protobuf:1.66.0")
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.setSrcDirs(listOf("src"))
}

application {
    mainClass.set("spike.envoy.MainKt")
}
