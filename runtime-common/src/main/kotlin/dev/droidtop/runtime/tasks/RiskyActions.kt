package dev.droidtop.runtime.tasks

import android.content.Context

/**
 * The classes of risky action Settings > Risky actions switches (docs/SPEC.md "Risky actions", Droidtop/tracker#248).
 * A risky action changes another app or the system through the privileged helper; each class has its own switch,
 * all of them behind a master switch, all off until the person turns them on.
 */
enum class RiskyClass(val id: String, val title: String, val summary: String) {
    /** All files access through appops, a runtime permission through pm: another app gets more than it had. */
    GRANT_ACCESS(
        "grant_access",
        "Give another app access",
        "Let droidtop give an emulator All files access or a permission it asks for, instead of you doing it in Android's own screens",
    ),

    /** The privileged helper writing a file in another app's folder (an emulator's config or BIOS folder). */
    OTHER_APP_FILES(
        "other_app_files",
        "Write another app's files",
        "Let droidtop change an emulator's settings file or put a BIOS file in its folder, where Android keeps droidtop out",
    ),

    /** The helper app changing a screen's setting for the whole system: its refresh rate (`cmd display`). */
    DISPLAY_SETTINGS(
        "display_settings",
        "Change display settings",
        "Let droidtop set a screen's refresh rate for every app, through the helper app",
    ),

    /** The helper app changing Android's accessibility settings for every app: font and display size, TalkBack. */
    ACCESSIBILITY_SETTINGS(
        "accessibility_settings",
        "Change accessibility settings",
        "Let droidtop set font size, display size, magnification, contrast, inversion and TalkBack for every app, through the helper app",
    ),

    /** Root-level commands through a provider that holds root (Sui, or a root provider plugin). */
    ROOT_COMMANDS(
        "root_commands",
        "Root-level commands",
        "Let droidtop run commands as root through a provider that has it, such as placing a RetroArch core in RetroArch's own folder",
    ),
}

/** Whether the risky action [RiskyClass] may run: the one question the gate asks. */
fun interface RiskyGate {
    fun allows(risk: RiskyClass): Boolean
}

/** The switches as stored: the master and one per class. Pure, so the rule is unit-tested. */
data class RiskySwitches(val master: Boolean = false, val classes: Set<RiskyClass> = emptySet()) {
    /** A class is on only while the master is on as well. */
    fun allows(risk: RiskyClass): Boolean = master && risk in classes

    companion object {
        val OFF = RiskySwitches()
    }
}

/**
 * The wording of the explicit confirmation each use shows, naming the app and the exact permission, appop or file.
 * Plain words: what will change, in whose app, and how to undo it. Pure, so the sentences are unit-tested.
 */
object RiskyPrompts {
    const val ALL_FILES_OP = "MANAGE_EXTERNAL_STORAGE"

    fun allFilesTitle(app: String): String = "Let $app read your game folders"

    fun allFilesConfirm(app: String, packageName: String): String =
        "Give $app ($packageName) All files access? droidtop will set the appop $ALL_FILES_OP to allow for it, " +
            "so it can read and write every file you can see. Take it back in Android's All files access screen."

    fun permissionTitle(app: String, label: String): String = "Let $app use $label"

    fun permissionConfirm(app: String, packageName: String, permission: String): String =
        "Give $app ($packageName) the permission $permission? droidtop will grant it for you. " +
            "Take it back in the app's permissions in Android's settings."

    fun writeFileConfirm(app: String, path: String): String =
        "Write $path in $app's folder? droidtop will replace that file as the system's shell user, " +
            "because Android keeps droidtop out of $app's folder."

    fun addFileConfirm(app: String, folder: String): String =
        "Put the file you pick into $folder in $app's folder? droidtop will write it as the system's shell user, " +
            "because Android keeps droidtop out of $app's folder."

    /**
     * What the helper's "Get the BIOS" row asks before it downloads: the provider, where from when it says, the exact
     * file and the folder it goes to. Through the helper ([viaHelper]) it also says whose folder and as whom.
     */
    fun getBiosConfirm(plugin: String, source: String?, path: String, app: String, viaHelper: Boolean): String =
        "Download this file with $plugin" + (source?.let { " from $it" } ?: "") + " and put it at $path? " +
            if (viaHelper) "droidtop will write it into $app's folder as the system's shell user, because Android keeps droidtop out of it."
            else "It goes into $app's BIOS folder."

    fun placeCoresConfirm(targets: List<String>, app: String): String =
        "Place " + (if (targets.size == 1) "this core" else "these ${targets.size} cores") + " in $app's private folder: " +
            targets.take(4).joinToString(", ") + (if (targets.size > 4) " and ${targets.size - 4} more" else "") +
            "? droidtop will copy " + (if (targets.size == 1) "it" else "them") + " there as root through your root provider."

