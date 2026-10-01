package dev.droidtop.runtime.util

/** Loose version ordering shared by library versions and image tags. */
object Versions {
    /** Compares numeric prefixes and then text in dot, underscore, or dash separated components. */
    fun compareLoose(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return a.length.compareTo(b.length)
        val left = a.split('.', '_', '-')
        val right = b.split('.', '_', '-')
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(index) { "0" }
            val r = right.getOrElse(index) { "0" }
            val ln = l.takeWhile { it.isDigit() }
            val rn = r.takeWhile { it.isDigit() }
            val byNumber = (ln.toLongOrNull() ?: 0L).compareTo(rn.toLongOrNull() ?: 0L)
            if (byNumber != 0) return byNumber
            val byText = l.dropWhile { it.isDigit() }.compareTo(r.dropWhile { it.isDigit() })
            if (byText != 0) return byText
        }
        return 0
    }
}
