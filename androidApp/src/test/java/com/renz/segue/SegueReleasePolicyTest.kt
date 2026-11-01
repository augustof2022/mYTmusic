package com.renz.segue

import com.maxrave.domain.data.model.update.segueReleaseDownloadUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegueReleasePolicyTest {
    private val tag = "v2.5.0"
    private val good = "https://github.com/augustof2022/mYTmusic/releases/download/v2.5.0/Segue-Android.apk"

    @Test fun acceptsPublishedSegueApk() {
        assertEquals(good, segueReleaseDownloadUrl(tag, false, false, listOf("Segue-Android.apk" to good)))
    }

    @Test fun rejectsMissingApk() {
        assertNull(segueReleaseDownloadUrl(tag, false, false, emptyList()))
    }

    @Test fun rejectsWrongFile() {
        assertNull(segueReleaseDownloadUrl(tag, false, false, listOf("source.zip" to good)))
    }

    @Test fun rejectsWrongRepositoryAndWrongTag() {
        assertNull(segueReleaseDownloadUrl(tag, false, false, listOf("Segue-Android.apk" to good.replace("augustof2022", "someone"))))
        assertNull(segueReleaseDownloadUrl(tag, false, false, listOf("Segue-Android.apk" to good.replace("v2.5.0", "v2.4.4"))))
    }

    @Test fun rejectsDraftsAndPrereleases() {
        val asset = listOf("Segue-Android.apk" to good)
        assertNull(segueReleaseDownloadUrl(tag, true, false, asset))
        assertNull(segueReleaseDownloadUrl(tag, false, true, asset))
        assertNull(segueReleaseDownloadUrl(tag, null, false, asset))
        assertNull(segueReleaseDownloadUrl(tag, false, null, asset))
    }

    @Test fun rejectsUnrecognizedTags() {
        val asset = listOf("Segue-Android.apk" to good)
        for (bad in listOf(null, "", "2.5.0", "v2.5.0-beta", "v2.5.0/other")) {
            assertNull(segueReleaseDownloadUrl(bad, false, false, asset))
        }
    }
}
