// Test-only: the throwaway app the priv.packages rig check installs, grants, clears and uninstalls on a real console
// (app/src/androidTest PrivPackagesRigTest, Droidtop/tracker#130). It has no code and is never shipped or published;
// its APK is carried inside droidtop's androidTest APK only.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.droidtop.rigtest.throwaway"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.droidtop.rigtest.throwaway"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}
