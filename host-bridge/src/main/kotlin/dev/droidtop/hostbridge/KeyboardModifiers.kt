package dev.droidtop.hostbridge

/**
 * The modifier state of one virtual keyboard, kept on droidtop's side because the compositor does not keep it
 * (docs/SPEC.md 6b "Modifiers", Droidtop/tracker#147).
 *
 * wlroots handles `zwp_virtual_keyboard_v1.key` with `update_state = false` (wlroots
 * types/wlr_virtual_keyboard_v1.c, `virtual_keyboard_key`): a Shift or Ctrl key event reaches the focused client
 * as a key, but the keyboard's modifier state, which the client reads every other key through, changes only on
 * `zwp_virtual_keyboard_v1.modifiers`. Without that request every capital arrived lowercase, `|` as `\`, and
 * Ctrl+C as `c`. So each key event is followed, when it changes them, by the modifiers it implies: the held
 * modifier keys are depressed, a Caps Lock or Num Lock press toggles its modifier in the locked set.
 *
 * Which modifier a key sets is read from the keymap the keyboard was given ([XkbModifierMap]), not assumed, so a
 * layout whose right Alt is AltGr (level 3) sets Mod5 where the US map's sets Mod1.
 */
class KeyboardModifiers(private var map: XkbModifierMap) {
    /** The masks to send, as `zwp_virtual_keyboard_v1.modifiers` takes them (latched is always 0, group 0). */
    data class Masks(val depressed: Int, val locked: Int)

    private val held = mutableMapOf<Int, Int>()
    private var locked = 0
    private var last = Masks(0, 0)

    /**
     * Records [evdevKeyCode] going down or up. Returns the masks to send after the key event when they changed,
     * null when they did not (an ordinary key, or a second Shift while one is held).
     */
    fun onKey(evdevKeyCode: Int, pressed: Boolean): Masks? {
        val bits = map.modifiersOf(evdevKeyCode)
        if (bits == 0) return null
        if (map.isLock(evdevKeyCode)) {
            if (pressed && evdevKeyCode !in held) locked = locked xor bits
            if (pressed) held[evdevKeyCode] = 0 else held.remove(evdevKeyCode)
        } else if (pressed) {
            held[evdevKeyCode] = bits
        } else {
            held.remove(evdevKeyCode)
        }
        return changed()
    }

    /**
     * Takes a new keymap for this keyboard. Keys still held keep counting, under what the new map says they set.
     * Returns the masks to send when that changed them.
     */
    fun replaceMap(newMap: XkbModifierMap): Masks? {
        map = newMap
        for (key in held.keys.toList()) held[key] = if (map.isLock(key)) 0 else map.modifiersOf(key)
        return changed()
    }

    /** Forgets everything, for a new connection whose keyboards start with no modifier set. */
    fun reset() {
        held.clear()
        locked = 0
        last = Masks(0, 0)
    }

    private fun changed(): Masks? {
        val now = Masks(held.values.fold(0) { acc, bits -> acc or bits }, locked)
        if (now == last) return null
        last = now
        return now
    }
}

/**
 * Which real modifiers (as the bit masks `wl_keyboard.modifiers` carries: Shift 1, Lock 2, Control 4, Mod1 8 ...
 * Mod5 128) each key of an XKB keymap sets, and which keys lock rather than hold, read from the keymap's text (the
 * XKB_V1 format a virtual keyboard is given).
 *
 * A key's modifiers are its own `modifier_map` entries. A key with none whose first symbol is a modifier symbol
 * another key is mapped by takes that key's modifiers: a layout's right Alt that is `ISO_Level3_Shift` sets what
 * `<LVL3>` (also `ISO_Level3_Shift`) is mapped to, Mod5, which is what XKB's own interpretation of that symbol
 * (`SetMods(modifiers=LevelThree)`, LevelThree being the virtual modifier `<LVL3>`'s map gives) does. Caps Lock,
 * Shift Lock and Num Lock lock. Not a keymap compiler: actions bound by explicit `interpret` statements to other
 * symbols are not followed.
 */
