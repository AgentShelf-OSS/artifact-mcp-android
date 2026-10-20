package ai.agentshelf.artifactmcp.listen

import android.webkit.WebViewClient
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderFailuresTest {
    @Test fun `network errors of the page mean offline`() {
        for (code in listOf(WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO)) {
            assertThat(ReaderFailures.forNetworkError(code)).isEqualTo(MirrorState.Failure.OFFLINE)
        }
        assertThat(ReaderFailures.forNetworkError(WebViewClient.ERROR_AUTHENTICATION)).isEqualTo(MirrorState.Failure.SIGN_IN)
        assertThat(ReaderFailures.forNetworkError(WebViewClient.ERROR_FILE_NOT_FOUND)).isEqualTo(MirrorState.Failure.NOT_FOUND)
        assertThat(ReaderFailures.forNetworkError(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE)).isEqualTo(MirrorState.Failure.SERVER)
    }

    @Test fun `http statuses map to sign in, not found and server errors`() {
        assertThat(ReaderFailures.forHttpStatus(200)).isNull()
        assertThat(ReaderFailures.forHttpStatus(302)).isNull()
        assertThat(ReaderFailures.forHttpStatus(401)).isEqualTo(MirrorState.Failure.SIGN_IN)
        assertThat(ReaderFailures.forHttpStatus(403)).isEqualTo(MirrorState.Failure.SIGN_IN)
        assertThat(ReaderFailures.forHttpStatus(404)).isEqualTo(MirrorState.Failure.NOT_FOUND)
        assertThat(ReaderFailures.forHttpStatus(410)).isEqualTo(MirrorState.Failure.NOT_FOUND)
        assertThat(ReaderFailures.forHttpStatus(429)).isEqualTo(MirrorState.Failure.SERVER)
        assertThat(ReaderFailures.forHttpStatus(500)).isEqualTo(MirrorState.Failure.SERVER)
        assertThat(ReaderFailures.forHttpStatus(503)).isEqualTo(MirrorState.Failure.SERVER)
    }
}
