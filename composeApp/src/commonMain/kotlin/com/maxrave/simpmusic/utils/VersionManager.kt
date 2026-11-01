package com.maxrave.simpmusic.utils

import com.maxrave.simpmusic.BuildKonfig

object VersionManager {
    private var versionName: String? = null

    fun initialize() {
        if (versionName == null) {
            versionName =
                try {
                    BuildKonfig.versionName
                } catch (_: Exception) {
                    String()
                }
        }
    }

    fun getVersionName(): String = removeDevSuffix(versionName ?: String())

    /** True when [tag] names a release newer than this build. */
    fun isBehind(tag: String): Boolean = isNewerRelease(current = getVersionName(), tag = tag)

    private fun removeDevSuffix(versionName: String): String {
        return if (versionName.endsWith("-dev")) {
            versionName.replace("-dev", "")
        } else {
            versionName
        }
    }
}

/**
 * True only when release [tag] has a higher dotted version than this build.
 */
internal fun isNewerRelease(
    current: String,
    tag: String,
): Boolean {
    val release = tag.trim().removePrefix("v")
    return compareVersions(current, release) < 0
}

/** Dotted versions compared number by number, so "2.10.0" is newer than "2.9.0". */
internal fun compareVersions(
    a: String,
    b: String,
): Int {
    val x = versionNumbers(a)
    val y = versionNumbers(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

private fun versionNumbers(version: String): List<Int> = version.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }

