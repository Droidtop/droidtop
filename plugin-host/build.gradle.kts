plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.pluginhost"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    // The IPluginRuntime binder contract (docs/SPEC.md 12a): :app talks to
    // the isolated plugin process only through this AIDL interface, never
    // by holding a reference to plugin code directly.
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Bundle payloads are tar.xz (docs/SPEC.md 12a), same tooling choice
    // droidtop already made for OCI layers (runtime-common's
    // OciFlattener): commons-compress needs org.tukaani:xz on the
    // classpath itself to read the xz codec, so it is declared here too.
    implementation(libs.commons.compress)
    implementation("org.tukaani:xz:1.9")
    testImplementation(libs.junit)
    // android.jar's org.json is a set of stubs that throw at runtime; the
    // main sourceset relies on the real implementation bundled in the
    // Android platform, but a plain JVM unit test needs a real jar (same
    // pattern as :library-core).
    testImplementation("org.json:json:20240303")
}
