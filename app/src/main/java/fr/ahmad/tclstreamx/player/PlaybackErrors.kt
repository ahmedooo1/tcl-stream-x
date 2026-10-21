package fr.ahmad.tclstreamx.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

object PlaybackErrors {

    fun classify(error: PlaybackException): PlaybackIssue {
        var cause: Throwable? = error.cause
        var http: Int? = null
        while (cause != null && http == null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) http = cause.responseCode
            cause = cause.cause
        }
        return classify(error.errorCode, http)
    }

    /** Pure mapping, unit tested. */
    fun classify(errorCode: Int, httpStatus: Int?): PlaybackIssue = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpStatus) {
            401, 407 -> PlaybackIssue.AUTH
            403, 451 -> PlaybackIssue.FORBIDDEN
            404, 410 -> PlaybackIssue.NOT_FOUND
            in 500..599 -> PlaybackIssue.SERVER
            else -> PlaybackIssue.NETWORK
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> PlaybackIssue.NETWORK
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackIssue.TIMEOUT
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackIssue.NOT_FOUND
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackIssue.FORBIDDEN
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackIssue.FORMAT
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackIssue.DECODER
        in DRM_RANGE -> PlaybackIssue.DRM
        else -> PlaybackIssue.UNKNOWN
    }

    private val DRM_RANGE = 6000..6999

    /** Extension-less URLs that turn out to be HLS fail with this code first: retry as HLS. */
    fun shouldRetryAsHls(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
}
