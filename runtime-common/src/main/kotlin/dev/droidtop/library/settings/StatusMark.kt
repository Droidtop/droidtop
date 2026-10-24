package dev.droidtop.library.settings

/**
 * One status glyph from the host's set, beside a row's name (Droidtop/tracker#644, docs/DESIGN-LANGUAGE.md "One
 * component set": a status is a glyph, quiet when good). A row that is fine carries none.
 */
enum class StatusMark {
    /** Something is under way for it: a ring that turns. */
    WORKING,

    /** It waits for the person: an approval, a setup, new access, an update. */
    ATTENTION,

    /** It cannot run: stopped, denied, turned off by a crash. */
    ERROR,
}
