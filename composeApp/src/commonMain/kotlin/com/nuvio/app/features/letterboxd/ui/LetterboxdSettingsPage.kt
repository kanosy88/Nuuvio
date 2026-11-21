package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.features.letterboxd.LetterboxdAccountState
import com.nuvio.app.features.letterboxd.LetterboxdConnectionReport
import com.nuvio.app.features.letterboxd.LetterboxdConnectionStatus
import com.nuvio.app.features.letterboxd.LetterboxdError
import com.nuvio.app.features.letterboxd.LetterboxdProbeResult
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.letterboxd.LetterboxdSecretStorageKind
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Settings › Integrations › Letterboxd. */
internal fun LazyListScope.letterboxdSettingsContent(isTablet: Boolean) {
    item(key = "letterboxd_account") {
        val state by LetterboxdRepository.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { LetterboxdRepository.ensureLoaded() }
        SettingsSection(title = stringResource(Res.string.letterboxd_section_account), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                LetterboxdStatusRow(state = state, isTablet = isTablet)
            }
        }
    }
    item(key = "letterboxd_cookies") {
        val state by LetterboxdRepository.state.collectAsStateWithLifecycle()
        SettingsSection(title = stringResource(Res.string.letterboxd_section_cookies), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                LetterboxdCredentialsForm(state = state, isTablet = isTablet)
            }
        }
    }
    item(key = "letterboxd_report") {
        val state by LetterboxdRepository.state.collectAsStateWithLifecycle()
        val report = state.lastReport
        if (report != null) {
            SettingsSection(title = stringResource(Res.string.letterboxd_section_result), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    LetterboxdReportRows(report = report, isTablet = isTablet)
                }
            }
        }
    }
    letterboxdFriendsSettingsContent(isTablet)
    letterboxdRatePromptSettingsContent(isTablet)
    letterboxdRateLimitsSettingsContent(isTablet)
}

/** Click handler for the Integrations entry, or null when Letterboxd isn't supported on this platform. */
internal fun letterboxdSettingsEntry(onClick: () -> Unit): (() -> Unit)? =
    if (LetterboxdRepository.isSupported) onClick else null

/** Row appended to the Integrations group (see IntegrationsSettingsPage). */
@Composable
internal fun letterboxdIntegrationRow(isTablet: Boolean, onClick: () -> Unit) {
    SettingsGroupDivider(isTablet = isTablet)
    SettingsNavigationRow(
        title = stringResource(Res.string.letterboxd_settings_title),
        description = stringResource(Res.string.letterboxd_integration_description),
        isTablet = isTablet,
        onClick = onClick,
    )
}

/** User-facing message for a [LetterboxdError]; reusable by other Letterboxd features. */
internal fun LetterboxdError.messageResource(): StringResource = when (this) {
    LetterboxdError.NotConnected -> Res.string.letterboxd_error_not_connected
    LetterboxdError.AuthExpired -> Res.string.letterboxd_error_auth_expired
    LetterboxdError.SessionCookieMissing -> Res.string.letterboxd_error_session_cookie_missing
    LetterboxdError.CloudflareChallenge -> Res.string.letterboxd_error_cloudflare
    LetterboxdError.Rejected -> Res.string.letterboxd_error_rejected
    LetterboxdError.NotFound -> Res.string.letterboxd_error_not_found
    LetterboxdError.RateLimited -> Res.string.letterboxd_error_rate_limited
    LetterboxdError.Network -> Res.string.letterboxd_error_network
    LetterboxdError.Server -> Res.string.letterboxd_error_server
    LetterboxdError.Parse -> Res.string.letterboxd_error_parse
    LetterboxdError.Storage -> Res.string.letterboxd_error_storage
}

private val successColor = Color(0xFF3FB950)
private val warningColor = Color(0xFFE3A008)

@Composable
private fun rowPadding(isTablet: Boolean) =
    Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp)

