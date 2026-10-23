package dev.droidtop.library

/**
 * Finding the mirror a person picked on a record page again in a fresh copy of the record (docs/SPEC.md 7g, "Getting an
 * update"; Droidtop/tracker#599). A picked mirror with no `url` is a token only the source understands, and a token
 * from a saved record goes stale when the source's page changes (a new version moves the mirrors), so droidtop asks for
 * the record again and opens the same mirror from that. Pure.
 */
object RecordMirrors {
    /**
     * [picked], one of [pickedFrom], in [fresh]: the mirror with the same section, host and label. When several share
     * them, the one with [picked]'s link if it is still there, else the one at the same place among them. Null when the
     * fresh record no longer lists it.
     */
    fun sameIn(picked: UpdateSources.Download, pickedFrom: List<UpdateSources.Download>, fresh: List<UpdateSources.Download>): UpdateSources.Download? {
        fun alike(d: UpdateSources.Download) = d.section == picked.section && d.host == picked.host && d.label == picked.label
        val candidates = fresh.filter(::alike)
        if (candidates.size <= 1) return candidates.firstOrNull()
        candidates.firstOrNull { it.link.isNotEmpty() && it.link == picked.link }?.let { return it }
        val place = pickedFrom.filter(::alike).indexOf(picked)
        return candidates.getOrNull(place)
    }
}
