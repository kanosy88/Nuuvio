package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.letterboxd.LetterboxdError
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.letterboxd.LetterboxdWatchlistSync
import com.nuvio.app.features.letterboxd.LetterboxdWatchlistSyncController.State
import com.nuvio.app.features.letterboxd.WatchlistSyncAction
import com.nuvio.app.features.letterboxd.WatchlistSyncAnalysis
import com.nuvio.app.features.letterboxd.WatchlistSyncException
import com.nuvio.app.features.letterboxd.WatchlistSyncProgress
import com.nuvio.app.features.letterboxd.WatchlistSyncReport
import com.nuvio.app.features.letterboxd.WatchlistSyncResult
import com.nuvio.app.features.letterboxd.WatchlistSyncSkip
import com.nuvio.app.features.letterboxd.WatchlistSyncUnmatched
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * F3 hook (Library tab header): button that syncs the Letterboxd watchlist with the Nuvio library, with a mandatory
 * preview. Shown only when a Letterboxd account is set up (desktop).
 */
@Composable
internal fun LetterboxdLibrarySyncButton() {
    if (!LetterboxdRepository.isSupported) return
    LaunchedEffect(Unit) { LetterboxdRepository.ensureLoaded() }
    val account by LetterboxdRepository.state.collectAsStateWithLifecycle()
    if (!account.hasCredentials) return
    val controller = LetterboxdWatchlistSync.controller
    val state by controller.state.collectAsStateWithLifecycle()
    var dialogOpen by remember { mutableStateOf(false) }
    val dialogVisible by rememberUpdatedState(dialogOpen)
    val running = state is State.Analyzing || state is State.Applying

    LaunchedEffect(state) {
        val done = state as? State.Done ?: return@LaunchedEffect
        if (dialogVisible) return@LaunchedEffect
        val applied = done.report.results.count { it.succeeded }
        NuvioToastController.show(getString(Res.string.letterboxd_wsync_done_toast, applied, done.report.failures.size), 5000L)
    }

    IconButton(
        onClick = {
            if (state is State.Idle) controller.analyze()
            dialogOpen = true
        },
    ) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = stringResource(Res.string.letterboxd_wsync_button),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (dialogOpen && state !is State.Idle) {
        LetterboxdWatchlistSyncDialog(
            state = state,
            onApply = controller::apply,
            onRetry = controller::analyze,
            onDismiss = {
                controller.dismiss()
                dialogOpen = false
            },
        )
    }
}

