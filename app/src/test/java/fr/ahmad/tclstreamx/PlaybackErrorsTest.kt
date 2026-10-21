package fr.ahmad.tclstreamx

import androidx.media3.common.PlaybackException
import fr.ahmad.tclstreamx.player.PlaybackErrors
import fr.ahmad.tclstreamx.player.PlaybackIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorsTest {
    @Test
    fun httpStatusesAreClassified() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(PlaybackIssue.FORBIDDEN, PlaybackErrors.classify(bad, 403))
        assertEquals(PlaybackIssue.AUTH, PlaybackErrors.classify(bad, 401))
        assertEquals(PlaybackIssue.NOT_FOUND, PlaybackErrors.classify(bad, 404))
        assertEquals(PlaybackIssue.SERVER, PlaybackErrors.classify(bad, 503))
    }

    @Test
    fun drmAndDecoderAreNotRetried() {
        val drm = PlaybackErrors.classify(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, null)
        assertEquals(PlaybackIssue.DRM, drm)
        assertFalse(drm.retryable)
        val dec = PlaybackErrors.classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, null)
        assertEquals(PlaybackIssue.DECODER, dec)
        assertFalse(dec.retryable)
    }

    @Test
    fun networkErrorsAreRetryable() {
        assertTrue(PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null).retryable)
        assertEquals(PlaybackIssue.TIMEOUT, PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(PlaybackIssue.FORMAT, PlaybackErrors.classify(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, null))
    }
}
