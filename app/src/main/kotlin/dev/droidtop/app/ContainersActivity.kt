package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.runtime.BundledImageRepositories
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ImageCatalogRole
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.RootfsImage
import dev.droidtop.runtime.linux.root.CraneImageCatalogResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user-facing container/distro manager (docs/SPEC.md §3d — explicit
 * direction: containers are the user's machines, managed first-class, not
 * internal plumbing). Distrobox is the interaction model: a flat list of
 * every container this device has (role, backend, live running state) with
 * start/stop/delete, plus creation from the live-resolved image catalog
 * (§3a "Recommended") or a hand-typed OCI reference ("Custom").
 *
 * Sits directly on [ContainerRuntime] via [ContainerRuntimeFactory] — the
 * same backend the desktop session uses, no separate management path. The
 * PRIMARY container is listed like everything else but delete is guarded
 * while it's the live desktop (stopping the desktop out from under the
 * compositor is DesktopSessionService's job, not a list row's).
 */
class ContainersActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { dev.droidtop.app.ui.DroidtopTheme { ContainersScreen() } }
    }
}

@Composable
private fun ContainersScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var runtime by remember { mutableStateOf<ContainerRuntime?>(null) }
    var containers by remember { mutableStateOf<List<ContainerInfo>?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val rt = runtime ?: ContainerRuntimeFactory.select(context).also { runtime = it }
        containers = runCatching { rt.listContainers() }
            .onFailure { errorMessage = "Couldn't list containers: ${it.message}" }
            .getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { refresh() } }

    fun runAction(label: String, action: suspend (ContainerRuntime) -> Unit) {
        val rt = runtime ?: return
        scope.launch(Dispatchers.IO) {
            busyMessage = label
            errorMessage = null
            runCatching { action(rt) }
                .onFailure { errorMessage = "$label failed: ${it.message}" }
            refresh()
            busyMessage = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Containers", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { scope.launch(Dispatchers.IO) { refresh() } }) { Text("Refresh") }
            Button(onClick = { showCreate = !showCreate }) { Text(if (showCreate) "Close" else "New container") }
        }
        busyMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(vertical = 6.dp))
        }
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (showCreate) {
            CreateContainerPanel(
                enabled = busyMessage == null,
                onCreateFromRepository = { repo ->
                    runAction("Creating from ${repo.repository}") { rt ->
                        val resolver = CraneImageCatalogResolver(context)
                        val tag = resolver.listTags(repo).firstOrNull()
                            ?: error("no tags published under ${repo.registry}/${repo.repository}")
                        rt.createSibling(resolver.resolve(repo, tag).toRootfsImage())
                    }
                    showCreate = false
                },
                onCreateFromReference = { reference ->
                    runAction("Creating from $reference") { rt ->
                        rt.createSibling(RootfsImage(reference = reference))
                    }
                    showCreate = false
                },
            )
        }

        when (val list = containers) {
            null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            else -> {
                if (list.isEmpty()) {
                    Text(
                        "No containers yet. Create one to give this device a real Linux distro.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                    items(list, key = { it.container.id }) { info ->
                        ContainerRow(
                            info = info,
                            actionsEnabled = busyMessage == null,
                            confirmingDelete = confirmDeleteId == info.container.id,
                            onStart = { runAction("Starting ${info.container.id}") { it.start(info.container) } },
                            onStop = { runAction("Stopping ${info.container.id}") { it.stop(info.container) } },
                            onDeleteRequested = { confirmDeleteId = info.container.id },
                            onDeleteConfirmed = {
                                confirmDeleteId = null
                                runAction("Deleting ${info.container.id}") { it.destroy(info.container) }
                            },
                            onDeleteCancelled = { confirmDeleteId = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerRow(
    info: ContainerInfo,
    actionsEnabled: Boolean,
    confirmingDelete: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDeleteRequested: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteCancelled: () -> Unit,
) {
    // The PRIMARY container hosts the live desktop compositor -- deleting
    // it out from under a Connected session would tear the desktop down as
    // a side effect of a list row. Guarded here (stop the session first);
    // a stopped/idle primary is deletable like anything else.
    val primaryGuarded = info.container.role == ContainerRole.PRIMARY &&
        DesktopSessionService.state.value is DesktopSessionState.Connected
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.container.id, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${info.container.role.name.lowercase()} · ${info.container.backend.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (info.running) "RUNNING" else "STOPPED",
                style = MaterialTheme.typography.labelMedium,
                color = if (info.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (info.running) {
                TextButton(onClick = onStop, enabled = actionsEnabled) { Text("Stop") }
            } else {
                TextButton(onClick = onStart, enabled = actionsEnabled) { Text("Start") }
            }
            Spacer(Modifier.weight(1f))
            when {
                confirmingDelete -> {
                    Text("Delete container and its storage?", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDeleteCancelled) { Text("Keep") }
                    TextButton(onClick = onDeleteConfirmed, enabled = actionsEnabled) { Text("Delete") }
                }
                primaryGuarded -> Text(
                    "Live desktop — stop the session to delete",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> TextButton(onClick = onDeleteRequested, enabled = actionsEnabled) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun CreateContainerPanel(
    enabled: Boolean,
    onCreateFromRepository: (KnownImageRepository) -> Unit,
    onCreateFromReference: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Same live-catalog model as onboarding's desktop step (docs/SPEC.md
    // §3a): the seed list only names repositories; versions resolve at
    // create time. arm64 is the hard filter -- droidtop only targets ARM64
    // hardware, a repo without it can't run here at all.
    val candidates = remember {
        BundledImageRepositories.load(context).repositories
            .filter { it.arm64Available }
            .filter { it.role == ImageCatalogRole.SIBLING || it.role == ImageCatalogRole.BOTH }
    }
    var customReference by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text("Recommended", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        candidates.forEach { repo ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(repo.os, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${repo.registry}/${repo.repository}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onCreateFromRepository(repo) }, enabled = enabled) { Text("Create") }
            }
        }
        Text(
            "Custom OCI reference",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (customReference.isEmpty()) {
                    Text("docker.io/library/debian:bookworm", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = customReference,
                    onValueChange = { customReference = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onCreateFromReference(customReference.trim()) },
                enabled = enabled && customReference.isNotBlank(),
            ) { Text("Create") }
        }
    }
}
