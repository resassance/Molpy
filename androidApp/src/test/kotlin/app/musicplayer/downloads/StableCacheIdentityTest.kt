package app.musicplayer.downloads

import app.musicplayer.model.AssetId
import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.*
import org.junit.Test

class StableCacheIdentityTest {
    @Test fun stableIdentityEncodesEveryPathDelimiterAndRoundTripsUnicode() {
        val id = AssetId("asset/a ?#雪")
        val uri = StableCacheIdentity.uri(id)
        assertEquals("musicplayer-download://asset/asset%2Fa%20%3F%23%E9%9B%AA", uri)
        assertEquals(id, StableCacheIdentity.assetId(uri))
    }
    @Test fun runtimeUrlsAndNoncanonicalAliasesCannotEnterTheStableBoundary() {
        for (uri in listOf("https://fixture.invalid/audio?signature=secret", "musicplayer-download://asset/id?signature=secret",
            "musicplayer-download://other/id", "musicplayer-download://asset/a/b", "musicplayer-download://asset/%61")) {
            assertThrows(IllegalArgumentException::class.java) { StableCacheIdentity.assetId(uri) }
        }
    }
    @Test fun transportCompletionCannotPublishAvailable() {
        assertEquals(DownloadState.FAILED, DownloadProjection.state(Download.STATE_COMPLETED))
        assertEquals(DownloadState.FAILED, DownloadProjection.state(Download.STATE_FAILED))
        assertEquals(DownloadState.PAUSED, DownloadProjection.state(Download.STATE_STOPPED))
        assertEquals(DownloadState.DOWNLOADING, DownloadProjection.state(Download.STATE_DOWNLOADING))
        assertEquals(DownloadState.QUEUED, DownloadProjection.state(Download.STATE_QUEUED))
    }
}
