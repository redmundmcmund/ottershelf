package io.github.ottershelf.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.ottershelf.AppContainer
import io.github.ottershelf.DefaultPasswordException
import io.github.ottershelf.core.network.ApiException
import java.io.IOException
import javax.net.ssl.SSLException

data class LoginUiState(
    /** Blank until the user types it (no server is built into the app); the stored one after a sign-in. */
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: LoginError? = null,
)

sealed interface LoginError {
    data object Missing : LoginError
    data object Insecure : LoginError
    data object BadAddress : LoginError
    data object WrongPassword : LoginError
    data object DefaultPassword : LoginError
    data class Failed(val detail: String) : LoginError
    data class Tls(val detail: String) : LoginError
    data class Unreachable(val detail: String) : LoginError
    data class Unexpected(val detail: String) : LoginError
}

/**
 * The Nexus LoginActivity's sign-in: a missing scheme becomes https://, anything that is not https
 * (http://: cleartext is disabled) is refused before a request is made, the address is tidied
 * ([ServerAddress]: the stored spelling is kept for the same server), then AppContainer.signIn
 * (Api.login, the default-password check, AuthState) with the Nexus error messages. The user types
 * their own password; it lives only in this state until the screen goes away. Success needs no
 * navigation: AuthState flips and the shell replaces Login with the Dashboard.
 */
class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(
            server = container.session.serverUrl.orEmpty(),
            username = container.session.username.orEmpty(),
        ),
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onServerChange(value: String) = _state.update { it.copy(server = value, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun signIn() {
        val input = _state.value
        if (input.busy) return
        var typed = input.server.trim().trimEnd('/')
        if (typed.isNotEmpty() && "://" !in typed) typed = "https://$typed"
        val username = input.username.trim()
        when {
            typed.isEmpty() || username.isEmpty() || input.password.isEmpty() -> return fail(LoginError.Missing)
            !typed.startsWith("https://", ignoreCase = true) -> return fail(LoginError.Insecure)
        }
        val server = ServerAddress.resolve(typed, stored = container.session.serverUrl)
            ?: return fail(LoginError.BadAddress)
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                container.signIn(server, username, input.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DefaultPasswordException) {
                fail(LoginError.DefaultPassword)
            } catch (e: ApiException) {
                fail(if (e.code == 401) LoginError.WrongPassword else LoginError.Failed(e.message.orEmpty()))
            } catch (e: SSLException) {
                fail(LoginError.Tls(e.message.orEmpty()))
            } catch (e: IOException) {
                fail(LoginError.Unreachable(e.message.orEmpty()))
            } catch (e: Exception) {
                fail(LoginError.Unexpected(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun fail(error: LoginError) = _state.update { it.copy(busy = false, error = error) }
}