@Composable
private fun LetterboxdStatusRow(state: LetterboxdAccountState, isTablet: Boolean) {
    Column(modifier = rowPadding(isTablet), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val (text, color) = when (state.status) {
            LetterboxdConnectionStatus.Disconnected ->
                stringResource(Res.string.letterboxd_status_disconnected) to MaterialTheme.colorScheme.onSurfaceVariant
            LetterboxdConnectionStatus.Unverified ->
                stringResource(Res.string.letterboxd_status_unverified) to warningColor
            LetterboxdConnectionStatus.Connected ->
                (state.username?.let { stringResource(Res.string.letterboxd_status_connected, it) }
                    ?: stringResource(Res.string.letterboxd_status_connected_unknown)) to successColor
            LetterboxdConnectionStatus.ReconnectRequired ->
                stringResource(Res.string.letterboxd_status_reconnect) to MaterialTheme.colorScheme.error
        }
        Text(text = text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = color)
        if (state.hasCredentials) {
            Text(
                text = stringResource(Res.string.letterboxd_saved_cookies, state.cookieNames.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            storageResource(state.storageKind)?.let { resource ->
                Text(
                    text = stringResource(resource),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun storageResource(kind: LetterboxdSecretStorageKind): StringResource? = when (kind) {
    LetterboxdSecretStorageKind.MacKeychain -> Res.string.letterboxd_storage_mac
    LetterboxdSecretStorageKind.WindowsDpapi -> Res.string.letterboxd_storage_windows
    LetterboxdSecretStorageKind.LinuxSecretService -> Res.string.letterboxd_storage_linux
    LetterboxdSecretStorageKind.RestrictedFile -> Res.string.letterboxd_storage_file
    LetterboxdSecretStorageKind.Unavailable -> null
}

@Composable
private fun LetterboxdCredentialsForm(state: LetterboxdAccountState, isTablet: Boolean) {
    val scope = rememberCoroutineScope()
    var cookies by remember { mutableStateOf("") }
    var userAgent by remember(state.userAgent) { mutableStateOf(state.userAgent) }
    var cookiesVisible by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
    )

    Column(modifier = rowPadding(isTablet), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HelpText(stringResource(Res.string.letterboxd_cookies_help))
        OutlinedTextField(
            value = cookies,
            onValueChange = { cookies = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy,
            label = { Text(stringResource(Res.string.letterboxd_cookies_label)) },
            minLines = 2,
            maxLines = 5,
            visualTransformation = if (cookiesVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { cookiesVisible = !cookiesVisible }) {
                    Icon(
                        imageVector = if (cookiesVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(
                            if (cookiesVisible) Res.string.settings_hide_secret else Res.string.settings_show_secret,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = fieldColors,
        )
        OutlinedTextField(
            value = userAgent,
            onValueChange = { userAgent = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isBusy,
            singleLine = true,
            label = { Text(stringResource(Res.string.letterboxd_user_agent_label)) },
            colors = fieldColors,
        )
        HelpText(stringResource(Res.string.letterboxd_user_agent_help))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val pasted = cookies
                    scope.launch {
                        val report = LetterboxdRepository.saveCredentials(pasted, userAgent)
                        if (report != null) {
                            cookies = ""
                            cookiesVisible = false
                        }
                    }
                },
                enabled = !state.isBusy && cookies.isNotBlank(),
            ) {
                Text(stringResource(Res.string.letterboxd_action_save_test))
            }
            OutlinedButton(
                onClick = { scope.launch { LetterboxdRepository.testConnection() } },
                enabled = !state.isBusy && state.hasCredentials,
            ) {
                Text(stringResource(Res.string.letterboxd_action_test))
            }
            if (state.hasCredentials) {
                TextButton(
                    onClick = { scope.launch { LetterboxdRepository.signOut() } },
                    enabled = !state.isBusy,
                ) {
                    Text(stringResource(Res.string.letterboxd_action_sign_out), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (state.isBusy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                HelpText(stringResource(Res.string.letterboxd_testing))
            }
        }
        val error = state.lastError
        if (error != null && !state.isBusy && state.lastReport?.error != error) {
            Text(
                text = stringResource(error.messageResource()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.hasCredentials) HelpText(stringResource(Res.string.letterboxd_sign_out_note))
    }
}

@Composable
private fun LetterboxdReportRows(report: LetterboxdConnectionReport, isTablet: Boolean) {
    Column(modifier = rowPadding(isTablet), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val identity = when (report.identity) {
            LetterboxdProbeResult.Ok ->
                stringResource(Res.string.letterboxd_result_identity_ok, report.username.orEmpty()) to successColor
            LetterboxdProbeResult.Unauthorized ->
                stringResource(Res.string.letterboxd_result_identity_unauthorized) to MaterialTheme.colorScheme.error
            LetterboxdProbeResult.CloudflareChallenge ->
                stringResource(Res.string.letterboxd_result_identity_cloudflare) to MaterialTheme.colorScheme.error
            else -> stringResource(Res.string.letterboxd_result_identity_failed) to MaterialTheme.colorScheme.error
        }
        ResultLine(identity.first, identity.second)
        val api = when (report.api) {
            LetterboxdProbeResult.Ok -> stringResource(Res.string.letterboxd_result_api_ok) to successColor
            LetterboxdProbeResult.CloudflareChallenge -> stringResource(Res.string.letterboxd_result_api_cloudflare) to warningColor
            LetterboxdProbeResult.Unauthorized -> stringResource(Res.string.letterboxd_result_api_unauthorized) to MaterialTheme.colorScheme.error
            LetterboxdProbeResult.Failed -> stringResource(Res.string.letterboxd_result_api_failed) to warningColor
            LetterboxdProbeResult.Skipped -> stringResource(Res.string.letterboxd_result_api_skipped) to MaterialTheme.colorScheme.onSurfaceVariant
        }
        ResultLine(api.first, api.second)
        if (report.identity == LetterboxdProbeResult.Failed || report.identity == LetterboxdProbeResult.Unauthorized) {
            report.error?.let { ResultLine(stringResource(it.messageResource()), MaterialTheme.colorScheme.error) }
        }
        if (report.identity != LetterboxdProbeResult.Ok) {
            report.httpStatus?.takeIf { it != 200 }?.let {
                ResultLine(stringResource(Res.string.letterboxd_result_http_status, it.toString()), MaterialTheme.colorScheme.error)
            }
        }

        val warnings = buildList {
            if (report.identity == LetterboxdProbeResult.Ok && !report.csrfTokenFound) add(Res.string.letterboxd_result_csrf_missing)
            val blocked = report.identity == LetterboxdProbeResult.CloudflareChallenge ||
                report.api == LetterboxdProbeResult.CloudflareChallenge
            if (blocked && !report.hasClearanceCookie) add(Res.string.letterboxd_result_no_clearance)
            if (report.hasClearanceCookie && !report.customUserAgent) add(Res.string.letterboxd_result_no_user_agent)
        }
        if (warnings.isNotEmpty()) {
            SettingsGroupDivider(isTablet = isTablet)
            warnings.forEach { ResultLine(stringResource(it), warningColor) }
        }
        HelpText(stringResource(Res.string.letterboxd_result_checked_at, formatLocalDateTime(report.checkedAtEpochMs)))
    }
}

@Composable
private fun ResultLine(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun HelpText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
