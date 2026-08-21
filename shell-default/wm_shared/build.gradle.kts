plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.android.wm.shell.shared"

    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    sourceSets {
        named("main") {
            java.setSrcDirs(listOf("src"))
            manifest.srcFile("AndroidManifest.xml")
            res.setSrcDirs(listOf("res"))
        }
    }
    kotlin {
        jvmToolchain(21)//1.8（8）
    }
}

// Added by droidtop's fork (not upstream Murine) — see shell-default/
// build.gradle's own header comment for why this can't be injected
// centrally from there.
val frameworkStubs = configurations.detachedConfiguration(
    dependencies.create("com.github.alesimula:android-framework-stubs:16-r2")
).apply { isTransitive = false }

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-animation:1.0.0")
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.1.0")
    implementation("androidx.window:window:1.5.1")
    implementation("javax.inject:javax.inject:1")
    implementation(libs.kotlinx.coroutines)
    compileOnly(project(":flags"))
}
// A plain compileOnly dependency does NOT win against the SDK's own
// (redacted) android.jar for android.* symbols — see shell-default/
// build.gradle's own comment on this. Prepending the stub jar onto each
// compile task's classpath/libraries is what actually makes it resolve.
tasks.withType<JavaCompile>().configureEach {
    doFirst {
        classpath = files(frameworkStubs.resolve()) + classpath
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    doFirst {
        libraries.from(files(frameworkStubs.resolve()))
    }
}
