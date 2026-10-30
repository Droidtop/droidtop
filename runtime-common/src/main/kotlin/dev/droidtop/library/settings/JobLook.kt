package dev.droidtop.library.settings

/**
 * How a job's progress looks, by where it stands (docs/SPEC.md "Motion and visual status", Droidtop/tracker#643; owner,
 * 2026-10-24: "how we do notifications and stuff is very text based. Can we move to more animations and intuitive
 * design?"): every surface that shows a job draws the same states the same way, so its state is seen, not read.
 */
enum class JobLook {
    /** Waiting its turn, for a connection or for the download rules: a dim bar with a slow pulse. */
    QUEUED,

    /** Moving, its fraction known: the fill glides to each new value. */
    ACTIVE,

    /** Under way with no fraction to show (checking, unpacking, a size not known yet): a shimmer along the track. */
    CHECKING,

    /** Stopped by the person: the fill stands, dimmed. */
    PAUSED,

    /** Finished: the fill completes and a check draws in. */
    DONE,

    /** Ended in failure: a short shake and the track in the danger colour; the reason is one press away. */
    FAILED,

    /**
     * Not a job: a quantity drawn as a meter (show, then say, Droidtop/tracker#738), such as a disk's used share on
     * the Storage page (Droidtop/tracker#363). The fill stands at the value with no motion or mark, and is said as
     * its percent.
     */
    LEVEL,
    ;

    companion object {
        /** The look a bare fraction implies: under way with no size is [CHECKING], else [ACTIVE]; null is no job. */
        fun of(progress: Float?): JobLook? = when {
            progress == null -> null
            progress < 0f -> CHECKING
            else -> ACTIVE
        }

        /** The most segments a bar is drawn in ([CatalogItem.progressParts]); a job in more parts is one plain bar. */
        const val MAX_PARTS = 32

        /**
         * What [look] is in words, for a screen reader and beside the drawing (show, then say: owner, 2026-10-29,
         * Droidtop/tracker#738); the bar or the mark is never the only way the state is told.
         */
        fun words(look: JobLook): String = when (look) {
            QUEUED -> "Waiting"
            ACTIVE -> "In progress"
            CHECKING -> "Working"
            PAUSED -> "Paused"
            DONE -> "Done"
            FAILED -> "Failed"
            LEVEL -> "Level"
        }
    }
}
