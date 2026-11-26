package dev.droidtop.library

/**
 * A launch that did not start, for a reason written for the person ("set up Linux games first", "couldn't identify
 * which file to run"). The shell's failure dialog shows its [message] as it is, where every other exception stays in the log and reads "couldn't be started" (mytop-project/tracker#171). Only throw it with a
 * sentence that names something the person can act on; a raw exception's text never goes in it.
 */
class LaunchRefused(message: String) : IllegalStateException(message)
