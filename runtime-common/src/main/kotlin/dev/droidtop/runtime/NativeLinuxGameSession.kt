package dev.droidtop.runtime

/**
 * Launches a native Linux game binary directly inside a container -- no
 * Wine -- the strictly-better path docs/SPEC.md 5a describes for a game
 * that ships its own Linux build. Which of a game's builds runs is the
 * PC runner model's decision (`library-core`'s `RunnerAvailability`), not
 * this class's.
 *
 * If the binary is x86/x86-64 (the common case), translation would have
 * to happen transparently through FEX's `binfmt_misc` registration
 * (docs/SPEC.md 3c), which droidtop does not set up; the runner model
 * reads the registration and holds such a build at "needs setup" rather
 * than launching it. A genuinely ARM64-native binary runs through this
 * class as it is.
 */
class NativeLinuxGameSession(
    val container: Container,
    val runtime: ContainerRuntime,
) {
    // No WAYLAND_DISPLAY here: every runtime's exec already hands a process
    // the compositor's own socket name (ContainerLayout.clientEnvironment),
    // and the `wayland-0` this used to force is a name sway never uses.
    suspend fun launch(executablePath: String, args: List<String> = emptyList()): ContainerExecResult =
        runtime.exec(
            container = container,
            command = listOf(executablePath) + args,
        )
}
