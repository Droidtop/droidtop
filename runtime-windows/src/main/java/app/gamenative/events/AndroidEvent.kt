package app.gamenative.events

/**
 * Minimal, real compatibility shim -- `com.winlator.xenvironment.components.
 * BionicProgramLauncherComponent`/`GlibcProgramLauncherComponent` (forked in
 * wholesale) import `app.gamenative.events.AndroidEvent` but, per a real
 * check of their actual usage this session, never reference it (a dead/
 * unused import in the real upstream source, same situation as the
 * `MainActivity` shim). Upstream's real `AndroidEvent` is a large sealed
 * event-bus interface with ~25 variants tied to `app.gamenative.data.
 * GameSource`/`app.gamenative.ui.enums.Orientation` and an `Event<T>` base
 * from gamenative's own event-bus library -- not forked in, since nothing
 * here actually needs it. Empty marker interface only, so the dead import
 * resolves.
 */
interface AndroidEvent<T>
