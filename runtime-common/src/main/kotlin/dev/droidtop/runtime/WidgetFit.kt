package dev.droidtop.runtime

/**
 * Whether a widget can go on Standard's home screen at all, for the widget picker (Droidtop/tracker#563; rig,
 * build 2011: com.android.calendar's widget, declaring a size no grid holds, showed as "Calendar 0×0"). A widget
 * that can be resized down may take its resize minimum; one that cannot needs its own size. Either must have been
 * measured (a span of 0 says no grid profile measured it) and fit inside the grid. Pure.
 */
object WidgetFit {
    /** The smallest span a widget takes in one direction: its resize minimum when it resizes that way, else its size. */
    fun smallest(span: Int, minResizeSpan: Int, resizes: Boolean): Int = if (resizes) minOf(span, minResizeSpan) else span

    /** True when a widget whose smallest spans are [x] by [y] has a place on a [columns] by [rows] grid. */
    fun fits(x: Int, y: Int, columns: Int, rows: Int): Boolean = x in 1..columns && y in 1..rows
}
