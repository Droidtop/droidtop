plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Hilt: the vendored gamenative tree (:runtime-windows) is Hilt-built
    // -- its activities are @AndroidEntryPoint -- and Hilt requires the
    // FINAL application module to carry the plugin and the annotated
    // Application class (DroidtopApplication) for their object graph to
    // exist at runtime.
    alias(libs.plugins.google.ksp)
    alias(gn.plugins.dagger.hilt)
}

android {
    namespace = "dev.droidtop.app"
    // shell-default (the forked-in Murine Launcher) and its sub-modules
    // compile against 36 — several of their AndroidX dependencies
    // (recyclerview 1.4.0, compose material3/animation 1.8.1+) require
    // consumers to compile against 35+ too, confirmed via a real
    // :app:assembleDebug AAR-metadata check failure. Every other droidtop
    // module stays on its own existing compileSdk; only the final
    // linking module (:app) has to be >= the highest compileSdk among
    // everything it depends on.
    compileSdk = 36

    // A plain incrementing integer, not a git SHA — CI passes the number
    // of commits reachable from the commit it builds (see
    // build-scripts/release_channel.py) via VERSION_REVISION; local builds
    // fall back to "0" since there's no meaningful revision counter
    // outside CI.
    val versionRevision = System.getenv("VERSION_REVISION") ?: "0"

    androidResources {
        // Must match shell-gamepad's own override (see that module's
        // build.gradle.kts for the full real story): AAPT's default
        // pattern strips `<dir>_*` asset directories, and the final APK's
        // own asset-merge step applies THIS module's pattern -- both
        // modules need it or the app-level merge re-strips what the
        // library kept.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
        // Archives that are already compressed are stored, not deflated
        // again. The vendored Windows runtime ships 27 .tzst assets, 59 MB
        // that deflate to 100% of their size, and packaging deflated them
        // in memory once per APK (three ABI splits, two build types): the
        // frame CI's packaging OutOfMemoryError dies in is that deflate
        // (zipflinger Compressor.deflate, Droidtop/tracker#283). The APK
        // is the same size either way.
        noCompress += listOf("tzst", "txz", "zst", "xz")
    }

    defaultConfig {
        applicationId = "dev.droidtop.app"
        minSdk = 26
        targetSdk = 34
        // The commit's revision number (VERSION_REVISION): a plain monotonic integer,
        // so Android itself refuses downgrades and AppSelfUpdate can answer
        // "is this newer" numerically against the published release-info.
        // Local builds without the env var stay at 1.
        versionCode = versionRevision.toIntOrNull()?.coerceAtLeast(1) ?: 1
        versionName = "0.2.0-dev.$versionRevision"
        // The ABIs droidtop ships (arm64-v8a for real hardware, x86_64 for
        // x86 devices and emulators) are set by `splits` below and the
        // packaging excludes, not an ndk abiFilters block: AGP refuses the
        // two together.
    }

    // One universal APK with both ABIs is what the release channel
    // publishes (docs/SPEC.md 10b). The per-ABI APKs exist because an app's
    // native libraries are ONE ABI's, chosen at install, and Android-x86
    // derivatives with ARM translation (the BlueStacks rig) choose
    // arm64-v8a for the universal APK: their package manager's ABIPicker
    // takes the x86_64 set only when it holds every arm64 library, and
    // gamenative's prebuilt natives exist for arm64 alone. Everything droidtop
    // EXECUTES (proot and its loaders, crane) must be the kernel's own ABI,
    // so on such a device Desktop mode needs the x86_64 APK (docs/SPEC.md 3).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // Only configured when the signing secrets are present (real CI runs,
    // via SIGNING_KEYSTORE_PATH/SIGNING_STORE_PASSWORD/SIGNING_KEY_ALIAS/
    // SIGNING_KEY_PASSWORD in the workflow) — local/contributor builds fall
    // back to Android's default auto-generated debug keystore instead of
    // failing when these aren't set.
    //
    // Without this, every CI run signs with a brand-new ephemeral debug
    // keystore (GitHub Actions runners are fresh VMs with no persisted
    // ~/.android/debug.keystore across runs), so every past "latest"
    // release had a different signing identity and users had to fully
    // uninstall before installing any newer build. One persistent keystore
    // (generated once, stored only as a GitHub Actions secret — see
    // .signing/ in .gitignore) fixes that.
    val signingKeystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (signingKeystorePath != null) {
            create("droidtop") {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (signingKeystorePath != null) {
                signingConfig = signingConfigs.getByName("droidtop")
            }
        }
        // What CI publishes and people install (SPEC 10b). Until build 556
        // that was the debug variant, and on the console everything was
        // slow: a debuggable app is never compiled ahead of time (the
        // installed package sat at dexopt status=extract), ART runs it
        // without inlining so a debugger can attach anywhere, and the
        // baseline profiles Compose ships are not installed. None of that is
        // about droidtop's code; it is what "debug" means on Android.
        // Signed with the same key, so it installs over a debug "latest".
        // Code shrinking is on (R8, SPEC 10b): the release APK carried 114 MB
        // of unshrunk dex and packaging it ran the daemon out of heap
        // (Droidtop/tracker#283). It shrinks and does not obfuscate or
        // optimise, see app/proguard-rules.pro. Resource shrinking stays off:
        // resources are a small part of the APK (the assets are not touched by
        // it) and the launcher and gamenative look some up by name. Debug
        // stays unminified.
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
                "../shell-default/proguard.pro",
                "../shell-default/proguard.flags",
                "../shell-default/systemUIPluginCore/proguard.flags",
            )
            signingConfig = if (signingKeystorePath != null) {
                signingConfigs.getByName("droidtop")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        jniLibs {
            // Dependencies (zstd-jni, libarchive, ...) carry 32-bit
            // and other ABIs droidtop does not ship; the universal APK keeps
            // exactly arm64-v8a + x86_64, as the old ndk abiFilters did.
            excludes += listOf("lib/armeabi/**", "lib/armeabi-v7a/**", "lib/x86/**", "lib/mips/**", "lib/mips64/**", "lib/riscv64/**")
            // The vendored gamenative runtime does not just dlopen its
            // native libraries, it hands their paths to other processes:
            // BionicProgramLauncherComponent LD_PRELOADs libevshim.so out
            // of ApplicationInfo.nativeLibraryDir, and that directory is
            // empty unless the libraries are extracted at install time.
            // AGP's default (uncompressed, mapped straight out of the
            // APK) is the better default for a normal app and the wrong
            // one here -- upstream sets exactly this for the same reason.
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    /**
     * The minSdk gate. droidtop's minSdk is 26 and every module agrees on
     * that, but nothing was checking it, so two API-30/33-only calls
     * shipped and crashed the app outright on the Android 9 rig
     * (2026-09-11): an ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
     * intent that does not exist below API 30, and InputStream.readNBytes,
     * which is API 33+. Lint's own NewApi/InlinedApi detectors find
     * exactly this class of bug, and understand Build.VERSION guards, so a
     * correctly guarded call still passes.
     *
     * checkDependencies is on because the crash was not in this module:
     * everything droidtop ships in the APK is linted from here, one task
     * and one report, rather than eleven per-module lint blocks that drift
     * apart. checkOnly keeps it to this one question -- the rest of lint's
     * catalogue over a Launcher3 fork is a separate, much larger job and
     * not what this gate is for.
     */
    lint {
        checkOnly += listOf("NewApi", "InlinedApi")
        checkDependencies = true
        abortOnError = true
        warningsAsErrors = false
        // The gate runs on the debug variant, in its own workflow
        // (android-checks.yml). The release build CI publishes must not run
        // lint a second time (lintVitalRelease): it would double the work
        // and put a check back in front of the artifact (SPEC 10b).
        checkReleaseBuilds = false
        // Scope, one mechanism: a baseline that holds ONLY the findings in
        // trees droidtop vendors rather than writes -- the gamenative tree
        // (vendor/gamenative/..., compiled into :runtime-windows by
        // srcDir) and shell-default's vendored AOSP sub-libraries,
        // :WMShared (shell-default/wm_shared), :msdl
        // (shell-default/msdllib) and :Shared (shell-default/shared), so
        // lint reports them against this gate like any other source.
        // Those trees are upstream code we sync, not code we write, and
        // fixing their API-27..34 calls in place would be rewritten by the
        // next vendor sync; a per-source-set exclusion would have hidden
        // them forever instead. A baseline is the honest middle: the
        // findings recorded in the file are known and accepted, and ANY
        // new one -- a call the next sync brings in, or one we add
        // ourselves while porting -- is not in the file and still fails
        // the build. Everything droidtop writes -- shell-default/src, the
        // launcher fork's own sources, included -- is deliberately NOT in
        // the baseline and must be fixed in the code.
        baseline = file("lint-baseline.xml")
    }
}

// Two protobuf runtimes meet in this app: shell-default's Launcher3
// protos are generated LITE and pull protobuf-javalite, while the
// vendored gamenative tree (JavaSteam's Steam protos) needs full
// protobuf-java. Both jars ship the same com.google.protobuf classes,
// which is exactly the duplicate-class failure this resolves. The full
// runtime is the documented superset -- lite-generated code runs on it
// unchanged -- so one copy of the full artifact stands in for both. Its
// version is the catalog's protobufJavalite entry, so the substituted
// runtime cannot drift from the release the launcher protos were built against.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.google.protobuf:protobuf-javalite"))
            .using(module("com.google.protobuf:protobuf-java:${libs.versions.protobufJavalite.get()}"))
    }
}

