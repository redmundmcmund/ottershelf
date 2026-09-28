package io.github.ottershelf.feature.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ottershelf.R
import io.github.ottershelf.ui.icons.LucideIcon
import io.github.ottershelf.ui.nav.appViewModel
import io.github.ottershelf.ui.theme.OttershelfTheme
import io.github.ottershelf.ui.theme.PatternBackground
import io.github.ottershelf.ui.theme.atAlpha

/**
 * Sign-in (the Nexus LoginActivity). Success needs no navigation: AuthState flips and the shell
 * replaces Login with the Dashboard; when the server rejects the session the shell comes back here.
 */
@Composable
fun LoginScreen() {
    val viewModel = appViewModel { LoginViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginContent(
        state = state,
        onServerChange = viewModel::onServerChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSignIn = viewModel::signIn,
    )
}

/**
 * activity_login.xml: a centred column at most 420dp wide with 24dp padding, scrolling when the
 * keyboard leaves too little room. The launcher icon and app name become the web login's brand:
 * the Ottershelf badge and the "Ottershelf" wordmark with "shelf" in the accent. Then
 * the three fields 8dp apart, the error under them, the 52dp accent Sign in button, and a spinner
 * while signing in. The page is the user's background pattern, as on the web.
 */
@Composable
fun LoginContent(
    state: LoginUiState,
    onServerChange: (String) -> Unit = {},
    onUsernameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onSignIn: () -> Unit = {},
) {
    val colors = OttershelfTheme.colors
    val focus = LocalFocusManager.current
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val submit = {
        focus.clearFocus()
        onSignIn()
    }
    val error = state.error
    val missing = error == LoginError.Missing

    PatternBackground(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painterResource(R.drawable.ottershelf_badge),
                        contentDescription = null,
                        modifier = Modifier.size(104.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        buildAnnotatedString {
                            append(stringResource(R.string.login_wordmark_otter))
                            withStyle(SpanStyle(color = colors.primary)) { append(stringResource(R.string.login_wordmark_shelf)) }
                        },
                        style = MaterialTheme.typography.headlineLarge,
                        color = colors.foreground,
                    )
                    Text(
                        stringResource(R.string.login_subtitle),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = colors.mutedForeground,
                    )
                    Spacer(Modifier.height(24.dp))

                    LoginField(
                        value = state.server,
                        onValueChange = onServerChange,
                        label = stringResource(R.string.login_server),
                        placeholder = stringResource(R.string.login_server_placeholder),
                        enabled = !state.busy,
                        isError = error == LoginError.Insecure || error == LoginError.BadAddress ||
                            (missing && state.server.isBlank()),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    LoginField(
                        value = state.username,
                        onValueChange = onUsernameChange,
                        label = stringResource(R.string.login_username),
                        enabled = !state.busy,
                        isError = missing && state.username.isBlank(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier.semantics { contentType = ContentType.Username },
                    )
                    LoginField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = stringResource(R.string.login_password),
                        enabled = !state.busy,
                        isError = error == LoginError.WrongPassword || (missing && state.password.isEmpty()),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                LucideIcon(
                                    name = if (showPassword) "EyeOff" else "Eye",
                                    contentDescription = stringResource(
                                        if (showPassword) R.string.login_hide_password else R.string.login_show_password,
                                    ),
                                    tint = colors.mutedForeground,
                                    size = 20.dp,
                                    fallback = null,
                                )
                            }
                        },
                        modifier = Modifier.semantics { contentType = ContentType.Password },
                    )

                    if (error != null) {
                        Text(
                            errorText(error),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                            color = colors.destructive,
                        )
                    }

                    Button(
                        onClick = submit,
                        enabled = !state.busy,
                        modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(OttershelfTheme.radii.md),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary,
                            disabledContainerColor = colors.primary.atAlpha(0.5f),
                            disabledContentColor = colors.onPrimary.atAlpha(0.8f),
                        ),
                    ) {
                        Text(
                            stringResource(if (state.busy) R.string.login_signing_in else R.string.login_sign_in),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (state.busy && !LocalInspectionMode.current) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(top = 16.dp).size(36.dp),
                            color = colors.primary,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        }
    }
}

/** The Nexus `Field` style: full width, 8dp above, one line; drawn as the web's inputs. */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isError: Boolean,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    placeholder: String? = null,
) {
    val colors = OttershelfTheme.colors
    val container = colors.background.atAlpha(0.6f)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(OttershelfTheme.radii.md),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.foreground,
            unfocusedTextColor = colors.foreground,
            disabledTextColor = colors.mutedForeground,
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            errorContainerColor = container,
            cursorColor = colors.primary,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.input,
            disabledBorderColor = colors.border,
            errorBorderColor = colors.destructive,
            focusedLabelColor = colors.primary,
            unfocusedLabelColor = colors.mutedForeground,
            disabledLabelColor = colors.mutedForeground,
            errorLabelColor = colors.destructive,
            errorCursorColor = colors.destructive,
            focusedPlaceholderColor = colors.mutedForeground,
            unfocusedPlaceholderColor = colors.mutedForeground,
        ),
        modifier = modifier.padding(top = 8.dp).fillMaxWidth(),
    )
}

@Composable
private fun errorText(error: LoginError): String = when (error) {
    LoginError.Missing -> stringResource(R.string.login_error_missing)
    LoginError.Insecure -> stringResource(R.string.login_error_insecure)
    LoginError.BadAddress -> stringResource(R.string.login_error_bad_address)
    LoginError.WrongPassword -> stringResource(R.string.login_error_wrong)
    LoginError.DefaultPassword -> stringResource(R.string.login_error_default_password)
    is LoginError.Failed -> stringResource(R.string.login_error_failed, error.detail)
    is LoginError.Tls -> stringResource(R.string.login_error_tls, error.detail)
    is LoginError.Unreachable -> stringResource(R.string.login_error_unreachable, error.detail)
    is LoginError.Unexpected -> stringResource(R.string.login_error_unexpected, error.detail)
}
