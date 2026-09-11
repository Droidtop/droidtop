package dev.droidtop.runtime.windows

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.gamenative.ui.data.XServerState
import app.gamenative.ui.screen.xserver.buildVkBasaltConfig
import app.gamenative.ui.screen.xserver.changeWineAudioDriver
import app.gamenative.ui.screen.xserver.extractArm64ecInputDLLs
import app.gamenative.ui.screen.xserver.extractGraphicsDriverFiles
import app.gamenative.ui.screen.xserver.extractx86_64InputDlls
import app.gamenative.ui.screen.xserver.setImagefsContainerVariant
import app.gamenative.ui.screen.xserver.setupWineSystemFiles
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import com.winlator.core.DXVKHelper
import com.winlator.core.OnExtractFileListener
import com.winlator.core.WineInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import com.winlator.xserver.ScreenInfo
import java.io.File
import timber.log.Timber

/**
 * Everything that has to be true of a Wine prefix BEFORE a guest is
 * started in it.
 *
 * This is the half of gamenative's launch that droidtop's own Wine path
 * used to skip entirely, and skipping it is not a cosmetic difference:
 * the prefix's drive letters are symlinks something has to create
 * (`WineUtils.createDosdevicesSymlinks`), the D3D wrapper's DLLs are
 * files something has to extract (`extractDXWrapperFiles`), the Vulkan
 * driver the Vortek renderer talks to is a file something has to put
 * where the guest can load it (`extractGraphicsDriverFiles`), and which
 * audio backend Wine itself uses is a registry value something has to
 * write (`changeWineAudioDriver`). A launch into a prefix none of that
 * has happened to reaches a game that cannot see its own folder, cannot
 * create a device and cannot make a sound.
 *
 * None of that work is reimplemented here. Every step is gamenative's
 * own function, called in gamenative's own order; what this object owns
 * is only the orchestration droidtop needs and gamenative's own copy of
 * cannot be reused from: theirs is spliced through a Compose screen's
 * state, a Steam app id and a splash-text event bus. The functions it
 * calls are `internal` in the fork for exactly this reason, so there is
 * one implementation of each step rather than droidtop's and theirs.
 *
 * Deliberately not included, each Steam- or store-specific:
 * `extractSteamFiles`/`SteamTokenLogin` (reached from inside
 * `setupWineSystemFiles` only when the container asks for Steam),
 * `GameFixesRegistry`, `PreInstallSteps`, and the encrypted-app-ticket
 * request.
 */
internal object WinePrefixPreparation {

    /**
     * Brings [prefix] up to date and returns the environment variables
     * the preparation itself contributed -- the graphics-driver and
     * DX-wrapper steps communicate through an [EnvVars] they write into
     * (ICD paths, `VKBASALT_CONFIG_FILE`, `DXVK_*`), so the launcher
     * must be given the same instance rather than a fresh one.
     *
     * Runs on whatever thread calls it: every step is disk work, and the
     * caller is already off the main thread.
     */
    suspend fun prepare(context: Context, prefix: Container, screenInfo: ScreenInfo): EnvVars {
        val imageFs = ImageFs.find(context)
        val containerManager = ContainerManager(context)
        val contentsManager = ContentsManager(context).apply { syncContents() }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, prefix.wineVersion)

        // Where wine lives for THIS prefix, before anything reads it: a
        // proton build is at opt/<version>, the default build at
        // opt/wine. gamenative does the same in its own pre-launch phase.
        if (!wineInfo.isMainWineVersion()) {
            imageFs.setWinePath(wineInfo.path)
        } else {
            imageFs.setWinePath(imageFs.rootDir.path + "/opt/wine")
        }

        // "xuser" is a symlink to one container's directory, and HOME is
        // set through it for every guest process. Activating is what
        // repoints it, so it happens per launch and not only when the
        // prefix was created -- otherwise a second container (or a
        // reinstalled base system) would leave the guest's HOME pointing
        // at someone else's prefix.
        containerManager.activateContainer(prefix)

        val state = mutableStateOf(
            XServerState(
                graphicsDriver = prefix.graphicsDriver,
                graphicsDriverVersion = prefix.graphicsDriverVersion,
                audioDriver = prefix.audioDriver,
                dxwrapper = prefix.dxWrapper,
                dxwrapperConfig = DXVKHelper.parseConfig(prefix.dxWrapperConfig),
                screenSize = prefix.screenSize,
                wineInfo = wineInfo,
            ),
        )

        // The same three markers gamenative reads: a prefix that has
        // never booted, or one whose base image or wine build changed
        // under it, needs the full patch pass rather than the
        // incremental one.
        val appliedVariant = prefix.getExtra("appliedContainerVariant")
        val appliedWineVersion = prefix.getExtra("appliedWineVersion")
        val markersPresent = appliedVariant.isNotEmpty() && appliedWineVersion.isNotEmpty()
        val variantChanged = markersPresent && prefix.containerVariant != appliedVariant
        val wineVersionChanged = markersPresent && prefix.wineVersion != appliedWineVersion
        val imageChanged = prefix.getExtra("imgVersion") != imageFs.version.toString()
        val firstTimeBoot = prefix.getExtra("appVersion").isEmpty() ||
            variantChanged || wineVersionChanged || imageChanged
        Timber.i("Preparing Wine prefix %s (first boot: %s)", prefix.id, firstTimeBoot)

        // A 32-bit wine build has no syswow64: every extraction has to
        // be redirected into system32, and anything already aimed there
        // dropped. gamenative's own listener, same condition.
        val extractListener: OnExtractFileListener? = if (!wineInfo.isWin64) {
            OnExtractFileListener { destination, _ ->
                destination?.path?.let { path ->
                    if (path.contains("system32/")) null else File(path.replace("syswow64/", "system32/"))
                }
            }
        } else {
            null
        }

        val envVars = EnvVars()
        setupWineSystemFiles(
            context,
            firstTimeBoot,
            screenInfo,
            state,
            prefix,
            containerManager,
            envVars,
            contentsManager,
            extractListener,
        )
        // Input DLLs for the wine builds that need them -- both are
        // no-ops for any other build, and which build a prefix runs is
        // the prefix's own field, so the gate is gamenative's.
        extractArm64ecInputDLLs(context, prefix)
        extractx86_64InputDlls(context, prefix)
        extractGraphicsDriverFiles(
            context,
            state.value.graphicsDriver,
            // setupWineSystemFiles normalises "dxvk"/"vkd3d" into the
            // versioned names this step switches on, so it is read back
            // out of the state rather than off the container.
            state.value.dxwrapper,
            state.value.dxwrapperConfig ?: DXVKHelper.parseConfig(prefix.dxWrapperConfig),
            prefix,
            envVars,
            firstTimeBoot,
            buildVkBasaltConfig(
                effect = prefix.getExtra("sharpnessEffect", "None"),
                sharpnessLevel = prefix.getExtra("sharpnessLevel", "100").toIntOrNull() ?: 100,
                sharpnessDenoise = prefix.getExtra("sharpnessDenoise", "100").toIntOrNull() ?: 100,
            ),
        )
        // Which backend WINE itself hands audio to. The server droidtop
        // starts (PulseAudio or ALSA) is the other half of the same
        // decision, and both read the prefix's one audioDriver field.
        changeWineAudioDriver(prefix.audioDriver, prefix, imageFs)
        setImagefsContainerVariant(context, prefix)
        return envVars
    }
}
