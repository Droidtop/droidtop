package dev.droidtop.mapping

/**
 * A controller layout as Steam stores it (Droidtop/tracker#618; docs/SPEC.md 5b "Controller layouts"): a KeyValues
 * file whose root is `controller_mappings`. The owner, 2026-10-23: "we want all profiles we use to be compatible with
 * steam. If a steam user who doesn't use droidtop wants to use it, they should be able to". So the file is the stored
 * layout, and this class is a read view over its node tree ([root]): everything a reader here does not know is still in
 * the tree and is written back unchanged.
 *
 * The parts, in Steam's words:
 * - a **group** binds one physical source in a **mode** (`four_buttons`, `dpad`, `joystick_move`, `joystick_mouse`,
 *   ...); its **inputs** (`button_a`, `dpad_north`, `click`, ...) each have **activators** (`Full_Press`,
 *   `Long_Press`, `Double_Press`, `Start_Press`, `Release_Press`, `chord`), and an activator's **bindings** are what
 *   happens (`key_press W`, `mouse_button LEFT`, `xinput_button A`, `game_action Default jump`, ...);
 * - a **preset** is one action set or layer: which group is on which source (`group_source_bindings`, a group id to
 *   "`<source> active|inactive [modeshift]`");
 * - `actions` and `action_layers` name the sets and layers, and `localization` gives `#`-prefixed titles their words.
 */
class ControllerLayout(val root: KvNode) {
    init {
        require(root.key.equals(ROOT, ignoreCase = true) && root.isBlock) { "not a controller layout (its root is \"${root.key}\")" }
    }

    val version: String? get() = root.string("version")
    val title: String? get() = root.string("title")
    val description: String? get() = root.string("description")
    val controllerType: String? get() = root.string("controller_type")

    val groups: List<Group> get() = root.all("group").map(::Group)
    val presets: List<Preset> get() = root.all("preset").map(::Preset)

    /** The layout-wide `settings` block. */
    val settings: Map<String, String> get() = root.first("settings")?.strings().orEmpty()

    /** The action sets (`actions`) then the layers (`action_layers`), by name, as the presets refer to them. */
    val actionSets: List<ActionSet>
        get() = root.first("actions")?.children.orEmpty().filter { it.isBlock }.map { ActionSet(it, layer = false) } +
            root.first("action_layers")?.children.orEmpty().filter { it.isBlock }.map { ActionSet(it, layer = true) }

    fun group(id: Int): Group? = groups.firstOrNull { it.id == id }

    /**
     * [text] in [language]: a title starting with `#` is a token looked up in `localization`, in [language] and then
     * English; any other text is itself.
     */
    fun localized(text: String?, language: String = "english"): String? {
        if (text == null || !text.startsWith("#")) return text
        val token = text.removePrefix("#")
        val localization = root.first("localization") ?: return text
        return localization.first(language)?.string(token) ?: localization.first("english")?.string(token) ?: text
    }

    fun write(): String = KeyValues.write(listOf(root))

    class ActionSet(val node: KvNode, val layer: Boolean) {
        val name: String get() = node.key
        val title: String? get() = node.string("title")

        /** A layer's set: the one it sits on. */
        val parent: String? get() = node.string("parent_set_name")
    }

    class Group(val node: KvNode) {
        val id: Int? get() = node.string("id")?.trim()?.toIntOrNull()
        val mode: String? get() = node.string("mode")
        val settings: Map<String, String> get() = node.first("settings")?.strings().orEmpty()
        val inputs: List<Input> get() = node.first("inputs")?.children.orEmpty().filter { it.isBlock }.map(::Input)
        fun input(name: String): Input? = inputs.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    class Input(val node: KvNode) {
        val name: String get() = node.key
        val activators: List<Activator> get() = node.first("activators")?.children.orEmpty().filter { it.isBlock }.map(::Activator)
    }

    class Activator(val node: KvNode) {
        /** `Full_Press`, `Long_Press`, `Double_Press`, `Start_Press`, `Release_Press` or `chord`. */
        val type: String get() = node.key
        val settings: Map<String, String> get() = node.first("settings")?.strings().orEmpty()
        val bindings: List<Binding> get() = node.first("bindings")?.all("binding").orEmpty().mapNotNull { it.value }.map(Binding::parse)
    }

    class Preset(val node: KvNode) {
        val id: Int? get() = node.string("id")?.trim()?.toIntOrNull()

        /** The action set or layer this preset is ([ActionSet.name]). */
        val name: String? get() = node.string("name")
        val sources: List<SourceBinding>
            get() = node.first("group_source_bindings")?.children.orEmpty().mapNotNull { SourceBinding.parse(it.key, it.value) }
    }

    /** Group [groupId] on physical [source], [active] or not, and whether it is the source's mode-shift group. */
    data class SourceBinding(val groupId: Int, val source: String, val active: Boolean, val modeShift: Boolean) {
        companion object {
            fun parse(key: String, value: String?): SourceBinding? {
                val id = key.trim().toIntOrNull() ?: return null
                val words = value?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
                val source = words.firstOrNull() ?: return null
                return SourceBinding(id, source, "active" in words.drop(1), "modeshift" in words.drop(1))
            }
        }
    }

    companion object {
        const val ROOT = "controller_mappings"

        /** The layout in [text]: its `controller_mappings` block. Throws [KeyValuesException] or [IllegalArgumentException]. */
        fun parse(text: String): ControllerLayout {
            val root = KeyValues.parse(text).firstOrNull { it.key.equals(ROOT, ignoreCase = true) }
                ?: throw IllegalArgumentException("no $ROOT block")
            return ControllerLayout(root)
        }
    }
}

/**
 * One binding string, as Steam writes it: the action, then optionally a comma, a label and an icon
 * (`key_press SPACE, Jump, `). [raw] is kept, so a binding this reader does not know is written back as it was.
 */
sealed class Binding {
    abstract val raw: String

    /** The text after the action: the label shown in Steam's overlay, or null. */
    val label: String? get() = raw.split(',').getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    data class KeyPress(val key: String, override val raw: String) : Binding()
    data class MouseButton(val button: String, override val raw: String) : Binding()
    data class MouseWheel(val direction: String, override val raw: String) : Binding()
    data class XinputButton(val button: String, override val raw: String) : Binding()
    data class GameAction(val set: String, val action: String, override val raw: String) : Binding()
    data class ControllerAction(val words: List<String>, override val raw: String) : Binding()

    /** Held, puts group [groupId] on [source] instead of its preset's group (Steam's mode shift). */
    data class ModeShift(val source: String, val groupId: Int, override val raw: String) : Binding()
    data class Other(override val raw: String) : Binding()

    companion object {
        fun parse(raw: String): Binding {
            val words = raw.substringBefore(',').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val verb = words.firstOrNull()?.lowercase() ?: return Other(raw)
            val args = words.drop(1)
            return when {
                verb == "key_press" && args.size == 1 -> KeyPress(args[0], raw)
                verb == "mouse_button" && args.size == 1 -> MouseButton(args[0], raw)
                verb == "mouse_wheel" && args.size == 1 -> MouseWheel(args[0], raw)
                verb == "xinput_button" && args.size == 1 -> XinputButton(args[0], raw)
                verb == "game_action" && args.size == 2 -> GameAction(args[0], args[1], raw)
                verb == "controller_action" && args.isNotEmpty() -> ControllerAction(args, raw)
                verb == "mode_shift" && args.size == 2 && args[1].toIntOrNull() != null -> ModeShift(args[0], args[1].toInt(), raw)
                else -> Other(raw)
            }
        }
    }
}
