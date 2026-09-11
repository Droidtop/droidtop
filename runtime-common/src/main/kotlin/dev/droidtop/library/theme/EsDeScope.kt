package dev.droidtop.library.theme

/**
 * Real `scope`, the property that decides WHERE one of the three
 * window-level elements is drawn: `helpsystem`, `clock` and
 * `systemstatus`. Those three are the only element types that carry it,
 * because they are the only ones real ES-DE renders from the Window rather
 * than from a view (Window.cpp:730-754 for clock and systemstatus,
 * :954-958 for the help bar).
 *
 * The four real values and ES-DE's own gate, which is identical in all
 * three of those blocks:
 * - `shared` (the real default, GuiComponent.cpp:34) -- drawn both in a
 *   view and while a menu is open on top of it.
 * - `view` -- skipped while a menu is open.
 * - `menu` -- skipped while no menu is open.
 * - `none` -- never drawn at all.
 *
 * An unrecognized value logs a warning and leaves the current value alone
 * (HelpComponent.cpp:224-231, DateTimeComponent.cpp:213-226,
 * SystemStatusComponent.cpp:196-209), which for a theme-loaded component
 * is `shared` -- so an invalid value draws, same as here.
 *
 * [menuOpen] is real ES-DE's own "the GUI stack has more than one entry"
 * (Window.cpp:513-516), which in droidtop is the same state that dims the
 * help bar: one of its own in-context menus is a Compose Dialog over the
 * themed view.
 */
fun esDeScopeAllows(element: EsDeThemeElement, menuOpen: Boolean): Boolean =
    when (element.strOrNull("scope")) {
        "none" -> false
        "view" -> !menuOpen
        "menu" -> menuOpen
        else -> true
    }
