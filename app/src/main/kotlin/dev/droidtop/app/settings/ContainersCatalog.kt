package dev.droidtop.app.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import dev.droidtop.app.ContainerRuntimeFactory
import dev.droidtop.app.DesktopSessionService
import dev.droidtop.app.DesktopSessionState
import dev.droidtop.app.DesktopSetupPrefs
import dev.droidtop.app.MainActivity
import dev.droidtop.app.vpn.DroidtopVpnService
import dev.droidtop.app.vpn.VpnPrefs
import dev.droidtop.app.vpn.VpnState
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.runtime.BundledImageRepositories
import dev.droidtop.runtime.CompositorProvisioning
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.ContainerNames
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ContainerTerminal
import dev.droidtop.runtime.CraneImageCatalogResolver
import dev.droidtop.runtime.ImageCatalogRole
import dev.droidtop.runtime.ImageTags
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The container manager as a settings catalog screen (docs/SPEC.md §3d,
 * "The surface, precisely"): registered as [SCREEN_ID], so it is drawn by
 * the same navigator as every other settings screen, in droidtop's own
 * look, driven by pad and touch alike, in every surface that reaches
 * Desktop settings; `ContainersActivity` hosts it for the Desktop
 * taskbar. It replaced a hand-built Material list with its own buttons,
 * which had no focus, no hint row and no way in by pad.
 *
 * The root lists "Create a container" and one row per container (its
 * name, what it is, its state). A container's page holds its rows: the
 * primary action (the PRIMARY's state IS the desktop session: "Start the
 * desktop" opens Desktop, "Stop the desktop" ends the session), Terminal,
 * Name, Printing (primary), VPN, USB devices and Delete. "Create a
 * container" offers each Recommended repository with its live tag list
 * (the current tag first, [ImageTags.ordered]) and a name, and a Custom
 * reference.
 *
 * A rename that is refused says why on the Name row itself ([renameProblems]),
 * where the person is looking; the old screen put it in a line at the top,
 * off screen when a lower card was renamed (rig, dq-desk2-02).
 */
object ContainersCatalog {
    const val SCREEN_ID = "containers"

    /** How many tags the version picker offers; the rest are reachable as a Custom reference. */
    private const val TAG_LIMIT = 200

    /** Why the last rename of a container (by id) was refused, until a rename succeeds. */
    private val renameProblems = ConcurrentHashMap<String, String>()

    fun screen() = CatalogScreen(
        id = SCREEN_ID,
        title = "Containers",
        subtitle = "The Linux systems on this device: the desktop's own, and any others you make",
        groups = { context -> rootGroups(context) },
    )

    /** Desktop mode, which starts the desktop session if none runs; where every terminal and container window appears. */
    private fun openDesktop(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(BackButtonMenu.EXTRA_MODE, Mode.DESKTOP.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private val session: DesktopSessionState get() = DesktopSessionService.state.value

    private fun isRunning(info: ContainerInfo): Boolean =
        info.running || (info.container.role == ContainerRole.PRIMARY && session !is DesktopSessionState.Idle && session !is DesktopSessionState.Failed)

    private fun stateLabel(info: ContainerInfo, runtime: ContainerRuntime): String = when {
        info.container.role == ContainerRole.PRIMARY && session is DesktopSessionState.Connecting -> "Starting"
        isRunning(info) -> "Running"
        info.container.role == ContainerRole.PRIMARY || runtime.siblingsNeedStart -> "Stopped"
        // A proot sibling has nothing to start: it is its programs.
        else -> "Ready"
    }

    private fun imageLine(info: ContainerInfo): String? {
        val image = info.image ?: return null
        val short = image.removePrefix("docker.io/library/").removePrefix("docker.io/")
        return short + (info.digest?.let { " · ${it.removePrefix("sha256:").take(12)}" } ?: "")
    }

    private suspend fun rootGroups(context: Context): List<CatalogGroup> {
        val runtime = ContainerRuntimeFactory.select(context)
        val containers = runCatching { runtime.listContainers() }
        val create = NestedScreenItem(
            id = "containers_create",
            title = "Create a container",
            subtitle = "From a distribution's releases, or any OCI image",
            inline = createScreen(),
        )
        val rows: List<CatalogItem> = containers.fold(
            onSuccess = { list ->
                list.sortedWith(compareBy({ it.container.role != ContainerRole.PRIMARY }, { it.displayName.lowercase() }))
                    .map { info ->
                        val state = stateLabel(info, runtime)
                        NestedScreenItem(
                            id = "container:${info.container.id}",
                            title = info.displayName,
                            subtitle = listOfNotNull(
                                if (info.container.role == ContainerRole.PRIMARY) "The desktop's own" else null,
                                imageLine(info),
                            ).joinToString(" · ").ifBlank { null },
                            inline = containerScreen(info.container.id, info.displayName),
                            valueLabel = { state },
                        )
                    }
                    .ifEmpty {
                        listOf(
                            ActionItem(
                                id = "containers_none",
                                title = "No containers yet",
                                subtitle = "The desktop makes its own when it first starts; Create a container makes others",
                                run = {},
                            ),
                        )
                    }
            },
            onFailure = { error ->
                listOf(ActionItem(id = "containers_error", title = "Couldn't list the containers", subtitle = error.message, run = {}))
            },
        )
        return listOf(
            CatalogGroup(id = "create", title = null, items = listOf(create)),
            CatalogGroup(id = "containers", title = "Containers", items = rows),
        )
    }

    // ---- a container's page ----

    private fun containerScreen(id: String, name: String) = CatalogScreen(
        id = "container:$id",
        title = name,
        groups = { context -> containerGroups(context, id) },
    )

    private suspend fun containerGroups(context: Context, id: String): List<CatalogGroup> {
        val runtime = ContainerRuntimeFactory.select(context)
        val info = runtime.listContainers().firstOrNull { it.container.id == id }
            ?: return listOf(
                CatalogGroup(
                    id = "gone",
                    title = null,
                    items = listOf(ActionItem(id = "container_gone", title = "This container was deleted", subtitle = "Go back to Containers", run = {})),
                ),
            )
        val container = info.container
        val primary = container.role == ContainerRole.PRIMARY
        val running = isRunning(info)
        val desktopUp = session is DesktopSessionState.Connected

        val main = buildList<CatalogItem> {
            // The state, and the one action that changes it.
            when {
                primary && running -> add(
                    AsyncActionItem(
                        id = "container_stop",
                        title = "Stop the desktop",
                        subtitle = "Ends the desktop and every program on it",
                        value = stateLabel(info, runtime),
                        run = { ctx, _ ->
                            DesktopSessionService.stop(ctx)
                            runtime.stop(container)
                            "Stopped."
                        },
                    ),
                )
                primary -> add(
                    ActionItem(
                        id = "container_start",
                        title = "Start the desktop",
                        subtitle = "Opens Desktop, which starts it",
                        value = stateLabel(info, runtime),
                        run = { ctx -> openDesktop(ctx) },
                    ),
                )
                running -> add(
                    AsyncActionItem(
                        id = "container_stop",
                        title = "Stop",
                        subtitle = "Ends every program running in it",
                        value = stateLabel(info, runtime),
                        run = { _, _ ->
                            runtime.stop(container)
                            "Stopped."
                        },
                    ),
                )
                runtime.siblingsNeedStart -> add(
                    AsyncActionItem(
                        id = "container_start",
                        title = "Start",
                        value = stateLabel(info, runtime),
                        run = { _, _ ->
                            runtime.start(container)
                            "Started."
                        },
                    ),
                )
            }
            add(terminalItem(info, runtime, desktopUp))
            add(
                TextInputItem(
                    id = "container_name",
                    title = "Name",
                    subtitle = renameProblems[id] ?: "What droidtop calls this container everywhere",
                    value = info.displayName,
                    onChange = { _, value ->
                        runCatching { runtime.rename(container, value) }
                            .onSuccess { renameProblems.remove(id) }
                            .onFailure { renameProblems[id] = "Not renamed: ${it.message}" }
                    },
                ),
            )
        }

        val groups = mutableListOf(CatalogGroup(id = "container", title = imageLine(info), items = main))
        if (primary) groups += CatalogGroup(id = "printing", title = "Printing", items = printingItems(context, runtime, desktopUp))
        groups += CatalogGroup(id = "vpn", title = "VPN", items = vpnItems(context, runtime, info))
        groups += CatalogGroup(id = "devices", title = "USB devices", items = deviceItems(context, runtime, info))
        groups += CatalogGroup(id = "delete", title = null, items = listOf(deleteItem(info, runtime, running)))
        return groups
    }

    private fun terminalItem(info: ContainerInfo, runtime: ContainerRuntime, desktopUp: Boolean): CatalogItem {
        if (!desktopUp) {
            return ActionItem(
                id = "container_terminal",
                title = "Terminal",
                subtitle = "A terminal opens as a window on the desktop, which is not running. This starts it; then choose Terminal again",
                run = { ctx -> openDesktop(ctx) },
            )
        }
        val container = info.container
        return AsyncActionItem(
            id = "container_terminal",
            title = "Terminal",
            subtitle = "Opens a terminal window on the desktop; the first time installs one",
            run = { ctx, onStatus ->
                if (container.role == ContainerRole.SIBLING && runtime.siblingsNeedStart && !info.running) runtime.start(container)
                onStatus("Getting a terminal ready (the first time installs one)...")
                ContainerTerminal.ensureInstalled(runtime, container)?.let { error(it) }
                val started = DesktopSessionService.runInPrimary { sessionRuntime, _ ->
                    ContainerTerminal.failureMessage(ContainerTerminal.open(sessionRuntime, container, info.displayName))
                }
                check(started) { "the desktop stopped. Start it again, then open the terminal." }
                withContext(Dispatchers.Main) { openDesktop(ctx) }
                "Opened on the desktop."
            },
        )
    }

    private fun deleteItem(info: ContainerInfo, runtime: ContainerRuntime, running: Boolean): CatalogItem {
        // One rule for every container: a running one is stopped before
        // it can be deleted (docs/SPEC.md §3d).
        if (running) {
            return ActionItem(
                id = "container_delete",
                title = "Delete",
                subtitle = if (info.container.role == ContainerRole.PRIMARY) {
                    "Live desktop: stop it to delete"
                } else {
                    "Running: stop it to delete"
                },
                run = {},
            )
        }
        return AsyncActionItem(
            id = "container_delete",
            title = "Delete",
            subtitle = "Removes the container and everything installed in it",
            confirmTitle = "Delete ${info.displayName} and everything in it?",
            run = { _, _ ->
                runtime.destroy(info.container)
                "Deleted. Go back to Containers."
            },
        )
    }

    // ---- printing (docs/SPEC.md 4b) ----

    private suspend fun printingItems(context: Context, runtime: ContainerRuntime, desktopUp: Boolean): List<CatalogItem> {
        val on = withContext(Dispatchers.IO) { DesktopSetupPrefs.printing(context) }
        // Read from CUPS's socket in the shared socket directory: CUPS runs
        // beside the desktop and never holds it up, so a CUPS that did not
        // start shows here (and in the desktop log), not as a hung desktop.
        val cupsUp = desktopUp && File(runtime.hostSocketDir(), ContainerLayout.CUPS_SOCKET).exists()
        val status = when {
            on && cupsUp -> "CUPS is running, for every container."
            on && desktopUp -> "CUPS is not running (yet). The desktop does not wait for it; if this stays, why is " +
                "in droidtop's desktop log and in ${ContainerLayout.daemonLog(CompositorProvisioning.PRINTING_DAEMON)} " +
                "in the desktop's container."
            on -> "CUPS is installed and started with the desktop."
            else -> "Off. On installs CUPS and starts it with the desktop."
        }
        return buildList {
            add(
                ToggleItem(
                    id = "container_printing",
                    title = "Printing",
                    subtitle = status,
                    current = on,
                    onToggle = { ctx, value -> DesktopSetupPrefs.setPrinting(ctx, value) },
                ),
            )
            if (on && cupsUp) {
                add(
                    ActionItem(
                        id = "container_add_printer",
                        title = "Add a printer",
                        subtitle = "Opens CUPS's own page in the browser",
                        run = { ctx ->
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${CompositorProvisioning.PRINTING_WEB_PORT}/admin"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ),
                )
            }
        }
    }

    // ---- VPN (docs/SPEC.md 4a) ----

    private suspend fun vpnItems(context: Context, runtime: ContainerRuntime, info: ContainerInfo): List<CatalogItem> {
        val id = info.container.id
        val config = withContext(Dispatchers.IO) { VpnPrefs.read(context) }
        val state = DroidtopVpnService.state.value
        val serves = config.containerId == id && state != VpnState.Off
        val socket = File(runtime.hostSocketDir(), ContainerLayout.VPN_SOCKET)
        val needsConsent = VpnService.prepare(context) != null
        val apps = config.allowedApps
        return listOf(
            ToggleItem(
                id = "container_vpn",
                title = "Carry the device's traffic",
                subtitle = when {
                    serves -> describe(state)
                    needsConsent -> "Android asks once to allow droidtop's VPN. Allow it, then turn this on again. " +
                        "The container serves a SOCKS5 proxy at ${ContainerLayout.SOCKET_DIR}/${ContainerLayout.VPN_SOCKET}"
                    else -> "Through a SOCKS5 proxy the container serves at ${ContainerLayout.SOCKET_DIR}/${ContainerLayout.VPN_SOCKET}"
                },
                current = serves,
                onToggle = { ctx, on ->
                    // Another container's VPN is replaced, not stacked.
                    DroidtopVpnService.stop(ctx)
                    if (on) {
                        val ask = VpnService.prepare(ctx)
                        if (ask != null) {
                            ctx.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else {
                            VpnPrefs.serveFrom(ctx, id, socket)
                            DroidtopVpnService.start(ctx)
                        }
                    }
                },
            ),
            NestedScreenItem(
                id = "container_vpn_apps",
                title = "Apps it carries",
                inline = vpnAppsScreen(),
                valueLabel = { if (apps.isEmpty()) "Every app" else "${apps.size} app${if (apps.size == 1) "" else "s"}" },
            ),
            ActionItem(
                id = "container_vpn_always_on",
                title = "Always-on in Android",
                subtitle = "Android's own VPN settings",
                run = { ctx -> ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            ),
        )
    }

    private fun describe(state: VpnState): String = when (state) {
        VpnState.Connected -> "Connected: the device's traffic goes through this container"
        VpnState.NoEndpoint -> "Nothing is serving the VPN socket, so traffic is held back. Start the container's VPN client, or turn this off"
        is VpnState.Failed -> "Couldn't start: ${state.message}"
        VpnState.Off -> "Off"
    }

    /**
     * "Route only these apps": the launchable apps, each on or off. None on
     * means every app. droidtop itself is never offered, because its
     * container's own traffic must stay outside the tunnel it serves.
     */
    private fun vpnAppsScreen() = CatalogScreen(
        id = "container_vpn_apps",
        title = "Apps it carries",
        subtitle = "With none on, every app is carried",
        groups = { context ->
            val selected = withContext(Dispatchers.IO) { VpnPrefs.read(context).allowedApps }
            val apps = withContext(Dispatchers.IO) { launchableApps(context) }
            listOf(
                CatalogGroup(
                    id = "apps",
                    title = null,
                    items = apps.map { (pkg, label) ->
                        ToggleItem(
                            id = "vpn_app:$pkg",
                            title = label,
                            current = pkg in selected,
                            onToggle = { ctx, on ->
                                VpnPrefs.setAllowedApps(ctx, if (on) selected + pkg else selected - pkg)
                                // The app list is fixed when the tunnel is made.
                                if (DroidtopVpnService.state.value != VpnState.Off) {
                                    DroidtopVpnService.stop(ctx)
                                    DroidtopVpnService.start(ctx)
                                }
                            },
                        )
                    },
                ),
            )
        },
    )

    private fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    // ---- USB devices (docs/SPEC.md 4b) ----

    private suspend fun deviceItems(context: Context, runtime: ContainerRuntime, info: ContainerInfo): List<CatalogItem> {
        runtime.deviceSharingUnavailableReason?.let { reason ->
            return listOf(ActionItem(id = "container_devices", title = "Not available here", subtitle = reason, run = {}))
        }
        val container = info.container
        val shared = runCatching { runtime.sharedDevices(container).toSet() }.getOrDefault(emptySet())
        val connected = withContext(Dispatchers.IO) {
            val usb = context.getSystemService(UsbManager::class.java)
            usb?.deviceList?.values.orEmpty().map { device ->
                device.deviceName to listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { "USB device" }
            }.sortedBy { it.first }
        }
        // A device ticked earlier and unplugged now is still listed, so it can be turned off.
        val nodes = connected + shared.filter { path -> connected.none { it.first == path } }.map { it to "Not connected" }
        if (nodes.isEmpty()) {
            return listOf(
                ActionItem(
                    id = "container_devices",
                    title = "No USB device is connected",
                    subtitle = "Plug one in, then open this page again",
                    run = {},
                ),
            )
        }
        return nodes.map { (path, label) ->
            ToggleItem(
                id = "usb:$path",
                title = label,
                subtitle = "$path · available in the container from its next start",
                current = path in shared,
                onToggle = { _, on ->
                    val next = if (on) shared + path else shared - path
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) { runCatching { runtime.setSharedDevices(container, next.sorted()) } }
                },
            )
        }
    }

    // ---- creating ----

    private fun createScreen() = CatalogScreen(
        id = "containers_create",
        title = "Create a container",
        subtitle = "Each is a stock image from its publisher; droidtop adds nothing to it",
        groups = { context ->
            val repositories = BundledImageRepositories.load(context).repositories
                .filter { it.arm64Available }
                .filter { it.role == ImageCatalogRole.SIBLING || it.role == ImageCatalogRole.BOTH }
            listOf(
                CatalogGroup(
                    id = "recommended",
                    title = "Recommended",
                    items = repositories.map { repo ->
                        NestedScreenItem(
                            id = "create:${repo.id}",
                            title = displayOs(repo),
                            subtitle = "${repo.registry}/${repo.repository}",
                            inline = createFromRepositoryScreen(repo),
                        )
                    },
                ),
                CatalogGroup(
                    id = "custom",
                    title = "Custom",
                    items = listOf(
                        NestedScreenItem(
                            id = "create:custom",
                            title = "Any OCI image",
                            subtitle = "By its reference, e.g. docker.io/library/debian:bookworm",
                            inline = createFromReferenceScreen(),
                        ),
                    ),
                ),
            )
        },
    )

    private fun displayOs(repo: KnownImageRepository): String = repo.os.replaceFirstChar { it.uppercaseChar() }

    /** What a create form holds until Create; lives as long as the form's screen. */
    private class CreateForm {
        var tag: String? = null
        var name: String? = null
        var reference: String = ""
    }

    private fun createFromRepositoryScreen(repo: KnownImageRepository): CatalogScreen {
        val form = CreateForm()
        return CatalogScreen(
            id = "create:${repo.id}",
            title = displayOs(repo),
            subtitle = "${repo.registry}/${repo.repository}",
            groups = { context ->
                val runtime = ContainerRuntimeFactory.select(context)
                val resolver = CraneImageCatalogResolver(context)
                val tags = runCatching { resolver.listTags(repo) }
                val existing = runCatching { runtime.listContainers() }.getOrDefault(emptyList()).map { it.displayName }
                val current = tags.getOrNull()?.let { ImageTags.current(it) }
                val tag = form.tag ?: current
                val name = form.name ?: ContainerNames.defaultName(ContainerRole.SIBLING, "${repo.repository}:${tag ?: ""}", existing)
                val items = buildList<CatalogItem> {
                    tags.fold(
                        onSuccess = { list ->
                            val ordered = ImageTags.ordered(list, TAG_LIMIT)
                            add(
                                ChoiceItem(
                                    id = "create_version",
                                    title = "Version",
                                    subtitle = "The registry's tags, the current release first" +
                                        if (list.size > ordered.size) ". ${list.size - ordered.size} older tags: enter one under Custom" else "",
                                    options = ordered.map { ChoiceOption(it, if (it == current) "$it (current)" else it) },
                                    current = tag,
                                    onSelect = { _, value -> form.tag = value },
                                ),
                            )
                        },
                        onFailure = { error ->
                            add(ActionItem(id = "create_version", title = "Couldn't list the versions", subtitle = error.message, run = {}))
                        },
                    )
                    add(
                        TextInputItem(
                            id = "create_name",
                            title = "Name",
                            subtitle = "What droidtop calls it; you can rename it later",
                            value = name,
                            onChange = { _, value -> form.name = value.trim().ifEmpty { null } },
                        ),
                    )
                    if (tag != null) {
                        add(
                            AsyncActionItem(
                                id = "create_go",
                                title = "Create",
                                subtitle = "Downloads ${repo.repository.substringAfterLast('/')}:$tag and makes \"$name\"",
                                run = { ctx, onStatus ->
                                    onStatus("Downloading ${repo.repository.substringAfterLast('/')}:$tag...")
                                    val image = CraneImageCatalogResolver(ctx).resolve(repo, tag).toRootfsImage()
                                    runtime.createSibling(image, name)
                                    "Created \"$name\". It is in Containers."
                                },
                            ),
                        )
                    }
                }
                listOf(CatalogGroup(id = "create", title = null, items = items))
            },
        )
    }

    private fun createFromReferenceScreen(): CatalogScreen {
        val form = CreateForm()
        return CatalogScreen(
            id = "create:custom",
            title = "Any OCI image",
            subtitle = "Any image a registry serves for this device's architecture",
            groups = { context ->
                val runtime = ContainerRuntimeFactory.select(context)
                val existing = runCatching { runtime.listContainers() }.getOrDefault(emptyList()).map { it.displayName }
                val reference = form.reference.trim()
                val name = form.name ?: ContainerNames.defaultName(ContainerRole.SIBLING, reference.ifEmpty { null }, existing)
                val items = buildList<CatalogItem> {
                    add(
                        TextInputItem(
                            id = "create_reference",
                            title = "Image reference",
                            subtitle = "e.g. docker.io/library/debian:bookworm",
                            value = form.reference,
                            onChange = { _, value -> form.reference = value.trim() },
                        ),
                    )
                    add(
                        TextInputItem(
                            id = "create_name",
                            title = "Name",
                            subtitle = "What droidtop calls it; you can rename it later",
                            value = name,
                            onChange = { _, value -> form.name = value.trim().ifEmpty { null } },
                        ),
                    )
                    if (reference.isNotEmpty()) {
                        add(
                            AsyncActionItem(
                                id = "create_go",
                                title = "Create",
                                subtitle = "Downloads $reference and makes \"$name\"",
                                run = { _, onStatus ->
                                    onStatus("Downloading $reference...")
                                    runtime.createSibling(RootfsImage(reference = reference), name)
                                    "Created \"$name\". It is in Containers."
                                },
                            ),
                        )
                    }
                }
                listOf(CatalogGroup(id = "create", title = null, items = items))
            },
        )
    }
}
