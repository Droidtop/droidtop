package dev.droidtop.ui.shared

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * droidtop's motion tokens (docs/SPEC.md "Gaming motion and focus"), the
 * sibling of [Space] and [TypeRole] in DesignTokens.kt: every animation the
 * shell's own chrome plays takes its duration and its curve from here by
 * ROLE, so the screens feel like one product and retuning the feel is an
 * edit in this file. A themed ES-DE view is out of scope: it plays the
 * theme's own transitions.
 *
 * The durations and curves are Steam Big Picture's, measured from its
 * gamepad UI and restated here as droidtop's own roles (credit to its
 * designers, who did the patient work; nothing of theirs is copied): colour
 * answers "where am I" at once while shape glides, a few curves do most of
 * the work, a held direction glides instead of stepping, and letting go of
 * focus is slower than taking it, which reads as weight. The motion switch
 * and its snap rule, the staggered entry and the sheen follow DroidDeck's
 * front end (ui/FrontEndScreen.kt at 9310d19, GPL-3.0, see NOTICE.md),
 * reworked onto these roles.
 *
 * **Reduced motion is one switch.** [enabled] is false when the person turns
 * Gaming's Animations setting off OR Android's animator duration scale is 0
 * ("Remove animations"); every spec built here is then a snap, so nothing the
 * chrome draws moves on its own. Compose already stretches its own animation
 * clock by a non-zero animator scale, so durations here are unscaled; only a
 * wall-clock wait (a coroutine delay) goes through [ms]. [MotionWatch] keeps
 * Android's scale current for every host in the process, and Gaming's
 * MotionSync adds its own switch. It lives in `:ui-shared` so every surface
 * that shows a job, the companion and Standard's settings among them, moves
 * by the same roles (Droidtop/tracker#643).
 */
object Motion {
    /** A focus colour or fill change: instant, so the eye gets the answer first. */
    const val ColourMs = 0

    /** A row or menu item taking focus: its scale glides while its fill has already changed. */
    const val FocusMs = 320

    /** Focus taking a capsule: its lift, shadow and brightness glide in. */
    const val LiftMs = 300

    /** Focus leaving it: twice as slow as taking it, so the item settles back. */
    const val ReleaseMs = 600

    /** A carousel or grid moving the focused item to the centre. */
    const val CarouselCentreMs = 120

    /** A side or bottom panel sliding in; out is shorter, the user is already done with it. */
    const val PanelInMs = 300
    const val PanelOutMs = 180

    /**
     * One shell screen replacing another (a crossfade). ES-DE's own
     * inter-view fade is 500 ms at its slowest and 160 ms at its fastest
     * (ViewController.cpp); the shell's own screens take the short end: long
     * enough not to be a cut, short enough that B pressed twice never waits.
     */
    const val ScreenMs = 160

    /** A side-menu row taking the cursor: its plate flashes bright and settles back over this long. */
    const val FlashMs = 500

    /** The left menu's live dot for the running game: one pulse of its ring. */
    const val LivePulseMs = 1600

    /** A press acknowledging itself: nearly instant on purpose. */
    const val PressMs = 50

    /** The art behind a page changing with the cursor. */
    const val BackdropMs = 500

    /** Ambient, never demanding: a waiting pulse, and the art changing on an idle screen. */
    const val AmbientPulseMs = 900
    const val AmbientFadeMs = 1200

    /**
     * A page's blocks fading in on entry: each block fades up from [RiseDp]
     * below over [RiseMs], the next one [RiseStaggerMs] later, so the page
     * assembles top to bottom instead of popping in. Capped at
     * [RiseMaxSteps] steps, so a long page does not keep its last rows waiting.
     */
    const val RiseMs = 500
    const val RiseStaggerMs = 60
    const val RiseMaxSteps = 6
    const val RiseDp = 8f

    /**
     * A job's bar (JobProgress, Droidtop/tracker#643): the fill glides to each new value over [ProgressMs], longer than
     * the gap between two reports (a quarter second at most, ProgressThrottle) so it never stops between them; a
     * checking shimmer crosses the track every [ShimmerMs]; a finished job's check draws in over [DoneMs]; a failed one
     * shakes once over [ShakeMs]. A queued job pulses on [AmbientPulseMs].
     */
    const val ProgressMs = 600
    const val ShimmerMs = 1400
    const val DoneMs = 400
    const val ShakeMs = 360

    /** A one-shot sheen across the capsule that just took focus; the Play button's stripe is slower. */
    const val ShineMs = 1000
    const val PlayShineMs = 2000

    /**
     * The focus ring (FocusGlide.kt). Landing: when it appears it starts
     * [RingLandFromDp] outside its control and closes onto it over
     * [RingLandMs], and then stays at full strength (two opaque edges,
     * FocusRingInks: a ring that fades is a ring a low-vision eye loses).
     * Gliding: after a move it keeps re-measuring where its control is for
     * [GlideSettleMs] (layer animations move a control without a relayout);
     * two moves closer together than [GlideRepeatMs] are a held direction
     * (it runs as one piece, no stretch); a move to a control more than
     * [GlideFarDp] away, edge to edge, travels as a droplet; the trailing edge waits [GlideTrailDelayMs].
     */
    const val RingLandMs = 400
    const val RingLandFromDp = 12f
    const val RingFadeMs = 140
    const val GlideSettleMs = 450
    const val GlideRepeatMs = 180
    const val GlideFarDp = 360f
    const val GlideTrailDelayMs = 30

    /** Quick out, gentle settle: focus on rows and menu items, the backdrop, the first press of a scroll. */
    val QuickOut: Easing = CubicBezierEasing(0.17f, 0.45f, 0.14f, 0.83f)

    /** A touch softer at the end: a capsule's lift, panels, the ring landing, the sheen. */
    val Glide: Easing = CubicBezierEasing(0.16f, 0.86f, 0.43f, 0.99f)

    /** A lazy settle: a capsule letting go of focus. */
    val SoftLand: Easing = CubicBezierEasing(0f, 0.73f, 0.48f, 1f)

    /** Slow start, quick leave: something going away. */
    val Exit: Easing = CubicBezierEasing(0.6f, 0f, 1f, 1f)

    /**
     * Whether the chrome animates at all. Snapshot state, so a change of the
     * setting reaches every spec built after it.
     */
    var enabled: Boolean by mutableStateOf(true)
        private set

    /**
     * Android's animator duration scale, for the one thing Compose does not
     * scale itself: a wall-clock wait between two animations ([ms]).
     */
    private var animatorScale: Float by mutableStateOf(1f)

    /** The person's own switch (Gaming's Animations), on until a shell that has one says otherwise. */
    private var appSwitchOn: Boolean = true

    /** Android's animator duration scale as last read; kept by [MotionWatch] for the whole process. */
    private var systemScale: Float = 1f

    /** The switch's two inputs; the decision is [isOn]. Called by Gaming's MotionSync. */
    fun update(appSwitchOn: Boolean, animatorDurationScale: Float) {
        this.appSwitchOn = appSwitchOn
        updateScale(animatorDurationScale)
    }

    /** Android's animator duration scale alone, for every host ([MotionWatch]). */
    fun updateScale(animatorDurationScale: Float) {
        systemScale = animatorDurationScale
        enabled = isOn(appSwitchOn, systemScale)
        animatorScale = if (enabled) systemScale else 0f
    }

    /** Motion runs only when the person's switch is on and Android has not removed animations. Pure. */
    fun isOn(appSwitchOn: Boolean, animatorDurationScale: Float): Boolean =
        appSwitchOn && animatorDurationScale > 0f

    /** A wall-clock wait scaled as the animations around it are; 0 when motion is off. */
    fun ms(base: Int): Long = (base * animatorScale).roundToInt().toLong()

    /** A timed role: [ms] long on [easing], or a snap when motion is off. */
    fun <T> tw(ms: Int, delay: Int = 0, easing: Easing = QuickOut): FiniteAnimationSpec<T> =
        if (!enabled) snap() else tween(ms, delay, easing)

    /** A physical role (the ring's edges): a spring, or a snap when motion is off. */
    fun <T> sp(damping: Float, stiffness: Float): FiniteAnimationSpec<T> =
        if (!enabled) snap() else spring(damping, stiffness)

    fun <T> focus(): FiniteAnimationSpec<T> = tw(FocusMs, easing = QuickOut)
    fun <T> progress(): FiniteAnimationSpec<T> = tw(ProgressMs, easing = Glide)
    fun <T> flash(): FiniteAnimationSpec<T> = tw(FlashMs, easing = QuickOut)
    fun <T> lift(): FiniteAnimationSpec<T> = tw(LiftMs, easing = Glide)
    fun <T> release(): FiniteAnimationSpec<T> = tw(ReleaseMs, easing = SoftLand)
    fun <T> panelIn(): FiniteAnimationSpec<T> = tw(PanelInMs, easing = Glide)
    fun <T> panelOut(): FiniteAnimationSpec<T> = tw(PanelOutMs, easing = Exit)
    fun <T> screen(): FiniteAnimationSpec<T> = tw(ScreenMs)
    fun <T> backdrop(): FiniteAnimationSpec<T> = tw(BackdropMs, easing = QuickOut)
    fun <T> ambientFade(): FiniteAnimationSpec<T> = tw(AmbientFadeMs)
    fun <T> shine(play: Boolean = false): FiniteAnimationSpec<T> =
        tw(if (play) PlayShineMs else ShineMs, easing = Glide)

    /** Block [index] of a page fading in: [RiseMs] on [QuickOut], delayed by its place. */
    fun <T> rise(index: Int): FiniteAnimationSpec<T> = tw(RiseMs, delay = riseDelay(index), easing = QuickOut)

    /** How long block [index] waits before it rises: stepped, and capped. Pure. */
    fun riseDelay(index: Int): Int = index.coerceIn(0, RiseMaxSteps) * RiseStaggerMs

    /**
     * A scroll that centres the focused item. The first press eases in and
     * out; a press that arrives while the last is still moving, or that is a
     * held direction repeating, replaces it with a linear one, so holding a
     * direction is one smooth glide instead of a run of small stops.
     */
    fun scroll(chained: Boolean): FiniteAnimationSpec<Float> =
        tw(CarouselCentreMs, easing = if (chained) LinearEasing else QuickOut)
}
