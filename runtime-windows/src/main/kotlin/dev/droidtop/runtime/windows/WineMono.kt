package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineInfo
import com.winlator.core.WineRegistryEditor
import dev.droidtop.runtime.windows.utils.PinnedReleaseAsset
import java.io.File
import java.io.RandomAccessFile
import timber.log.Timber

/**
 * Wine Mono for .NET Framework games on any Wine build (Droidtop/tracker#895).
 *
 * A .NET Framework program (XNA and FNA games among them, Reus) exits at once when Wine's mscoree finds no Mono
 * runtime: "Wine Mono is not installed". No Wine build droidtop runs carries one: GameNative's do not, and
 * proton-wine-tux does not either (owner, 2026-11-05: Mono is installed after the fact, not packaged with Wine).
 * droidtop's prefixes come from a prebuilt pack, so Wine's own first-boot install never runs either. mscoree also takes a runtime from anywhere the prefix's `HKCU\Software\Wine\Mono\RuntimePath` names
 * (dlls/mscoree/metahost.c, get_mono_path_registry), whatever the Wine build. So, the first time a .NET Framework game
 * starts in a prefix that has none, Wine Mono's release (the version this tree's mscoree asks for) is unpacked once into
 * the image, which Wine sees as Z:, and the prefix is pointed at it. Nothing is installed into the prefix itself.
 */
internal object WineMono {
    const val VERSION = "10.4.1"
    private const val DIR = "imagefs/opt/wine-mono"

    private val release = PinnedReleaseAsset(
        "wine-mono-$VERSION",
        "wine-mono-$VERSION-x86.tar.xz",
        "a16606ef0724202e6a6848ece6e0cbba64d11e2f11aefe744af1d93c6d9f99bb",
        DIR,
        "Wine Mono, which .NET Framework games need",
        urls = listOf(
            // Re-hosted unmodified (Droidtop/droidtop-components sources/mirror.json, group windows-runtime), then Wine Mono's own.
            "https://github.com/Droidtop/droidtop-components/releases/download/windows-runtime/wine-mono-$VERSION-x86.tar.xz",
            "https://github.com/madewokherd/wine-mono/releases/download/wine-mono-$VERSION/wine-mono-$VERSION-x86.tar.xz",
        ),
        type = TarCompressorUtils.Type.XZ,
    )

    /** The runtime as Wine sees it: under Z:, the image's root. */
    const val RUNTIME_PATH = "Z:\\opt\\wine-mono\\wine-mono-$VERSION"

    /**
     * Before [executable] starts in [container]: when it is a .NET Framework program and neither the prefix
     * (`C:\windows\mono\mono-2.0`) nor its Wine build (`share/wine/mono`) has a Mono runtime, fetches Wine Mono once
     * and names it in the prefix's registry. Returns whether the prefix now has a runtime to use. Disk and network.
     */
    suspend fun prepare(context: Context, container: Container, executable: File): Boolean {
        if (!isDotNetFramework(executable)) return true
        val prefix = File(container.rootDir, ".wine")
        if (File(prefix, "drive_c/windows/mono/mono-2.0").isDirectory) return true
        val wine = runCatching {
            WineInfo.fromIdentifier(context, ContentsManager(context).apply { syncContents() }, container.wineVersion).path
        }.getOrNull()
        if (wine != null && File(wine, "share/wine/mono").listFiles()?.any { it.name.startsWith("wine-mono") } == true) return true
        release.ensureInstalled(context) {}
        WineRegistryEditor(File(prefix, "user.reg")).use { reg ->
            if (reg.getStringValue("Software\\Wine\\Mono", "RuntimePath") != RUNTIME_PATH) {
                reg.setStringValue("Software\\Wine\\Mono", "RuntimePath", RUNTIME_PATH)
            }
        }
        Timber.i("Wine Mono %s named for prefix %s (%s is a .NET Framework program)", VERSION, container.id, executable.name)
        return true
    }

    /** Whether [file] is a .NET Framework (CLR) program: a PE image with a COM descriptor directory. Disk read. */
    fun isDotNetFramework(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(raf.length(), 4096L).toInt())
            raf.readFully(head)
            hasClrHeader(head)
        }
    }.getOrDefault(false)

    /** Whether [head], a PE image's first bytes, has a non-empty CLR (COM descriptor, index 14) data directory. Pure. */
    fun hasClrHeader(head: ByteArray): Boolean {
        fun u16(at: Int) = if (at + 2 <= head.size) (head[at].toInt() and 0xff) or ((head[at + 1].toInt() and 0xff) shl 8) else -1
        fun u32(at: Int) = if (at + 4 <= head.size) u16(at).toLong() or (u16(at + 2).toLong() shl 16) else -1L
        if (u16(0) != 0x5a4d) return false // MZ
        val pe = u32(0x3c).toInt()
        if (pe <= 0 || u32(pe) != 0x00004550L) return false // PE\0\0
        val optional = pe + 24
        val directories = when (u16(optional)) {
            0x10b -> optional + 96 // PE32
            0x20b -> optional + 112 // PE32+
            else -> return false
        }
        val count = u32(directories - 4)
        if (count <= 14) return false
        return u32(directories + 14 * 8).let { it > 0 } && u32(directories + 14 * 8 + 4).let { it > 0 }
    }
}