dependencies {
    implementation(project(":net-core"))

    // Hilt runtime + compiler for this, the application module -- see the
    // plugins block comment. Versions from gamenative's own catalog so
    // they track the fork exactly.
    implementation(gn.bundles.hilt)
    ksp(gn.hilt.android.compiler)
    implementation(project(":runtime-common"))
    implementation(project(":host-bridge"))
    implementation(project(":runtime-windows"))
    implementation(project(":runtime-linux-root"))
    implementation(project(":runtime-linux-noroot"))
    implementation(project(":input-seat"))
    implementation(project(":input-keyboard"))
    implementation(project(":library-core"))
    implementation(project(":stores"))
    implementation(project(":plugin-host"))
    implementation(project(":display"))
    implementation(project(":shell-default"))
    implementation(project(":shell-desktop"))
    implementation(project(":shell-gamepad"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ViewTreeLifecycleOwner/ViewTreeSavedStateRegistryOwner (SecondScreenPresentation
    // hosting Compose inside an android.app.Presentation, which isn't a LifecycleOwner/
    // SavedStateRegistryOwner on its own the way an Activity is) -- not resolvable
    // transitively via lifecycle-runtime-ktx alone, needs the base artifacts explicitly.
    implementation(libs.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // OnboardingActivity's own OnboardingRun (androidx.lifecycle.ViewModel
    // + `by viewModels()`): the onboarding run's answers have to outlive
    // the Activity across a rotation. Both artifacts arrive transitively
    // through androidx.activity, and both are named here for the same
    // reason the lifecycle-runtime base artifacts above are -- a
    // transitive version is not a dependency declaration.
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.savedstate)
    implementation(libs.savedstate.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // The QR codes of Steam's sign-in and the scraper key setup (ui/QrCode.kt).
    implementation(libs.zxing.core)
    // Installs the baseline profiles that Compose and the other AndroidX
    // libraries ship inside their AARs, so their hot paths are compiled at
    // install time instead of interpreted on first use. Release builds only
    // benefit; a debuggable app is never compiled from a profile.
    implementation(libs.androidx.profileinstaller)

    // Real fix (DroidtopApplication.kt): coil-svg was already a
    // shell-gamepad dependency, but nothing ever registered
    // SvgDecoder.Factory() with a real ImageLoader -- adding the
    // dependency alone doesn't make Coil3 use it, it needs a
    // SingletonImageLoader.Factory, which has to live in the actual
    // Application class (this module, not a library module).
    implementation(libs.coil.compose)
    implementation(libs.coil.android)
    implementation(libs.coil.svg)

    // The UPDATE_NOW trigger's guard and bypass are decided by pure
    // functions (UpdateNow/AppSelfUpdate.mayCheck), so :app carries plain
    // JVM tests now; CI runs :app:testDebugUnitTest with the rest.
    testImplementation(libs.junit)
    // OnboardingRun writes and reads the saved first run as org.json, and
    // the resume-and-migrate tests exercise that path; android.jar's
    // org.json is a throwing stub in JVM tests, so the real library backs
    // them (the same reasoning and version as shell-gamepad's test classpath).
    testImplementation(libs.json.v20240303)
}

// CI builds both variants in one invocation, and AGP then packages the
// release and debug APKs side by side in the one daemon heap: the
// packaging OutOfMemoryError of Droidtop/tracker#283 hit both splitter
// runs at once ("both IncrementalSplitterRunnable actions failed
// together"). Only the two packaging tasks are ordered; everything before
// them still runs in parallel.
tasks.matching { it.name == "packageDebug" }.configureEach {
    mustRunAfter("packageRelease")
}
