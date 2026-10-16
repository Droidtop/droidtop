package dev.droidtop.runtime.windows

import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import dev.droidtop.library.OwnGameScreens

/**
 * The game's pointer for droidtop's Windows screen ([OwnGameScreens.Pointer], Droidtop/tracker#565): the calls the
 * screen's touch trackpad already makes on [xServer] (`injectPointerMoveDelta`, `injectPointerButtonPress` and
 * `Release`, the wheel as scroll-button presses), so controller mouse mode and the companion's trackpad share one
 * pointer path. Main thread.
 */
internal class XServerPointer(
    private val xServer: XServer,
    private val display: () -> Int?,
) : OwnGameScreens.Pointer {
    override val displayId: Int? get() = display()

    override val widthPx: Int get() = xServer.screenInfo.width.toInt()

    override fun move(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) xServer.injectPointerMoveDelta(dx, dy)
    }

    override fun button(button: OwnGameScreens.PointerButton, pressed: Boolean) {
        val x = when (button) {
            OwnGameScreens.PointerButton.LEFT -> Pointer.Button.BUTTON_LEFT
            OwnGameScreens.PointerButton.RIGHT -> Pointer.Button.BUTTON_RIGHT
            OwnGameScreens.PointerButton.MIDDLE -> Pointer.Button.BUTTON_MIDDLE
        }
        if (pressed) xServer.injectPointerButtonPress(x) else xServer.injectPointerButtonRelease(x)
    }

    override fun wheel(down: Boolean) {
        val x = if (down) Pointer.Button.BUTTON_SCROLL_DOWN else Pointer.Button.BUTTON_SCROLL_UP
        xServer.injectPointerButtonPress(x)
        xServer.injectPointerButtonRelease(x)
    }
}
