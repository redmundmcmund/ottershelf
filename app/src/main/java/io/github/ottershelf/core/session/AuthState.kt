package io.github.ottershelf.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.ottershelf.core.model.AuthUser
import io.github.ottershelf.core.network.ApiJson

/**
 * Whether someone is signed in, and who, for the whole app. The navigation shell shows Login while
 * [status] is [Status.SignedOut] and the signed-in screens (scoped to [Status.SignedIn.accountKey])
 * otherwise, so signing out, or the server rejecting the refresh token, sends the app to Login.
 *
 * Changed only by the AppContainer's signIn/signOut and by the Api when a refresh is rejected;
 * screens read it.
 */
class AuthState(private val session: Session) {

    sealed interface Status {
        data object SignedOut : Status
        data class SignedIn(val accountKey: String) : Status
    }

    private val _status = MutableStateFlow(current())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _user = MutableStateFlow(readUser())
    /** The signed-in user as last seen, kept so permissions are known offline. */
    val user: StateFlow<AuthUser?> = _user.asStateFlow()

    private fun current(): Status =
        if (session.isSignedIn) Status.SignedIn(session.accountKey()) else Status.SignedOut

    private fun readUser(): AuthUser? =
        session.userJson?.let { runCatching { ApiJson.decodeFromString(AuthUser.serializer(), it) }.getOrNull() }

    /** After a successful login (the Api has stored the credentials). */
    fun setSignedIn(user: AuthUser?) {
        if (user != null) setUser(user)
        _status.value = current()
    }

    /** Any thread: the credentials are gone (signed out here, or the refresh token was rejected). */
    fun setSignedOut() {
        _status.value = Status.SignedOut
    }

    fun setUser(user: AuthUser) {
        session.userJson = ApiJson.encodeToString(AuthUser.serializer(), user)
        _user.value = user
    }

    companion object {
        /** The permission that shows the Requests tab (as the Nexus app and the web decide). */
        const val BOOK_REQUEST_ACCESS = "book_request_access"

        /** The permission that allows downloading a book's file (unknown user: allowed, as on the Nexus). */
        const val LIBRARY_DOWNLOAD = "library_download"
    }
}
