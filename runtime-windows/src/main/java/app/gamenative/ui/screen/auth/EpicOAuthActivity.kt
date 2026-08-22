package app.gamenative.ui.screen.auth

import android.app.Activity

/**
 * Minimal, real compatibility shim for the real thing forked-in
 * `com.winlator.xenvironment.components.WineRequestComponent` actually uses
 * on `app.gamenative.ui.screen.auth.EpicOAuthActivity` (confirmed via
 * reading its real usage this session): the two real companion constants
 * (`EPIC_AUTH_URL_PREFIX`, `EXTRA_GAME_AUTH_URL`) and the class itself as an
 * `Intent` target (`intent.setClass(context, EpicOAuthActivity.class)`).
 * Upstream's real activity opens a `WebView` to complete Epic's OAuth code
 * flow and posts an `EpicAuthCodeReceived` event back through gamenative's
 * own event bus/service layer -- not forked in, since droidtop has no Epic
 * account integration yet. An empty `ComponentActivity` satisfies the real
 * `Intent`-target usage honestly: launching it currently opens a blank
 * screen rather than lying about a working Epic sign-in flow.
 */
class EpicOAuthActivity : Activity() {
    companion object {
        const val EXTRA_GAME_AUTH_URL = "game_auth_url"
        const val EPIC_AUTH_URL_PREFIX = "https://epicgames.com"
    }
}
