// droidtop's side of the Murine launcher's hooks (Droidtop/tracker#921 item 2): Murine (the shell-default submodule,
// Droidtop/murine-launcher) calls app.murinelauncher.hooks.MurineHooks wherever droidtop changes what it does, and this
// module installs droidtop's implementations of them at start (ShellDefaultHooks.install, from DroidtopApplication).
// It grows one area per slice on #921; the droidtop code still inside the submodule moves here in the last one.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.droidtop.shell.standard.hooks"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    implementation(project(":shell-default"))
    implementation(project(":runtime-common"))
    implementation(project(":ui-shared"))
}
