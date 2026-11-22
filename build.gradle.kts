// Root build file — no build logic here, plugins are applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Real use: library-core's own RomDatabase (Room, persistent ROM
    // scan cache) -- was already declared in the version catalog but
    // unused until now.
    alias(libs.plugins.google.ksp) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // droidtop's own baseline profile (:baselineprofile records it, :app carries it; docs/SPEC.md 10b).
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// The classes Compose may treat as stable (compose-stability.conf, Droidtop/tracker#941), for every module that uses
// Compose.
subprojects {
    val module = this
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        module.extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
        }
    }
}

// Robolectric fetches its Android jars from Google's mirror of Maven Central: Maven Central itself answered CI with
// "Server returned HTTP response code: 429" for android-all-instrumented-14-robolectric-10818077-i7, failing every
// Robolectric class in library-core (runs 44972417720 and 45001009237, 2026-11-22). The same artifacts, another host.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
        systemProperty("robolectric.dependency.repo.id", "google-maven-central")
    }
}

// Compose compiler reports, with -PcomposeReports (Droidtop/tracker#941): for each module that uses Compose, which
// composables can skip when their inputs are unchanged and which classes Compose counts as unstable, the reason a
// screen redraws whole when one value changes. Written to <module>/build/compose-reports and compose-metrics;
// scripts/compose-stability.py totals them, and Android checks prints the totals on every run.
if (providers.gradleProperty("composeReports").isPresent) {
    subprojects {
        val module = this
        plugins.withId("org.jetbrains.kotlin.plugin.compose") {
            module.extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
                reportsDestination.set(module.layout.buildDirectory.dir("compose-reports"))
                metricsDestination.set(module.layout.buildDirectory.dir("compose-metrics"))
            }
            // A compile restored from the build cache writes no report (the first CI run had only the test
            // compilations' reports), so with reports on these compiles run.
            module.tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
                outputs.cacheIf { false }
            }
        }
    }
}

// One Compose for every module (Droidtop/tracker#566): a module compiled against an older Compose than the app ships
// links to methods the shipped library no longer has, and the release build dies with NoSuchMethodError (the plugin
// page's FlowRow, compiled against foundation 1.7 while shell-default's material3 brought 1.9). Each module writes the
// Compose ui, foundation, runtime and animation versions of its own classpath (the app its release runtime, every
// other module its release compile classpath; a project may only resolve its own), and checkComposeVersions fails
// when a module compiles against another version than the app ships, or the app would ship two versions of a group.
// CI runs it.
val composeGroups = setOf(
    "androidx.compose.ui", "androidx.compose.foundation", "androidx.compose.runtime", "androidx.compose.animation",
)

subprojects {
    val module = this
    tasks.register("writeComposeVersions") {
        val out = module.layout.buildDirectory.file("compose-versions.txt")
        outputs.file(out)
        outputs.upToDateWhen { false }
        doLast {
            val name = if (module.path == ":app") "releaseRuntimeClasspath" else "releaseCompileClasspath"
            val lines = module.configurations.findByName(name)?.incoming?.resolutionResult?.allComponents.orEmpty()
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .filter { it.group in composeGroups }
                .map { "${it.group}:${it.module}=${it.version}" }
                .sorted()
            out.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n"))
        }
    }
}

tasks.register("checkComposeVersions") {
    group = "verification"
    description = "Fails when a module compiles against a different Compose than the app ships."
    dependsOn(subprojects.map { "${it.path}:writeComposeVersions" })
    doLast {
        fun read(path: String): Map<String, String> {
            val file = project(path).layout.buildDirectory.file("compose-versions.txt").get().asFile
            if (!file.exists()) return emptyMap()
            return file.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        }
        val shipped = read(":app")
        if (shipped.isEmpty()) throw GradleException("checkComposeVersions: the app's release runtime has no Compose; the check is looking in the wrong place")
        val problems = mutableListOf<String>()
        shipped.entries.groupBy({ it.key.substringBefore(':') }, { it.value }).forEach { (group, found) ->
            if (found.toSet().size > 1) problems += "the app ships $group at ${found.toSet().sorted()}"
        }
        subprojects.filter { it.path != ":app" }.forEach { module ->
            read(module.path).forEach { (artifact, version) ->
                val ships = shipped[artifact]
                if (ships != null && ships != version) problems += "${module.path} compiles against $artifact:$version, the app ships $ships"
            }
        }
        if (problems.isNotEmpty()) throw GradleException("Compose versions differ (Droidtop/tracker#566):\n" + problems.joinToString("\n"))
        logger.lifecycle("Compose: one version per artifact across every module (${shipped.size} artifacts)")
    }
}