@Composable
internal fun LetterboxdWatchlistSyncDialog(
    state: State,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(onDismissRequest = onDismiss, title = stringResource(Res.string.letterboxd_wsync_title)) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                State.Idle -> Unit
                is State.Analyzing -> ProgressContent(progressText(state.progress), hint = stringResource(Res.string.letterboxd_wsync_progress_hint))
                is State.Applying -> ProgressContent(
                    progressText(state.progress ?: WatchlistSyncProgress.Applying(0, state.analysis.plan.actions.size)),
                    hint = stringResource(Res.string.letterboxd_wsync_apply_hint),
                )
                is State.Preview -> PreviewContent(state.analysis)
                is State.Done -> ReportContent(state.report)
                is State.Failed -> Text(
                    failureText(state.failure, state.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        DialogButtons {
            when (state) {
                is State.Preview -> {
                    DialogButton(stringResource(Res.string.letterboxd_wsync_cancel), onClick = onDismiss)
                    if (state.analysis.plan.actions.isNotEmpty()) {
                        DialogButton(stringResource(Res.string.letterboxd_wsync_apply), onClick = onApply, style = DialogButtonStyle.Primary)
                    }
                }
                is State.Failed -> {
                    DialogButton(stringResource(Res.string.letterboxd_wsync_close), onClick = onDismiss)
                    DialogButton(stringResource(Res.string.letterboxd_wsync_retry), onClick = onRetry, style = DialogButtonStyle.Primary)
                }
                is State.Analyzing -> DialogButton(stringResource(Res.string.letterboxd_wsync_cancel), onClick = onDismiss)
                is State.Applying -> DialogButton(stringResource(Res.string.letterboxd_wsync_hide), onClick = onDismiss)
                else -> DialogButton(stringResource(Res.string.letterboxd_wsync_close), onClick = onDismiss, style = DialogButtonStyle.Primary)
            }
        }
    }
}

@Composable
private fun ProgressContent(text: String, hint: String) {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
}

@Composable
private fun PreviewContent(analysis: WatchlistSyncAnalysis) {
    val plan = analysis.plan
    if (plan.firstSync) Note(stringResource(Res.string.letterboxd_wsync_first_sync))
    if (analysis.incompleteWatchlist) {
        Warning(stringResource(Res.string.letterboxd_wsync_warning_incomplete, analysis.watchlistAnnounced ?: 0, analysis.watchlistRead))
    }
    if (analysis.massRemoval) Warning(stringResource(Res.string.letterboxd_wsync_warning_mass_removal, plan.removeFromLetterboxd.size))
    Text(
        stringResource(Res.string.letterboxd_wsync_summary_letterboxd, plan.addToLetterboxd.size, plan.removeFromLetterboxd.size),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        stringResource(Res.string.letterboxd_wsync_summary_library, plan.addToLibrary.size, plan.removeFromLibrary.size),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        stringResource(Res.string.letterboxd_wsync_in_sync, plan.inSync.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textSecondary,
    )
    if (plan.actions.isEmpty()) Note(stringResource(Res.string.letterboxd_wsync_nothing))
    ActionSection(Res.string.letterboxd_wsync_section_add_letterboxd, plan.addToLetterboxd)
    ActionSection(Res.string.letterboxd_wsync_section_remove_letterboxd, plan.removeFromLetterboxd)
    ActionSection(Res.string.letterboxd_wsync_section_add_library, plan.addToLibrary)
    ActionSection(Res.string.letterboxd_wsync_section_remove_library, plan.removeFromLibrary)
    SkippedSection(plan.skipped)
    UnmatchedSection(plan.unmatched)
}

@Composable
private fun ReportContent(report: WatchlistSyncReport) {
    val applied = report.results.count { it.succeeded }
    val failures = report.failures
    Text(
        if (failures.isEmpty()) stringResource(Res.string.letterboxd_wsync_done_ok, applied)
        else stringResource(Res.string.letterboxd_wsync_done_partial, applied, failures.size),
        style = MaterialTheme.typography.titleSmall,
    )
    if (!report.snapshotSaved) Warning(stringResource(Res.string.letterboxd_wsync_snapshot_not_saved))
    if (failures.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.letterboxd_wsync_section_failed), failures.size)
        failures.forEach { result ->
            FilmRow(result.action.title, "${actionLabel(result.action)} · ${outcomeText(result.outcome)}")
        }
    }
    SkippedSection(report.plan.skipped)
    UnmatchedSection(report.plan.unmatched)
}

@Composable
private fun ActionSection(title: org.jetbrains.compose.resources.StringResource, actions: List<WatchlistSyncAction>) {
    if (actions.isEmpty()) return
    SectionTitle(stringResource(title), actions.size)
    actions.forEach { action -> FilmRow(action.title, actionReason(action)) }
}

@Composable
private fun SkippedSection(skipped: List<WatchlistSyncSkip>) {
    if (skipped.isEmpty()) return
    SectionTitle(stringResource(Res.string.letterboxd_wsync_section_skipped), skipped.size)
    skipped.forEach { skip ->
        FilmRow(
            skip.title,
            stringResource(
                when (skip.reason) {
                    WatchlistSyncSkip.Reason.WatchedKeptOnFirstSync -> Res.string.letterboxd_wsync_skip_watched_first
                    WatchlistSyncSkip.Reason.WatchedStateUnknown -> Res.string.letterboxd_wsync_skip_watched_unknown
                    WatchlistSyncSkip.Reason.LibraryMatchUncertain -> Res.string.letterboxd_wsync_skip_uncertain
                },
            ),
        )
    }
}

@Composable
private fun UnmatchedSection(unmatched: List<WatchlistSyncUnmatched>) {
    if (unmatched.isEmpty()) return
    SectionTitle(stringResource(Res.string.letterboxd_wsync_section_unmatched), unmatched.size)
    unmatched.forEach { film ->
        val side = stringResource(
            if (film.side == WatchlistSyncUnmatched.Side.Library) Res.string.letterboxd_wsync_side_library
            else Res.string.letterboxd_wsync_side_letterboxd,
        )
        val reason = when (film.reason) {
            WatchlistSyncUnmatched.Reason.NoExternalId -> stringResource(Res.string.letterboxd_wsync_unmatched_no_id)
            WatchlistSyncUnmatched.Reason.NotOnLetterboxd -> stringResource(Res.string.letterboxd_wsync_unmatched_not_on_letterboxd)
            WatchlistSyncUnmatched.Reason.TvShow -> stringResource(Res.string.letterboxd_wsync_unmatched_tv)
            WatchlistSyncUnmatched.Reason.LookupFailed -> listOfNotNull(
                stringResource(Res.string.letterboxd_wsync_unmatched_failed),
                film.error?.let { stringResource(it.messageResource()) },
            ).joinToString(" · ")
        }
        FilmRow(film.title, "$side · $reason")
    }
}

