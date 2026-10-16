package dev.droidtop.app

import dev.droidtop.pluginhost.TileState
import dev.droidtop.pluginhost.WidgetTemplate

/**
 * What a Plugin widget draws (docs/plugin-api.md 3 C10 and C2, Droidtop/tracker#563): a plugin's `ui.widget` template
 * or tile state turned into the title and rows of droidtop's one widget renderer ([DroidtopWidgets.rows]). Pure, so
 * each template's mapping is tested. A row's [Line.action] is the id a tap on it sends back (`action {id}`); a quick
 * tile's row is a press ([Line.press]); a row with neither does nothing. [Content.action] is a tap on the whole widget.
 */
internal object PluginWidgetContent {
    class Line(val text: String, val action: String? = null, val press: Boolean = false)

    class Content(val title: String, val lines: List<Line>, val empty: String, val action: String? = null)

    /** A `ui.widget` snapshot's template; [label] names the source when the template has no title of its own. */
    fun of(template: WidgetTemplate, label: String): Content = when (template) {
        is WidgetTemplate.Value -> Content(
            template.title,
            listOf(Line(join(template.value, template.subtitle))),
            "",
            template.action,
        )
        is WidgetTemplate.ListOf -> Content(
            template.title ?: label,
            template.items.take(DroidtopWidgets.MAX_ROWS).map { item ->
                Line(join(join(item.title, item.value), item.subtitle), item.action)
            },
            template.empty ?: "Nothing to show",
            template.action,
        )
        is WidgetTemplate.Gauge -> Content(
            template.title,
            listOf(Line(join(if (template.percent < 0) "Working" else "${template.percent}%", template.label))),
            "",
            template.action,
        )
        is WidgetTemplate.Media -> Content(
            template.title,
            (listOfNotNull(template.subtitle?.let { Line(it) }) + template.buttons.map { Line(it.label, it.id) })
                .take(DroidtopWidgets.MAX_ROWS),
            "",
            template.action,
        )
        is WidgetTemplate.Picture -> Content(template.title ?: label, emptyList(), "", template.action)
    }

    /** A C2 or C3 tile: its label and value as one row; a quick tile's row is its press, a status tile's is not. */
    fun tile(label: String, state: TileState?, quick: Boolean): Content {
        val value = when {
            state == null -> null
            state.value != null -> state.value
            state.on == true -> "On"
            state.on == false -> "Off"
            else -> null
        }
        val row = Line(join(state?.label ?: label, value), press = quick)
        return Content(label, listOf(row), "")
    }

    private fun join(first: String, second: String?): String = if (second.isNullOrBlank()) first else "$first · $second"
}