class XkbModifierMap private constructor(
    private val modifiersByEvdev: Map<Int, Int>,
    private val locks: Set<Int>,
) {
    fun modifiersOf(evdevKeyCode: Int): Int = modifiersByEvdev[evdevKeyCode] ?: 0

    fun isLock(evdevKeyCode: Int): Boolean = evdevKeyCode in locks

    companion object {
        /** No modifiers known: every key is an ordinary key. */
        val EMPTY = XkbModifierMap(emptyMap(), emptySet())

        private val REAL_MODIFIERS = listOf("Shift", "Lock", "Control", "Mod1", "Mod2", "Mod3", "Mod4", "Mod5")
        private val LOCKING_SYMBOLS = setOf("Caps_Lock", "Shift_Lock", "Num_Lock")

        /** The real modifier [name]'s mask, or 0 for a name that is not one (case as XKB writes it, any case read). */
        private fun maskOf(name: String): Int {
            val index = REAL_MODIFIERS.indexOfFirst { it.equals(name, ignoreCase = true) }
            return if (index < 0) 0 else 1 shl index
        }

        /** XKB keycodes are evdev codes plus 8 (the X11 offset). */
        private const val XKB_EVDEV_OFFSET = 8

        private val KEYCODE = Regex("""<([^>\s]+)>\s*=\s*(\d+)\s*;""")
        private val ALIAS = Regex("""alias\s*<([^>\s]+)>\s*=\s*<([^>\s]+)>\s*;""")
        private val KEY = Regex("""key\s*<([^>\s]+)>\s*\{(.*?)\}\s*;""", RegexOption.DOT_MATCHES_ALL)
        private val FIRST_SYMBOL = Regex("""\[\s*([A-Za-z0-9_]+)""")
        private val MODIFIER_MAP = Regex("""modifier_map\s+(\w+)\s*\{([^}]*)\}\s*;""")

        /** Reads [keymap]. A text with no modifier map in it gives [EMPTY], never an error. */
        fun parse(keymap: String): XkbModifierMap {
            val codes = mutableMapOf<String, Int>()
            val keycodesSection = section(keymap, "xkb_keycodes")
            KEYCODE.findAll(keycodesSection).forEach { codes[it.groupValues[1]] = it.groupValues[2].toInt() }
            ALIAS.findAll(keycodesSection).forEach { m -> codes[m.groupValues[2]]?.let { codes[m.groupValues[1]] = it } }

            val symbolsSection = section(keymap, "xkb_symbols")
            val firstSymbol = mutableMapOf<String, String>()
            KEY.findAll(symbolsSection).forEach { m ->
                FIRST_SYMBOL.find(m.groupValues[2])?.let { firstSymbol[m.groupValues[1]] = it.groupValues[1] }
            }

            val byKeyName = mutableMapOf<String, Int>()
            MODIFIER_MAP.findAll(symbolsSection).forEach { m ->
                val mask = maskOf(m.groupValues[1])
                if (mask == 0) return@forEach
                m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                    if (entry.startsWith("<") && entry.endsWith(">")) {
                        val name = entry.substring(1, entry.length - 1)
                        byKeyName[name] = (byKeyName[name] ?: 0) or mask
                    } else {
                        // A keysym entry: every key whose first symbol it is.
                        firstSymbol.filterValues { it == entry }.keys.forEach { name ->
                            byKeyName[name] = (byKeyName[name] ?: 0) or mask
                        }
                    }
                }
            }
            // Keys sharing a mapped key's modifier symbol set what it sets (a right Alt that is ISO_Level3_Shift).
            val bySymbol = mutableMapOf<String, Int>()
            byKeyName.forEach { (name, mask) -> firstSymbol[name]?.let { bySymbol[it] = (bySymbol[it] ?: 0) or mask } }
            firstSymbol.forEach { (name, symbol) ->
                if (name !in byKeyName) bySymbol[symbol]?.let { byKeyName[name] = it }
            }
            // A lock key with no map of its own still locks what XKB's interpretation of its symbol locks.
            firstSymbol.forEach { (name, symbol) ->
                if (name !in byKeyName && (symbol == "Caps_Lock" || symbol == "Shift_Lock")) byKeyName[name] = maskOf("Lock")
            }

            val modifiers = mutableMapOf<Int, Int>()
            val locks = mutableSetOf<Int>()
            byKeyName.forEach { (name, mask) ->
                val xkb = codes[name] ?: return@forEach
                val evdev = xkb - XKB_EVDEV_OFFSET
                if (evdev < 0) return@forEach
                modifiers[evdev] = mask
                if (firstSymbol[name] in LOCKING_SYMBOLS) locks += evdev
            }
            return if (modifiers.isEmpty()) EMPTY else XkbModifierMap(modifiers, locks)
        }

        /** The text of the `<kind> "..." { ... };` block of [keymap], or "" when it has none. */
        private fun section(keymap: String, kind: String): String {
            val start = keymap.indexOf(kind)
            if (start < 0) return ""
            val open = keymap.indexOf('{', start)
            if (open < 0) return ""
            var depth = 0
            for (i in open until keymap.length) {
                when (keymap[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return keymap.substring(open + 1, i)
                }
            }
            return keymap.substring(open + 1)
        }
    }
}
