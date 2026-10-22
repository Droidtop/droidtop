plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The engine's version is the pin's tag (aria2-engine.pin), the one place it is written.
val aria2EngineVersion: String = file("aria2-engine.pin").readLines()
    .first { it.startsWith("tag=") }.removePrefix("tag=").trim().removePrefix("v")

android {
    namespace = "dev.droidtop.net"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { buildConfig = true }
    val revision = System.getenv("VERSION_REVISION") ?: "0"
    defaultConfig { buildConfigField("String", "VERSION_NAME", "\"0.2.0-dev.$revision\"") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main") {
            // droidtop-agent's core for both ABIs (docs/SPEC.md 7o), built and
            // released by Droidtop/droidtop-agent and fetched against
            // agent-lib.pin (build-scripts/fetch-agent-lib.sh).
            jniLibs.srcDir("agent/jniLibs")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    // The download engine (docs/SPEC.md 12a): aria2-engine.pin, fetched by build-scripts/fetch-aria2-engine.sh.
    implementation("io.github.bi0shacker001:aria2-engine:${aria2EngineVersion}")
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
