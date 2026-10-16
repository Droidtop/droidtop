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
// page's FlowRow, compiled against foundation 1.7 while shell-default's material3 brought 1.9). This fails when any
// module's compile classpath has a Compose ui, foundation, runtime or animation artifact at another version than the
// app's release runtime, or when the app would ship two versions of one of those groups. CI runs it.
val composeGroups = setOf(
    "androidx.compose.ui", "androidx.compose.foundation", "androidx.compose.runtime", "androidx.compose.animation",
)

tasks.register("checkComposeVersions") {
    group = "verification"
    description = "Fails when a module compiles against a different Compose than the app ships."
    doLast {
        fun versions(target: Project, configuration: String): Map<String, String> {
            val config = target.configurations.findByName(configuration) ?: return emptyMap()
            return config.incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .filter { it.group in composeGroups }
                .associate { "${it.group}:${it.module}" to it.version }
        }
        val shipped = versions(project(":app"), "releaseRuntimeClasspath")
        if (shipped.isEmpty()) throw GradleException("checkComposeVersions: the app's release runtime has no Compose; the check is looking in the wrong place")
        val problems = mutableListOf<String>()
        shipped.entries.groupBy({ it.key.substringBefore(':') }, { it.value }).forEach { (group, found) ->
            if (found.toSet().size > 1) problems += "the app ships $group at ${found.toSet().sorted()}"
        }
        subprojects.forEach { module ->
            versions(module, "releaseCompileClasspath").forEach { (artifact, version) ->
                val ships = shipped[artifact]
                if (ships != null && ships != version) problems += "${module.path} compiles against $artifact:$version, the app ships $ships"
            }
        }
        if (problems.isNotEmpty()) throw GradleException("Compose versions differ (Droidtop/tracker#566):\n" + problems.joinToString("\n"))
        logger.lifecycle("Compose: one version per artifact across every module (${shipped.size} artifacts)")
    }
}