    fun placeCoreConfirm(core: String, app: String, target: String): String =
        "Place the $core core at $target in $app's private folder? droidtop will copy it there as root " +
            "through your root provider."

    /** Where the person turns a class on, for a row that is shown without it. */
    fun turnOnHint(risk: RiskyClass): String =
        "To let droidtop do this, turn on Risky actions in Settings, then \"${risk.title}\"."
}

/**
 * The person's switches, kept in droidtop's own preferences. [allows] answers from memory and is false until the
 * switches are loaded, so a privileged call can never run on a guess. Loading is file work: [preload] reads on its own
 * thread, [get] reads where the caller is already off the main thread.
 */
object RiskyActions : RiskyGate {
    private const val PREFS = "risky_actions"
    private const val KEY_MASTER = "master"

    @Volatile
    private var cached: RiskySwitches? = null

    override fun allows(risk: RiskyClass): Boolean = cached?.allows(risk) == true

    /** Whether the master switch is on, from memory (false until loaded): cheap enough to draw a row with. */
    fun masterOn(): Boolean = cached?.master == true

    /** The switches, reading the preferences on first use: call off the main thread. */
    fun get(context: Context): RiskySwitches = cached ?: read(context).also { cached = it }

    /** Loads the switches on a background thread, once, so [allows] has them by the time anything privileged asks. */
    fun preload(context: Context) {
        if (cached != null) return
        val application = context.applicationContext
        Thread { get(application) }.apply { name = "droidtop-risky-switches"; isDaemon = true; start() }
    }

    fun setMaster(context: Context, on: Boolean) = store(context, get(context).copy(master = on))

    fun setClass(context: Context, risk: RiskyClass, on: Boolean) {
        val current = get(context)
        store(context, current.copy(classes = if (on) current.classes + risk else current.classes - risk))
    }

    private fun read(context: Context): RiskySwitches {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RiskySwitches(
            master = prefs.getBoolean(KEY_MASTER, false),
            classes = RiskyClass.entries.filter { prefs.getBoolean("class_${it.id}", false) }.toSet(),
        )
    }

    private fun store(context: Context, switches: RiskySwitches) {
        cached = switches
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MASTER, switches.master)
        RiskyClass.entries.forEach { edit.putBoolean("class_${it.id}", it in switches.classes) }
        edit.apply()
    }
}

/**
 * The caution group notice a screen shows while a risky action it offers is switched off (docs/SPEC.md 7k, "Notices",
 * G1-G6): the feature named, what it would change, and Turn on, which switches on Risky actions and that one class.
 * Each use still asks its own explicit question first ([RiskyPrompts]). One notice per group, never a sentence
 * appended to every row.
 */
object RiskyNotices {
    fun notice(risk: RiskyClass): dev.droidtop.library.settings.Notice {
        val (title, body, details) = when (risk) {
            RiskyClass.OTHER_APP_FILES -> Triple(
                "Emulator setup is off for safety",
                "It changes emulator config files.",
                "Turning it on lets droidtop rewrite emulator settings files and put BIOS files in an emulator's folder, through the helper app. Each change asks first.",
            )
            RiskyClass.ROOT_COMMANDS -> Triple(
                "Core installs are off for safety",
                "It downloads emulator cores from the internet.",
                "Turning it on lets droidtop place RetroArch cores in RetroArch's own folder as root, through your root provider. Each install asks first.",
            )
            RiskyClass.DISPLAY_SETTINGS -> Triple(
                "Display controls are off for safety",
                "They change screen refresh and scaling.",
                "Turning it on lets droidtop set a screen's refresh rate for every app, through the helper app.",
            )
            RiskyClass.ACCESSIBILITY_SETTINGS -> Triple(
                "Quick actions are off for safety",
                "Some change system settings.",
                "Turning it on lets droidtop set font size, display size, magnification, contrast, inversion and TalkBack for every app, through the helper app.",
            )
            RiskyClass.GRANT_ACCESS -> Triple(
                "Granting access is off for safety",
                "It gives emulators access they ask for.",
                "Turning it on lets droidtop give an emulator All files access or a permission it asks for, instead of you doing it in Android's own screens. Each grant asks first.",
            )
        }
        return dev.droidtop.library.settings.Notice(
            tone = dev.droidtop.library.settings.NoticeTone.Caution,
            title = title,
            body = body,
            details = "$details Settings > Risky actions > ${risk.title}.",
            action = dev.droidtop.library.settings.NoticeAction("Turn on") { context ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    RiskyActions.setMaster(context, true)
                    RiskyActions.setClass(context, risk, true)
                }
            },
        )
    }

    /** [notice] as a group notice row. */
    fun item(id: String, risk: RiskyClass): dev.droidtop.library.settings.NoticeItem =
        dev.droidtop.library.settings.NoticeItem(id, notice(risk), dev.droidtop.library.settings.NoticePlacement.Group)
}
