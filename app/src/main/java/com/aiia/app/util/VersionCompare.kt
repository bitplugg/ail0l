package com.aiia.app.util

/**
 * Semver-ish comparison for release tags, kept free of Android APIs so it can be unit tested.
 */
object VersionCompare {
    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        val latest = components(latestTag)
        val current = components(currentVersion)
        if (latest.isEmpty() || current.isEmpty()) return false
        for (i in 0 until maxOf(latest.size, current.size)) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun components(version: String): List<Int> = version
        .trim()
        .trimStart('v', 'V')
        .substringBefore('+')
        .split('.', '-', '_')
        .mapNotNull { it.toIntOrNull() }
}
