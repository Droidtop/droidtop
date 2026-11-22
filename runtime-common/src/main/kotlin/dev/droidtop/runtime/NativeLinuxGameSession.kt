package dev.droidtop.runtime

/**
 * Launches a native Linux game binary directly inside a container -- no
 * Wine -- the strictly-better path docs/SPEC.md 5a describes for a game
 * that ships its own Linux build. Which of a game's builds runs is the
 * PC runner model's decision (`library-core`'s `RunnerAvailability`), not
 * this class's.
 *
 * The container is the game's Linux games container ([LinuxGames]). On an
 * arm64 device an x86 binary runs through FEX: the caller builds the
 * command ([dev.droidtop.shared.runners.TranslatedCommand], or a Ren'Py
 * launcher's variables) and passes FEX's own variables in [launch]'s env
 * (docs/SPEC.md 3c). An arm64 binary runs as it is.
 */
class NativeLinuxGameSession(
    val container: Container,
    val runtime: ContainerRuntime,
) {
    // No WAYLAND_DISPLAY here: every runtime's exec already hands a process
    // the compositor's own socket name (ContainerLayout.clientEnvironment),
    // and the `wayland-0` this used to force is a name sway never uses.
    suspend fun launch(executablePath: String, args: List<String> = emptyList(), env: Map<String, String> = emptyMap()): ContainerExecResult =
        runtime.exec(
            container = container,
            command = listOf(executablePath) + args,
            env = env,
        )
}
