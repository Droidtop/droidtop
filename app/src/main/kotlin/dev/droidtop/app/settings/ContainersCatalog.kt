package dev.droidtop.app.settings

import dev.droidtop.runtime.FailureText
import dev.droidtop.runtime.PlainFailure
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.provider.Settings
import dev.droidtop.app.ContainerPrograms
import dev.droidtop.app.ContainerBackups
import dev.droidtop.app.ContainerRuntimeFactory
import dev.droidtop.app.DesktopSessionService
import dev.droidtop.app.DesktopSessionState
import dev.droidtop.app.DesktopSetupPrefs
import dev.droidtop.app.GamesRootPrefs
import dev.droidtop.app.MainActivity
import dev.droidtop.app.MicrophonePermissionActivity
import dev.droidtop.app.vpn.DroidtopVpnService
import dev.droidtop.app.vpn.VpnPrefs
import dev.droidtop.app.vpn.VpnState
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.DocumentPickItem
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.library.settings.ChoiceOption
import dev.droidtop.library.settings.FolderPickItem
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.library.settings.TextBlockItem
import dev.droidtop.library.settings.ToggleItem
import dev.droidtop.runtime.BundledImageRepositories
import dev.droidtop.runtime.CompositorProvisioning
import dev.droidtop.runtime.ContainerBackend
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.ContainerNames
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerLook
import dev.droidtop.runtime.ContainerReach
import dev.droidtop.runtime.ContainerSockets
import dev.droidtop.runtime.ContainerUsage
import dev.droidtop.runtime.PackageUpgrade
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ContainerTerminal
import dev.droidtop.runtime.CraneImageCatalogResolver
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerApplications
import dev.droidtop.runtime.ContainerEnter
import dev.droidtop.runtime.ContainerExports
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.EnvironmentMounts
import dev.droidtop.runtime.ExportedApp
import dev.droidtop.runtime.ExportedCommand
import dev.droidtop.runtime.ExposedPort
import dev.droidtop.runtime.ExtraMount
import dev.droidtop.runtime.HomeChoice
import dev.droidtop.runtime.ImageRunConfig
import dev.droidtop.runtime.OciImageStore
import dev.droidtop.runtime.ImageListing
import dev.droidtop.runtime.ImageCatalogRole
import dev.droidtop.runtime.ImageTags
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
import dev.droidtop.library.userFacingErrorMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * The container manager as a settings catalog screen (docs/SPEC.md §3d,
 * "The surface, precisely"): registered as [SCREEN_ID], so it is drawn by
 * the same navigator as every other settings screen, in droidtop's own
 * look, driven by pad and touch alike, in every surface that reaches
 * Desktop settings; `CatalogScreenActivity` hosts it for the Desktop
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

    /**
     * Desktop mode, which starts the desktop session if none runs; where every terminal and container window appears.
     * Only the row that says it switches ("Open Desktop") calls it: no other row takes a person to another mode
     * (Droidtop/tracker#639).
     */
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
                                subtitle = "The desktop makes its own when it first starts; create others with the row above",
                            ),
                        )
                    }
            },
            onFailure = { error ->
                listOf(ActionItem(id = "containers_error", title = "Couldn't list the containers", subtitle = FailureText.of("Containers", error)))
            },
        )
        return listOf(
            CatalogGroup(id = "create", title = null, items = listOf(create)),
            CatalogGroup(id = "containers", title = "Containers", items = rows),
            CatalogGroup(id = "images", title = null, items = listOf(imagesItem(context))),
        )
    }

    // ---- images (Droidtop/tracker#757) ----

    /** The images stored on this device, with how many and what they take on disk. */
    private suspend fun imagesItem(context: Context): CatalogItem {
        val store = OciImageStore.of(context)
        val count = runCatching { store.entries().size }.getOrNull()
        val bytes = runCatching { store.diskBytes() }.getOrNull()
        val value = listOfNotNull(count?.toString(), bytes?.let { dev.droidtop.library.format.ValueFormat.size(it) }).joinToString(" · ")
        return NestedScreenItem(
            id = "containers_images",
            title = "Images",
            subtitle = "What containers are made from, kept so a new one needs no download",
            inline = imagesScreen(),
            valueLabel = { value.ifEmpty { null } },
        )
    }

    private fun imagesScreen() = CatalogScreen(
        id = "containers_images",
        title = "Images",
        subtitle = "Each container keeps its own copy of its image's files, so removing one breaks nothing",
        groups = { context ->
            val runtime = ContainerRuntimeFactory.select(context)
            val listed = runCatching { ImageListing.rows(OciImageStore.of(context).entries(), runtime.listContainers()) }
            val items: List<CatalogItem> = listed.fold(
                onSuccess = { rows ->
                    rows.map { row ->
                        NestedScreenItem(
                            id = "image:${row.entry.digest}",
                            title = row.name,
                            subtitle = usedByLine(row),
                            inline = imageScreen(row.entry.digest, row.name),
                            valueLabel = { dev.droidtop.library.format.ValueFormat.size(row.entry.sizeBytes) },
                        )
                    }.ifEmpty {
                        listOf(ActionItem(id = "images_none", title = "No images", subtitle = "Creating a container downloads its image"))
                    }
                },
                onFailure = { error ->
                    listOf(ActionItem(id = "images_error", title = "Couldn't list the images", subtitle = FailureText.of("Containers", error)))
                },
            )
            listOf(CatalogGroup(id = "images", title = null, items = items))
        },
    )

    private fun usedByLine(row: ImageListing.Row): String =
        if (row.usedBy.isEmpty()) "No container made from it" else "Used by ${row.usedBy.joinToString(", ")}"

    /** One image's page: when it was built, what it takes, who uses it; a container from it, and Remove. */
    private fun imageScreen(digest: String, name: String): CatalogScreen {
        val form = CreateForm()
        return CatalogScreen(id = "image:$digest", title = name, groups = { context -> imageGroups(context, digest, name, form) })
    }

    private suspend fun imageGroups(context: Context, digest: String, name: String, form: CreateForm): List<CatalogGroup> {
        val runtime = ContainerRuntimeFactory.select(context)
        val containers = runCatching { runtime.listContainers() }.getOrDefault(emptyList())
        val row = ImageListing.rows(OciImageStore.of(context).entries(), containers).firstOrNull { it.entry.digest == digest }
            ?: return listOf(
                CatalogGroup(
                    id = "gone",
                    title = null,
                    items = listOf(ActionItem(id = "image_gone", title = "This image was removed", subtitle = "Go back to Images")),
                ),
            )
        val about = listOfNotNull(
            row.entry.createdMs?.let { ActionItem(id = "image_built", title = "Built", value = java.text.DateFormat.getDateInstance().format(java.util.Date(it))) },
            ActionItem(id = "image_size", title = "Size", value = dev.droidtop.library.format.ValueFormat.size(row.entry.sizeBytes)),
            ActionItem(id = "image_used", title = "Containers", value = row.usedBy.joinToString(", ").ifEmpty { "None" }),
            ActionItem(id = "image_reference", title = "Reference", value = row.entry.reference),
        )
        val newName = form.name ?: ContainerNames.defaultName(ContainerRole.SIBLING, row.entry.reference, containers.map { it.displayName })
        val create = listOf(
            TextInputItem(
                id = "image_create_name",
                title = "Name",
                subtitle = "What droidtop calls it; you can rename it later",
                value = newName,
                onChange = { _, value -> form.name = value.trim().ifEmpty { null } },
            ),
            AsyncActionItem(
                id = "image_create",
                title = "Create a container",
                subtitle = "Makes \"$newName\" from this image, with nothing to download",
                run = { ctx, onStatus ->
                    onStatus("Making \"$newName\"...")
                    val created = runtime.createSibling(RootfsImage(row.entry.reference, row.entry.digest), newName)
                    ContainerPrograms.recordCreated(ctx, created.id)
                    "Created \"$newName\". It is in Containers."
                },
            ),
        )
        val remove = AsyncActionItem(
            id = "image_remove",
            title = "Remove",
            subtitle = if (row.usedBy.isEmpty()) {
                "Frees ${dev.droidtop.library.format.ValueFormat.size(row.entry.sizeBytes)}, less any part another image shares"
            } else {
                "${row.usedBy.joinToString(", ")} keep working; making one again from it downloads it again"
            },
            confirmTitle = "Remove $name?",
            run = { ctx, _ ->
                // The rule `rmi` follows (Droidtop/tracker#715); this page has already said who uses it.
                dev.droidtop.app.EngineActions.removeImages(ctx, containers, listOf(digest), force = true)
                "Removed. Go back to Images."
            },
        )
        return listOf(
            CatalogGroup(id = "image", title = null, items = about),
            CatalogGroup(id = "image_create", title = "New container", items = create),
            CatalogGroup(id = "image_remove", title = null, items = listOf(remove)),
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
                    items = listOf(ActionItem(id = "container_gone", title = "This container was deleted", subtitle = "Go back to Containers")),
                ),
            )
        val container = info.container
        val primary = container.role == ContainerRole.PRIMARY
        val running = isRunning(info)
        val desktopUp = session is DesktopSessionState.Connected

        // buildList's block below isn't suspend, so anything that needs a
        // suspend call (the real disk-usage walk) is computed up front.
        val diskUsageBytes = runCatching { runtime.diskUsageBytes(container) }.getOrNull()
        val usage = if (running) readUsage(runtime, container, PAGE_USAGE_SAMPLE_MS) else null
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
                        title = "Open Desktop",
                        subtitle = "Switches to Desktop mode, which starts the desktop",
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
            // A sibling with no program of its own starts with the desktop from here; one with a program has the same
            // switch beside its program (Droidtop/tracker#808).
            if (container.role == ContainerRole.SIBLING && runtime.siblingsNeedStart &&
                runCatching { runtime.imageRunConfig(container) }.getOrNull()?.program().isNullOrEmpty()
            ) {
                val app = context.applicationContext
                add(
                    ToggleItem(
                        id = "container_autostart",
                        title = "Start with the desktop",
                        subtitle = "Starts it each time the desktop starts",
                        current = withContext(Dispatchers.IO) { ContainerPrograms.autostart(app, container.id) },
                        onToggle = { _, on -> withContext(Dispatchers.IO) { ContainerPrograms.setAutostart(app, container.id, on) } },
                    ),
                )
            }
            if (running) add(restartItem(info, runtime))
            add(recreateItem(info, runtime))
            add(storageItem(diskUsageBytes))
            if (running) add(usageItem(runtime, container, usage))
            add(terminalItem(info, runtime, desktopUp))
            add(updateSoftwareItem(info, runtime))
            add(
                TextInputItem(
                    id = "container_name",
                    title = "Name",
                    subtitle = renameProblems[id] ?: "What droidtop calls this container everywhere",
                    value = info.displayName,
                    onChange = { _, value ->
                        runCatching { runtime.rename(container, value) }
                            .onSuccess { renameProblems.remove(id) }
                            .onFailure { renameProblems[id] = "Not renamed: ${FailureText.of("Containers", it)}" }
                    },
                ),
            )
        }

        val mounts = runCatching { runtime.mounts(info.container) }.getOrDefault(EnvironmentMounts())
        val groups = mutableListOf(CatalogGroup(id = "container", title = imageLine(info), items = main))
        groups += CatalogGroup(id = "reach", title = "What it can reach", items = reachItems(context, runtime, info, mounts))
        if (primary) groups += CatalogGroup(id = "printing", title = "Printing", items = printingItems(context, runtime, desktopUp))
        if (primary) groups += CatalogGroup(id = "fonts", title = "Languages", items = fontItems(context))
        groups += CatalogGroup(id = "network", title = "Network", items = listOf(networkItem(runtime, info)))
        groups += CatalogGroup(id = "vpn", title = "VPN", items = vpnItems(context, runtime, info))
        groups += CatalogGroup(id = "devices", title = "USB devices", items = deviceItems(context, runtime, info))
        groups += CatalogGroup(id = "sockets", title = "Sockets", items = socketItems(context, runtime, info))
        groups += CatalogGroup(id = "look", title = "Scale and theme", items = lookItems(runtime, info))
        groups += CatalogGroup(id = "keyboard", title = "Keyboard", items = listOf(keyboardItem(context, runtime, info)))
        runCatching { runtime.imageRunConfig(info.container) }.getOrNull()
            ?.let { image -> withContext(Dispatchers.IO) { ContainerProgram.effectiveConfig(context.filesDir, info.container.id, image) } }
            ?.takeIf { it.program().isNotEmpty() }
            ?.let { groups += CatalogGroup(id = "program", title = "Its own program", items = programItems(context, runtime, info, it)) }
        groups += CatalogGroup(id = "home", title = "Home", items = homeItems(runtime, info, mounts))
        groups += CatalogGroup(id = "mounts", title = "Mounts", items = mountItems(runtime, info, mounts))
        if (info.container.role == ContainerRole.SIBLING) {
            groups += CatalogGroup(id = "exports", title = "In the desktop's menus", items = exportItems(context, runtime, info))
            groups += CatalogGroup(id = "commands", title = "Commands in the desktop's terminal", items = commandItems(context, info))
        }
        groups += CatalogGroup(id = "backup", title = "Backup", items = backupItems(runtime, info, running) + scheduleItems(context, runtime, info))
        val others = runCatching { runtime.listContainers() }.getOrDefault(emptyList()).map { it.displayName }
        groups += CatalogGroup(id = "copy", title = "Copy", items = copyItems(runtime, info, running, others))
        groups += CatalogGroup(id = "delete", title = null, items = listOf(deleteItem(info, runtime, running)))
        return groups
    }

    /**
     * Export and import of the container's own data, by hand, to a file the
     * person picks (docs/SPEC.md 3d "Backup", Droidtop/tracker#81). Only
     * offered while it is stopped, since a running container's files are
     * changing under the archive.
     */
    private fun backupItems(runtime: ContainerRuntime, info: ContainerInfo, running: Boolean): List<CatalogItem> {
        runtime.dataBackupUnavailableReason?.let {
            return listOf(ActionItem(id = "container_backup_unavailable", title = "Backup: not available here", subtitle = it))
        }
        if (running) {
            return listOf(
                ActionItem(
                    id = "container_backup_stop_first",
                    title = "Back up or restore its data",
                    subtitle = "Stop it first: a running container's files are changing",
                ),
            )
        }
        val container = info.container
        val fileName = info.displayName.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "container" }
        return listOf(
            DocumentPickItem(
                id = "container_backup_export",
                title = "Back up its data",
                subtitle = "Saves everything in it to a file you choose, to restore later or on another device",
                mimeType = "application/x-tar",
                createName = "$fileName-container.tar",
                onPicked = { ctx, uri ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw PlainFailure("the file could not be opened")
                            out.use { runtime.exportData(container, it) }
                        }
                        "Saved."
                    }.getOrElse {
                        // The picker already made the file; a half-written archive
                        // that looks like a backup is worse than none.
                        runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                        "Not saved: ${FailureText.of("Containers", it)}"
                    }
                },
            ),
            DocumentPickItem(
                id = "container_backup_import",
                title = "Restore its data from a backup",
                subtitle = "Replaces everything in it with a backup made by \"Back up its data\"; what is in it now is lost",
                mimeType = "*/*",
                onPicked = { ctx, uri ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val input = ctx.contentResolver.openInputStream(uri) ?: throw PlainFailure("the file could not be opened")
                            input.use { runtime.importData(container, it) }
                        }
                        "Restored."
                    }.getOrElse { "Not restored: ${FailureText.of("Containers", it)}" }
                },
            ),
        )
    }

    /**
     * Backups on a schedule (docs/SPEC.md 3d "Backups on a schedule", Droidtop/tracker#808): how often, where they go,
     * and what the last run did. Shown whether or not it runs now: a running container is skipped, not stopped.
     */
    private suspend fun scheduleItems(context: Context, runtime: ContainerRuntime, info: ContainerInfo): List<CatalogItem> {
        if (runtime.dataBackupUnavailableReason != null) return emptyList()
        val id = info.container.id
        val (frequency, folder, last) = withContext(Dispatchers.IO) {
            Triple(ContainerBackups.frequency(context, id), ContainerBackups.folder(context, id), ContainerBackups.lastResult(context, id))
        }
        // Restoring a point of the schedule's chain (slice B2): any file in its folder, the chain worked out from it.
        val restore = if (folder != null && !info.running) {
            DocumentPickItem(
                id = "container_backup_restore_point",
                title = "Restore from a scheduled backup",
                subtitle = "Pick one in its folder: droidtop puts the container back as it was then",
                mimeType = "*/*",
                onPicked = { ctx, uri ->
                    val name = withContext(Dispatchers.IO) {
                        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }
                    if (name == null) {
                        "That file can't be read"
                    } else {
                        runCatching { withContext(Dispatchers.IO) { ContainerBackups.restore(ctx, runtime, info, name) } }
                            .getOrElse { "Not restored: ${FailureText.of("Containers", it)}" }
                    }
                },
            )
        } else {
            null
        }
        return listOfNotNull(
            ChoiceItem(
                id = "container_backup_schedule",
                title = "Back up on a schedule",
                subtitle = last ?: "While charging; skipped while it runs",
                options = ContainerBackups.Frequency.entries.map { ChoiceOption(it.id, it.label) },
                current = frequency.id,
                onSelect = { ctx, value ->
                    val app = ctx.applicationContext
                    dev.droidtop.runtime.util.AppScopes.io.launch {
                        ContainerBackups.setFrequency(app, id, ContainerBackups.Frequency.fromId(value))
                    }
                },
            ),
            FolderPickItem(
                id = "container_backup_folder",
                title = "Where scheduled backups go",
                subtitle = folder?.let { android.provider.DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifEmpty { "The folder you picked" } }
                    ?: "No folder yet: scheduled backups wait for one",
                onPicked = { ctx, uri ->
                    withContext(Dispatchers.IO) { ContainerBackups.setFolder(ctx, id, uri) }
                    null
                },
            ),
            restore,
        )
    }

    /** Ends everything running in the container and boots it again (docs/SPEC.md 3d "Restart"). */
    private fun restartItem(info: ContainerInfo, runtime: ContainerRuntime): CatalogItem {
        val container = info.container
        return AsyncActionItem(
            id = "container_restart",
            title = "Restart",
            subtitle = if (info.container.role == ContainerRole.PRIMARY) "Ends the desktop and every program on it, then starts it again" else "Ends everything running in it, then starts it again",
            confirmTitle = "Restart ${info.displayName}?",
            run = { _, _ ->
                runtime.restart(container)
                "Restarted."
            },
        )
    }

    /** A fresh pull of the same image, kept name and role (docs/SPEC.md 3d "Recreate from the image"). */
    private fun recreateItem(info: ContainerInfo, runtime: ContainerRuntime): CatalogItem {
        if (info.image == null) {
            return ActionItem(
                id = "container_recreate",
                title = "Recreate from the image",
                subtitle = "No image is recorded for this container: it was made before droidtop recorded them",
            )
        }
        return AsyncActionItem(
            id = "container_recreate",
            title = "Recreate from the image",
            subtitle = "Deletes it and downloads ${info.image} fresh, keeping the name and everything else about it",
            confirmTitle = "Recreate ${info.displayName} from ${info.image}? Everything installed by hand in it is lost",
            run = { _, onStatus ->
                onStatus("Downloading ${info.image}...")
                runtime.recreateFromImage(info.container)
                "Recreated \"${info.displayName}\" from ${info.image}."
            },
        )
    }

    /** Real size on disk (docs/SPEC.md 3d "Storage used"), computed fresh every time the page opens. */
    private fun storageItem(bytes: Long?): CatalogItem = ActionItem(
        id = "container_storage",
        title = "Storage used",
        value = bytes?.let { dev.droidtop.library.format.ValueFormat.size(it) } ?: "Unknown",
    )

    /**
     * What the container can reach of the device, a line a kind (docs/SPEC.md 3d, "What it can reach";
     * Droidtop/tracker#787 slice 1), read from the settings the rows below write ([ContainerReach]).
     */
    private suspend fun reachItems(context: Context, runtime: ContainerRuntime, info: ContainerInfo, mounts: EnvironmentMounts): List<CatalogItem> {
        val container = info.container
        val sockets = runCatching { runtime.sockets(container) }.getOrDefault(ContainerSockets())
        val microphone = runtime.microphoneUnavailableReason == null &&
            withContext(Dispatchers.IO) { DesktopSetupPrefs.microphone(context) } &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val devices = if (runtime.deviceSharingUnavailableReason != null) 0 else runCatching { runtime.sharedDevices(container).size }.getOrDefault(0)
        val servesVpn = withContext(Dispatchers.IO) { VpnPrefs.read(context) }.containerId == container.id &&
            DroidtopVpnService.state.value != VpnState.Off
        val network = runtime.networkUnavailableReason != null || runCatching { runtime.networkShared(container) }.getOrDefault(true)
        val settings = ContainerReach.Settings(mounts, sockets, microphone, devices, servesVpn, network)
        return ContainerReach.lines(settings).map { line ->
            ActionItem(id = "container_reach_${line.kind.lowercase().replace(' ', '_')}", title = line.kind, subtitle = line.what)
        }
    }

    /** A reading of [container]'s use, or why there is none: the backend's reason, or what went wrong. */
    private suspend fun readUsage(runtime: ContainerRuntime, container: Container, sampleMs: Long): Result<ContainerUsage?> {
        runtime.usageUnavailableReason?.let { return Result.failure(IllegalStateException(it)) }
        return runCatching { runtime.usage(container, sampleMs) }
    }

    /** "512 MB · 12% of the processor", or null with no reading. Pure. */
    internal fun usageLine(usage: ContainerUsage?): String? = usage?.let {
        "${dev.droidtop.library.format.ValueFormat.size(it.memoryBytes)} · ${usageShare(it.cpuPercent)} of the processor"
    }

    /** A processor share as a person reads it: "under 1%" for a trace, else whole percents. Pure. */
    internal fun usageShare(percent: Double): String = if (percent > 0 && percent < 1) "under 1%" else "${percent.toInt()}%"

    /**
     * Memory and processor use of a running container (docs/SPEC.md 3d, "Resource usage"; Droidtop/tracker#757 slice
     * 3), read from its processes when the page opens; A reads it again.
     */
    private fun usageItem(runtime: ContainerRuntime, container: Container, usage: Result<ContainerUsage?>?): CatalogItem {
        val reading = usage?.getOrNull()
        val problem = usage?.exceptionOrNull()?.let { FailureText.of("Containers", it) }
        return AsyncActionItem(
            id = "container_usage",
            title = "Memory and processor",
            subtitle = when {
                problem != null -> problem
                reading != null -> "${reading.processes} processes, measured over half a second. Press to measure again"
                else -> "No process of it is running"
            },
            value = usageLine(reading) ?: "Unknown",
            run = { _, _ ->
                val again = readUsage(runtime, container, dev.droidtop.runtime.USAGE_SAMPLE_MS)
                again.exceptionOrNull()?.let { FailureText.of("Containers", it) } ?: usageLine(again.getOrNull()) ?: "Not running"
            },
        )
    }

    // ---- sockets (docs/SPEC.md 3d) ----

    private suspend fun socketItems(context: Context, runtime: ContainerRuntime, info: ContainerInfo): List<CatalogItem> {
        val container = info.container
        val sockets = runCatching { runtime.sockets(container) }.getOrDefault(ContainerSockets())
        return buildList {
            add(
                ToggleItem(
                    id = "container_socket_wayland",
                    title = "Wayland",
                    subtitle = "Share the desktop's display with this container's own programs",
                    current = sockets.waylandShared,
                    onToggle = { _, on ->
                        withContext(Dispatchers.IO) {
                            runCatching { runtime.setSockets(container, sockets.copy(waylandShared = on)) }
                        }
                    },
                ),
            )
            // The clipboard kept from this container's windows (docs/SPEC.md 3d "Clipboard per container",
            // Droidtop/tracker#787 slice 2). The primary runs the compositor itself, so its own programs reach it directly.
            if (container.role == ContainerRole.PRIMARY) {
                add(
                    ActionItem(
                        id = "container_socket_clipboard",
                        title = "Clipboard: always shared",
                        subtitle = "This container runs the desktop itself, so its programs reach the clipboard directly",
                    ),
                )
            } else {
                add(
                    ToggleItem(
                        id = "container_socket_clipboard",
                        title = "Clipboard",
                        subtitle = "Let its programs read and set the clipboard. Applies from its next start",
                        current = sockets.clipboardShared,
                        onToggle = { _, on ->
                            withContext(Dispatchers.IO) {
                                runCatching { runtime.setSockets(container, sockets.copy(clipboardShared = on)) }
                            }
                        },
                    ),
                )
            }
            val audioReason = runtime.audioSharingUnavailableReason
            if (audioReason != null) {
                add(ActionItem(id = "container_socket_audio", title = "Audio: not available here", subtitle = audioReason))
            } else {
                add(
                    ToggleItem(
                        id = "container_socket_audio",
                        title = "Audio",
                        subtitle = "Share the device's audio with this container",
                        current = sockets.audioShared,
                        onToggle = { _, on ->
                            withContext(Dispatchers.IO) {
                                runCatching { runtime.setSockets(container, sockets.copy(audioShared = on)) }
                            }
                        },
                    ),
                )
                if (runtime.microphoneUnavailableReason == null) add(microphoneItem(context))
            }
        }
    }

    /**
     * Whether the container has the device's network (docs/SPEC.md 3d "Network", Droidtop/tracker#787 slice 3). Taking
     * it away asks first and says what stops; giving it back does not. Either applies from the container's next start.
     * A backend that cannot take it away says why in its place.
     */
    private suspend fun networkItem(runtime: ContainerRuntime, info: ContainerInfo): CatalogItem {
        runtime.networkUnavailableReason?.let { reason ->
            return ActionItem(id = "container_network", title = "Network", subtitle = reason, value = "This device's")
        }
        val container = info.container
        val shared = runCatching { runtime.networkShared(container) }.getOrDefault(true)
        return AsyncActionItem(
            id = "container_network",
            title = "Network",
            subtitle = if (shared) {
                "It has this device's network, as any app has. Press to take it away"
            } else {
                "It has no network at all. Press to give it this device's network again"
            },
            value = if (shared) "On" else "Off",
            confirmTitle = if (shared) {
                "Take ${info.displayName}'s network away? Nothing in it can reach the internet or this network, " +
                    "downloads and updates in it stop, and a VPN it carries stops working. Its windows, sound and folders " +
                    "stay. Applies when it next starts"
            } else {
                null
            },
            run = { _, _ ->
                runCatching { runtime.setNetworkShared(container, !shared) }.fold(
                    onSuccess = { if (shared) "Off from its next start" else "On from its next start" },
                    onFailure = { FailureText.of("Containers", it) },
                )
            },
        )
    }

    /**
     * How the container's programs look (docs/SPEC.md 3d "Scale and theme", Droidtop/tracker#787 slice 5): their size
     * against the desktop's scale, and whether GTK follows droidtop's Light or dark. Both apply to programs started
     * after the change.
     */
    private suspend fun lookItems(runtime: ContainerRuntime, info: ContainerInfo): List<CatalogItem> {
        val container = info.container
        val look = runCatching { runtime.look(container) }.getOrDefault(ContainerLook())
        fun save(next: ContainerLook) {
            dev.droidtop.runtime.util.AppScopes.io.launch { runCatching { runtime.setLook(container, next) } }
        }
        return listOf(
            ChoiceItem(
                id = "container_look_scale",
                title = "Scale",
                subtitle = "Its programs' size against the desktop's own scale. Qt programs and GTK's text follow it. " +
                    "Applies to programs started after the change",
                options = ContainerLook.SCALES.map { ChoiceOption(it.toString(), scaleLabel(it)) },
                current = look.scale.toString(),
                onSelect = { _, value -> value.toFloatOrNull()?.let { save(look.copy(scale = it)) } },
            ),
            ToggleItem(
                id = "container_look_theme",
                title = "Light or dark as droidtop",
                subtitle = "Its GTK programs follow droidtop's Light or dark. Off, they keep the image's own look. " +
                    "Applies to programs started after the change",
                current = look.followTheme,
                onToggle = { _, on ->
                    withContext(Dispatchers.IO) { runCatching { runtime.setLook(container, look.copy(followTheme = on)) } }
                },
            ),
        )
    }

    /**
     * The layout a physical keyboard types in while one of the container's windows has the focus (docs/SPEC.md 3d
     * "Keyboard", Droidtop/tracker#787 slice 6): the desktop's own by default, or one of the desktop's layouts. A
     * backend that cannot tell a window's container says why in its place.
     */
    private suspend fun keyboardItem(context: Context, runtime: ContainerRuntime, info: ContainerInfo): CatalogItem {
        runtime.windowOwnerUnavailableReason?.let { reason ->
            return ActionItem(id = "container_keyboard", title = "Keyboard layout", subtitle = reason, value = "The desktop's")
        }
        val container = info.container
        val look = runCatching { runtime.look(container) }.getOrDefault(ContainerLook())
        val names = withContext(Dispatchers.IO) { dev.droidtop.app.DesktopKeyboardLayout.layoutNames(context) }
        val ids = names.keys.ifEmpty { dev.droidtop.runtime.KeyboardLayouts.KNOWN }
        val options = listOf(ChoiceOption(AS_DESKTOP, "As the desktop")) +
            (ids + listOfNotNull(look.keyboardLayout)).distinct().map { id -> ChoiceOption(id, names[id]?.let { "$it ($id)" } ?: id) }
        return ChoiceItem(
            id = "container_keyboard",
            title = "Keyboard layout",
            subtitle = "A physical keyboard's layout while one of its windows has the focus",
            options = options,
            current = look.keyboardLayout ?: AS_DESKTOP,
            onSelect = { ctx, value ->
                val app = ctx.applicationContext
                dev.droidtop.runtime.util.AppScopes.io.launch {
                    runCatching { runtime.setLook(container, look.copy(keyboardLayout = value.takeIf { it != AS_DESKTOP })) }
                    dev.droidtop.app.DesktopKeyboardLayout.reapply(app)
                }
            },
        )
    }

    /** The keyboard row's "As the desktop": no layout of its own. */
    private const val AS_DESKTOP = "desktop"

    /** "As the desktop" for 1, else the percentage: "75%", "150%". Pure. */
    internal fun scaleLabel(scale: Float): String = if (scale == 1f) "As the desktop" else "${(scale * 100).toInt()}%"

    /**
     * The device microphone as a source on the audio bridge (Droidtop/tracker#80).
     * One switch for the whole desktop, not per container: every container that
     * shares audio reaches the same PulseAudio server. Off by default, and the
     * row itself is the reason asked before Android's own prompt.
     */
    private fun microphoneItem(context: Context): CatalogItem {
        val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return ToggleItem(
            id = "container_socket_microphone",
            title = "Microphone",
            subtitle = "Desktop programs may use the microphone, for calls. From the next start, droidtop in front",
            current = DesktopSetupPrefs.microphone(context) && granted,
            onToggle = { ctx, on ->
                val allowed = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                when {
                    !on -> DesktopSetupPrefs.setMicrophone(ctx, false)
                    allowed -> DesktopSetupPrefs.setMicrophone(ctx, true)
                    // The activity records the choice once Android has answered.
                    else -> ctx.startActivity(
                        Intent(ctx, MicrophonePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }

    // ---- the image's own program (docs/SPEC.md 3, "The image's own program", Droidtop/tracker#555 D6a) ----

    /**
     * What the image says it runs, read from its config and kept with the container, and droidtop running it: Start or
     * Stop with what it is doing, restart when it fails, start with the desktop, and its log (D6b).
     */
    private suspend fun programItems(context: Context, runtime: ContainerRuntime, info: ContainerInfo, config: ImageRunConfig): List<CatalogItem> = buildList {
        val container = info.container
        val app = context.applicationContext
        val state = ContainerPrograms.state(container.id)
        val running = ContainerPrograms.isRunning(container.id)
        add(
            AsyncActionItem(
                id = "container_program_run",
                title = if (running) "Stop it" else "Start it",
                value = programStateLabel(state),
                confirmTitle = if (running) "Stop ${info.displayName}'s program?" else null,
                run = { ctx, _ ->
                    if (running) {
                        ContainerPrograms.stoppedByPerson(ctx, container.id)
                        "Stopped."
                    } else {
                        ContainerPrograms.startedByPerson(ctx, runtime, container)
                        "Started; its output is in the log below."
                    }
                },
            ),
        )
        add(
            ToggleItem(
                id = "container_program_restart",
                title = "Restart when it fails",
                subtitle = "Waits longer after each quick failure, and stops trying after ${ContainerProgram.MAX_FAILURES_IN_A_ROW}",
                current = withContext(Dispatchers.IO) { ContainerPrograms.restartPolicy(app, container.id) != ContainerProgram.RestartPolicy.No },
                onToggle = { _, on ->
                    withContext(Dispatchers.IO) {
                        ContainerPrograms.setRestartPolicy(app, container.id, if (on) ContainerProgram.RestartPolicy.OnFailure() else ContainerProgram.RestartPolicy.No)
                    }
                },
            ),
        )
        add(
            ToggleItem(
                id = "container_program_autostart",
                title = "Start with the desktop",
                current = withContext(Dispatchers.IO) { ContainerPrograms.autostart(app, container.id) },
                onToggle = { _, on -> withContext(Dispatchers.IO) { ContainerPrograms.setAutostart(app, container.id, on) } },
            ),
        )
        add(TextBlockItem(id = "container_program", title = "Program", text = ImageRunConfig.shellWords(config.program())))
        config.workingDir?.let { add(TextBlockItem(id = "container_program_dir", title = "Working folder", text = it)) }
        if (config.env.isNotEmpty()) {
            add(TextBlockItem(id = "container_program_env", title = "Environment", text = config.env.joinToString("\n")))
        }
        config.ports.forEach { port -> add(portItem(container.id, port, info.container.backend)) }
        if (!config.runsAsRoot) {
            add(TextBlockItem(id = "container_program_user", title = "User", text = "Runs as ${config.user}"))
        }
        val log = withContext(Dispatchers.IO) { ContainerProgram.lastLines(ContainerProgram.hostLogFile(app.filesDir, container.id)) }
        add(TextBlockItem(id = "container_program_log", title = "Log", text = log.joinToString("\n").ifEmpty { "Nothing yet" }, last = true))
    }

    /**
     * One port the image says its program listens on (docs/SPEC.md 3 "Ports"). There are no network namespaces, so it
     * is the device's own port: a tcp one is a web address Open on Android reaches, anything else is a note.
     */
    private fun portItem(containerId: String, port: ExposedPort, backend: ContainerBackend): CatalogItem {
        val note = when {
            port.needsRoot && backend == ContainerBackend.PROOT -> "Below 1024: it can only listen there with root"
            port.url == null -> "${port.protocol.uppercase()}, on the device's own port"
            else -> "On the device's own port; open it while the program runs"
        }
        val url = port.url ?: return TextBlockItem(id = "container_port_${containerId}_$port", title = "Port ${port.number}", text = note)
        return ActionItem(
            id = "container_port_${containerId}_$port",
            title = url,
            subtitle = note,
            value = "Open on Android",
            run = { ctx ->
                dev.droidtop.library.Links.open(ctx, url)
            },
        )
    }

    private fun programStateLabel(state: ContainerPrograms.State): String = when (state) {
        ContainerPrograms.State.Stopped -> "Stopped"
        is ContainerPrograms.State.Running -> "Running"
        is ContainerPrograms.State.Waiting -> "Restarting in ${state.restartInMs / 1000} s (it exited with ${state.lastExit})"
        is ContainerPrograms.State.Ended -> state.why
            ?: if (state.gaveUp) "Stopped after failing ${ContainerProgram.MAX_FAILURES_IN_A_ROW} times (exit ${state.exitCode})" else "Exited with ${state.exitCode}"
        is ContainerPrograms.State.Failed -> "Couldn't start: ${state.message}"
    }

    // ---- exported commands (docs/SPEC.md 3d "Exported commands", Droidtop/tracker#555 D5, D7) ----

    /** Why the last command name typed on a container's page was not exported, by container id; shown once, under the field. */
    private val commandProblems = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Whether the desktop may run this container's commands, the commands it exports by name (each then runs in the
     * desktop's terminal as if installed there, through droidtop-enter), and a field to add one.
     */
    private suspend fun commandItems(context: Context, info: ContainerInfo): List<CatalogItem> {
        val container = info.container
        val filesDir = context.filesDir
        val (allowed, exported) = withContext(Dispatchers.IO) {
            ContainerEnter.allowed(filesDir, container.id) to ContainerEnter.read(filesDir).filter { it.containerId == container.id }
        }
        return buildList {
            add(
                ToggleItem(
                    id = "container_enter_allowed",
                    title = "Run its commands from the desktop",
                    subtitle = "Commands shown below run here when typed in the desktop's terminal",
                    current = allowed,
                    onToggle = { _, on -> withContext(Dispatchers.IO) { ContainerEnter.setAllowed(filesDir, container.id, on) } },
                ),
            )
            add(
                TextInputItem(
                    id = "container_command_add",
                    title = "Add a command",
                    subtitle = commandProblems.remove(container.id) ?: "A program's name, for example htop; it then runs from the desktop's terminal",
                    value = "",
                    onChange = { _, value ->
                        val name = value.trim()
                        if (!ContainerEnter.validName(name)) {
                            commandProblems[container.id] = "Not added: \"$name\" is not a program's name"
                        } else {
                            withContext(Dispatchers.IO) {
                                val command = ExportedCommand(container.id, info.displayName, name)
                                ContainerEnter.save(filesDir, ContainerEnter.with(ContainerEnter.read(filesDir), command, true))
                            }
                        }
                    },
                ),
            )
            exported.forEach { command ->
                add(
                    AsyncActionItem(
                        id = "container_command:${command.name}",
                        title = command.name,
                        subtitle = "Runs in ${info.displayName} from the desktop's terminal",
                        confirmTitle = "Stop offering ${command.name} on the desktop?",
                        run = { ctx, _ ->
                            withContext(Dispatchers.IO) {
                                ContainerEnter.save(ctx.filesDir, ContainerEnter.with(ContainerEnter.read(ctx.filesDir), command, false))
                            }
                            "Removed."
                        },
                    ),
                )
            }
        }
    }

    // ---- exported apps (docs/SPEC.md 3d "Exported apps", Droidtop/tracker#555 D5) ----

    /** One switch per app of this sibling: shown in the desktop's menus as "<app> (on <container>)", or not. */
    private suspend fun exportItems(context: Context, runtime: ContainerRuntime, info: ContainerInfo): List<CatalogItem> {
        val container = info.container
        val filesDir = context.filesDir
        val apps = runCatching { withContext(Dispatchers.IO) { ContainerApplications.ownApps(runtime, container) } }
        val exported = withContext(Dispatchers.IO) { ContainerExports.read(filesDir) }
            .filter { it.containerId == container.id }.map { it.app.id }.toSet()
        return apps.fold(
            onSuccess = { list ->
                if (list.isEmpty()) {
                    listOf(ActionItem(id = "container_exports_none", title = "No apps installed in it yet", subtitle = "An app installed here can then be shown on the desktop"))
                } else {
                    list.map { app ->
                        ToggleItem(
                            id = "container_export:${app.id}",
                            title = app.name,
                            subtitle = "Shown in the desktop's menus as \"${app.name} (on ${info.displayName})\"",
                            current = app.id in exported,
                            onToggle = { ctx, on ->
                                withContext(Dispatchers.IO) {
                                    val export = ExportedApp(container.id, info.displayName, app)
                                    ContainerExports.save(filesDir, ContainerExports.with(ContainerExports.read(filesDir), export, on))
                                }
                                // The Desktop widget lists the exported apps.
                                dev.droidtop.app.DesktopWidgetProvider.requestUpdate(ctx)
                            },
                        )
                    }
                }
            },
            onFailure = { error ->
                listOf(
                    ActionItem(
                        id = "container_exports_unreadable",
                        title = "Couldn't read its apps",
                        subtitle = if (runtime.siblingsNeedStart && !info.running) "Start it, then come back" else userFacingErrorMessage(error),
                    ),
                )
            },
        )
    }

    // ---- home (docs/SPEC.md 3d "Home and folders") ----

    /** Which home the container's programs see at /root: one row per choice, the current one marked. */
    private fun homeItems(runtime: ContainerRuntime, info: ContainerInfo, current: EnvironmentMounts): List<CatalogItem> {
        val container = info.container
        fun choose(home: HomeChoice): suspend (Context, (String) -> Unit) -> String = { _, _ ->
            withContext(Dispatchers.IO) { runtime.setMounts(container, current.copy(home = home)) }
            "From its next start."
        }
        val chosen = current.home as? HomeChoice.Chosen
        return listOf(
            AsyncActionItem(
                id = "container_home_shared",
                title = "Your files (shared with the desktop)",
                subtitle = "The same home folder as every container that shares it",
                value = "Current".takeIf { current.home == HomeChoice.Shared },
                run = choose(HomeChoice.Shared),
            ),
            AsyncActionItem(
                id = "container_home_private",
                title = "Its own files only",
                subtitle = "A home of its own that no other container sees",
                value = "Current".takeIf { current.home == HomeChoice.Private },
                run = choose(HomeChoice.Private),
            ),
            FolderPickItem(
                id = "container_home_chosen",
                title = "A folder you choose",
                subtitle = chosen?.let { "Current: ${it.hostPath}" } ?: "A folder on this device becomes its home",
                onPicked = { _, uri ->
                    val resolved = withContext(Dispatchers.IO) { GamesRootPrefs.resolveStoragePath(uri) }
                        ?: return@FolderPickItem "Couldn't resolve that folder to a real path on this device"
                    withContext(Dispatchers.IO) {
                        runCatching { runtime.setMounts(container, current.copy(home = HomeChoice.Chosen(resolved.absolutePath))) }
                    }.exceptionOrNull()?.let { userFacingErrorMessage(it) }
                },
            ),
        )
    }

    // ---- mounts (docs/SPEC.md 3d) ----

    private fun mountItems(runtime: ContainerRuntime, info: ContainerInfo, current: EnvironmentMounts): List<CatalogItem> {
        val container = info.container
        val mounts = current.folders
        return buildList {
            add(
                FolderPickItem(
                    id = "container_mount_add",
                    title = "Add a folder",
                    subtitle = "Shares a folder from this device into this container, from its next start",
                    onPicked = { _, uri ->
                        val resolved = withContext(Dispatchers.IO) { GamesRootPrefs.resolveStoragePath(uri) }
                            ?: return@FolderPickItem "Couldn't resolve that folder to a real path on this device"
                        val name = uniqueMountName(resolved.name.ifBlank { "folder" }, mounts.map { it.name })
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { runtime.setMounts(container, current.copy(folders = mounts + ExtraMount(resolved.absolutePath, name))) }
                        }
                        val exc = outcome.exceptionOrNull()
                        Log.w("droidtop.containers", "Extra mount failed", exc)
                        exc?.let { userFacingErrorMessage(it) }
                    },
                ),
            )
            if (mounts.isEmpty()) {
                add(ActionItem(id = "container_mounts_none", title = "No extra folders shared", subtitle = "Add one above"))
            }
            mounts.forEach { mount ->
                add(
                    AsyncActionItem(
                        id = "container_mount:${mount.name}",
                        title = mount.name,
                        subtitle = "${mount.hostPath} \u2192 ${mount.containerPath}",
                        confirmTitle = "Stop sharing ${mount.name}?",
                        run = { _, _ ->
                            runtime.setMounts(container, current.copy(folders = mounts - mount))
                            "Removed."
                        },
                    ),
                )
            }
        }
    }

    /** [base], or [base] followed by the first free " 2", " 3", ... suffix not already in [existing]. */
    private fun uniqueMountName(base: String, existing: List<String>): String {
        if (base !in existing) return base
        var n = 2
        while ("$base $n" in existing) n++
        return "$base $n"
    }

    /**
     * "Update its software" (docs/SPEC.md 3d; Droidtop/tracker#757 slice 4): the container's own package manager,
     * found inside it ([PackageUpgrade.FIND_SCRIPT]), refreshes and upgrades what is installed, as root, a step at a
     * time on the row; it ends with what was upgraded or what the manager said when it failed.
     */
    private fun updateSoftwareItem(info: ContainerInfo, runtime: ContainerRuntime): CatalogItem {
        val container = info.container
        return AsyncActionItem(
            id = "container_update_software",
            title = "Update its software",
            subtitle = "Upgrades what is in it with its own package manager; nothing new is installed",
            confirmTitle = "Upgrade the software in ${info.displayName}? Programs running in it may need restarting afterwards.",
            run = { _, onStatus ->
                if (container.role == ContainerRole.SIBLING && runtime.siblingsNeedStart && !info.running) {
                    onStatus("Starting it")
                    runtime.start(container)
                }
                onStatus("Finding its package manager")
                val root = dev.droidtop.runtime.ContainerUser.Spec("root", null)
                val found = runtime.exec(container, listOf("/bin/sh", "-c", PackageUpgrade.FIND_SCRIPT), emptyMap(), root)
                val manager = PackageUpgrade.byId(found.stdout)
                    ?: error("it has none droidtop knows (apk, apt, dnf, pacman or zypper)")
                var last = ""
                for (step in manager.steps) {
                    onStatus("${step.line} (${manager.label})")
                    val result = runtime.exec(container, step.command, step.env, root)
                    last = result.stdout + "\n" + result.stderr
                    check(result.succeeded) { "${manager.label} stopped: ${PackageUpgrade.failureLine(last)}" }
                }
                PackageUpgrade.summary(manager, last) ?: "Done (${manager.label})"
            },
        )
    }

    private fun terminalItem(info: ContainerInfo, runtime: ContainerRuntime, desktopUp: Boolean): CatalogItem {
        if (!desktopUp) {
            return ActionItem(
                id = "container_terminal",
                title = "Terminal",
                subtitle = "The terminal opens on the desktop, which is off. Open Desktop, then choose Terminal again",
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
                // On the desktop, where it is; this row does not switch modes (Droidtop/tracker#639).
                "Opened on the desktop. Switch to Desktop to use it."
            },
        )
    }

    // ---- copy (Droidtop/tracker#757) ----

    /** The name typed for a container's copy (by container id), until the copy is made. */
    private val copyNames = ConcurrentHashMap<String, String>()

    /** Make a copy: a new container with this one's files and settings, made while it is stopped, as Delete is. */
    private fun copyItems(runtime: ContainerRuntime, info: ContainerInfo, running: Boolean, others: List<String>): List<CatalogItem> {
        runtime.cloneUnavailableReason?.let {
            return listOf(ActionItem(id = "container_copy", title = "Make a copy", subtitle = it))
        }
        if (running) {
            val stop = if (info.container.role == ContainerRole.PRIMARY) "Live desktop: stop it to copy it" else "Running: stop it to copy it"
            return listOf(ActionItem(id = "container_copy", title = "Make a copy", subtitle = stop))
        }
        val id = info.container.id
        val name = copyNames[id] ?: ContainerNames.copyName(info.displayName, others)
        return listOf(
            TextInputItem(
                id = "container_copy_name",
                title = "Name",
                subtitle = "What the copy is called; you can rename it later",
                value = name,
                onChange = { _, value ->
                    val typed = value.trim()
                    if (typed.isEmpty()) {
                        copyNames.remove(id)
                    } else {
                        copyNames[id] = typed
                    }
                },
            ),
            AsyncActionItem(
                id = "container_copy",
                title = "Make a copy",
                subtitle = "\"$name\" with everything installed in ${info.displayName}, its home and folders",
                run = { ctx, onStatus ->
                    onStatus("Copying ${info.displayName}...")
                    dev.droidtop.app.ContainerClone.clone(ctx, runtime, info, name)
                    copyNames.remove(id)
                    "Made \"$name\". It is in Containers."
                },
            ),
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
            )
        }
        return AsyncActionItem(
            id = "container_delete",
            title = "Delete",
            subtitle = "Removes the container and everything installed in it",
            confirmTitle = "Delete ${info.displayName} and everything in it?",
            run = { ctx, _ ->
                // What it exported into the desktop goes with it (docs/SPEC.md 3d "Exported apps").
                dev.droidtop.app.ContainerRemoval.remove(ctx, runtime, info)
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
            on && desktopUp -> "CUPS is not running (yet). The desktop does not wait for it; if this stays, the reason is " +
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
                            dev.droidtop.library.Links.open(ctx, "http://127.0.0.1:${CompositorProvisioning.PRINTING_WEB_PORT}/admin")
                        },
                    ),
                )
            }
        }
    }

    // ---- fonts (Droidtop/tracker#390) ----

    private suspend fun fontItems(context: Context): List<CatalogItem> {
        val on = withContext(Dispatchers.IO) { DesktopSetupPrefs.allLanguageFonts(context) }
        return listOf(
            ToggleItem(
                id = "container_all_language_fonts",
                title = "Fonts for all languages",
                subtitle = if (on) {
                    "Chinese, Japanese and Korean text and emoji. About 100 MB, downloaded as a job in Downloads and installs " +
                        "while the desktop runs, on the networks Download rules allow."
                } else {
                    "Off: Chinese, Japanese and Korean text and emoji show as boxes."
                },
                current = on,
                onToggle = { ctx, value ->
                    DesktopSetupPrefs.setAllLanguageFonts(ctx, value)
                    if (value) dev.droidtop.app.DesktopFontsJob.ensure(ctx)
                },
            ),
        )
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
                run = { ctx -> SettingsLaunch.start(ctx, Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
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
            return listOf(ActionItem(id = "container_devices", title = "Not available here", subtitle = reason))
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
                    withContext(Dispatchers.IO) { runCatching { runtime.setSharedDevices(container, next.sorted()) } }
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
                            add(ActionItem(id = "create_version", title = "Couldn't list the versions", subtitle = FailureText.of("Containers", error)))
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
                                    val created = runtime.createSibling(image, name)
                                    ContainerPrograms.recordCreated(ctx, created.id)
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
                                run = { ctx, onStatus ->
                                    onStatus("Downloading $reference...")
                                    val created = runtime.createSibling(RootfsImage(reference = reference), name)
                                    ContainerPrograms.recordCreated(ctx, created.id)
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

/** How long the container page measures a running container's use when it opens: half a second, so the page is quick. */
private const val PAGE_USAGE_SAMPLE_MS = 500L
