package com.maxrave.domain.data.model.update

/** A Segue update must be a published stable release with the expected APK asset. */
fun segueReleaseDownloadUrl(
    tagName: String?,
    draft: Boolean?,
    prerelease: Boolean?,
    assets: List<Pair<String?, String?>>,
): String? {
    val tag = tagName ?: return null
    if (!Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag) || draft != false || prerelease != false) return null
    val expected = "https://github.com/augustof2022/mYTmusic/releases/download/$tag/Segue-Android.apk"
    return assets.firstOrNull { (name, url) -> name == "Segue-Android.apk" && url == expected }?.second
}
