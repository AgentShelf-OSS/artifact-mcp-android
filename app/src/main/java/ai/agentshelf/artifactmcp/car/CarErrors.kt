package ai.agentshelf.artifactmcp.car

import ai.agentshelf.artifactmcp.library.LibraryView
import androidx.media3.common.PlaybackException
import androidx.media3.session.SessionError

/**
 * Errors the car shows when it cannot start what was asked for: playback resumption (the play
 * button with nothing loaded, the "recent" card) and voice or typed search. Each kind has its own
 * [PlaybackException] code (set on the session as a playback exception, so the error state
 * stays visible until something plays) and a message string (resolved by the media service).
 */
object CarErrors {
    enum class Kind(val sessionCode: Int, val playbackCode: Int) {
        /** No server origin configured yet. */
        NOT_SET_UP(SessionError.ERROR_SESSION_SETUP_REQUIRED, PlaybackException.ERROR_CODE_SETUP_REQUIRED),

        /** The library says there is no Access session. */
        SIGNED_OUT(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED),

        /** Nothing in Continue to resume. */
        NOTHING_TO_RESUME(SessionError.ERROR_INVALID_STATE, PlaybackException.ERROR_CODE_INVALID_STATE),

        /** No network with internet access. */
        OFFLINE(SessionError.ERROR_IO, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),

        /** A search found nothing in the library. */
        NO_MATCH(SessionError.ERROR_BAD_VALUE, PlaybackException.ERROR_CODE_BAD_VALUE),

        /** A search had no library to look in (no copy and the server could not be reached). */
        LIBRARY_UNAVAILABLE(SessionError.ERROR_IO, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
    }

    /**
     * Whether playback can resume, or why not. [forPlayback] is false when the system only wants
     * the item's metadata (for example its resumption card after a reboot): that works offline.
     */
    fun resumption(originSet: Boolean, hasRecent: Boolean, library: LibraryView?, online: Boolean, forPlayback: Boolean): Kind? = when {
        !originSet -> Kind.NOT_SET_UP
        !hasRecent -> Kind.NOTHING_TO_RESUME
        !forPlayback -> null
        library == LibraryView.SignedOut -> Kind.SIGNED_OUT
        !online -> Kind.OFFLINE
        else -> null
    }

    /** Why a search started nothing: no session, no library, offline, or simply no match. */
    fun searchMiss(library: LibraryView?, online: Boolean): Kind = when (library) {
        LibraryView.SignedOut -> Kind.SIGNED_OUT
        LibraryView.Unavailable, null -> if (online) Kind.LIBRARY_UNAVAILABLE else Kind.OFFLINE
        is LibraryView.Ready -> Kind.NO_MATCH
    }
}