@Composable
private fun SectionTitle(text: String, count: Int) {
    Text(
        "$text ($count)",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.nuvio.colors.textSecondary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun FilmRow(title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = WarningColor)
}

@Composable
private fun actionReason(action: WatchlistSyncAction): String = stringResource(
    when (action) {
        is WatchlistSyncAction.AddToLetterboxd ->
            if (action.removedOnLetterboxd) Res.string.letterboxd_wsync_reason_removed_on_letterboxd
            else Res.string.letterboxd_wsync_reason_library_only
        is WatchlistSyncAction.RemoveFromLetterboxd -> Res.string.letterboxd_wsync_reason_removed_from_library
        is WatchlistSyncAction.AddToLibrary -> Res.string.letterboxd_wsync_reason_new_on_letterboxd
        is WatchlistSyncAction.RemoveFromLibrary -> Res.string.letterboxd_wsync_reason_watched
    },
)

@Composable
private fun actionLabel(action: WatchlistSyncAction): String = stringResource(
    when (action) {
        is WatchlistSyncAction.AddToLetterboxd -> Res.string.letterboxd_wsync_section_add_letterboxd
        is WatchlistSyncAction.RemoveFromLetterboxd -> Res.string.letterboxd_wsync_section_remove_letterboxd
        is WatchlistSyncAction.AddToLibrary -> Res.string.letterboxd_wsync_section_add_library
        is WatchlistSyncAction.RemoveFromLibrary -> Res.string.letterboxd_wsync_section_remove_library
    },
)

@Composable
private fun outcomeText(outcome: WatchlistSyncResult.Outcome): String = when (outcome) {
    WatchlistSyncResult.Outcome.ChangedSincePreview -> stringResource(Res.string.letterboxd_wsync_outcome_changed)
    WatchlistSyncResult.Outcome.NeedsConfirmation -> stringResource(Res.string.letterboxd_wsync_outcome_confirm)
    is WatchlistSyncResult.Outcome.Failed -> outcome.error?.let { error ->
        listOfNotNull(stringResource(error.messageResource()), outcome.detail).joinToString(" ")
    } ?: stringResource(Res.string.letterboxd_wsync_outcome_library_failed)
    is WatchlistSyncResult.Outcome.NotAttempted ->
        stringResource(Res.string.letterboxd_wsync_outcome_not_attempted, stringResource(outcome.error.messageResource()))
    WatchlistSyncResult.Outcome.Done, WatchlistSyncResult.Outcome.AlreadyDone -> ""
}

@Composable
private fun progressText(progress: WatchlistSyncProgress?): String = when (progress) {
    null -> stringResource(Res.string.letterboxd_wsync_progress_start)
    is WatchlistSyncProgress.ReadingWatchlist -> stringResource(Res.string.letterboxd_wsync_progress_watchlist, progress.page)
    is WatchlistSyncProgress.MatchingLibrary -> stringResource(Res.string.letterboxd_wsync_progress_matching, progress.done, progress.total)
    is WatchlistSyncProgress.LookingUpWatchlist -> stringResource(Res.string.letterboxd_wsync_progress_lookup, progress.done, progress.total)
    is WatchlistSyncProgress.CheckingWatched -> stringResource(Res.string.letterboxd_wsync_progress_watched, progress.done, progress.total)
    is WatchlistSyncProgress.Applying -> stringResource(Res.string.letterboxd_wsync_progress_apply, progress.done, progress.total)
}

@Composable
private fun failureText(failure: WatchlistSyncException.Failure, error: LetterboxdError?): String = when (failure) {
    WatchlistSyncException.Failure.LibraryNotReady -> stringResource(Res.string.letterboxd_wsync_error_library_not_ready)
    WatchlistSyncException.Failure.LibraryChanged -> stringResource(Res.string.letterboxd_wsync_error_library_changed)
    WatchlistSyncException.Failure.Letterboxd -> stringResource((error ?: LetterboxdError.Server).messageResource())
}

private val WarningColor = Color(0xFFE3A008)
