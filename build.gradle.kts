// Root build file — no build logic here, plugins are applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Real use: library-core's own RomDatabase (Room, persistent ROM
    // scan cache) -- was already declared in the version catalog but
    // unused until now.
    alias(libs.plugins.google.ksp) apply false
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
