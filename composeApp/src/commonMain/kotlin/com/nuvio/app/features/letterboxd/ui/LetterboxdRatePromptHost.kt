package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.journal.JournalRepository
import com.nuvio.app.features.letterboxd.ClientLetterboxdFilmSource
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelController
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelState
import com.nuvio.app.features.letterboxd.LetterboxdLibraryBridge
import com.nuvio.app.features.letterboxd.LetterboxdLogEntryDraft
import com.nuvio.app.features.letterboxd.LetterboxdNativeSync
import com.nuvio.app.features.letterboxd.LetterboxdRateLimitSettings
import com.nuvio.app.features.letterboxd.LetterboxdRatePromptCoordinator
import com.nuvio.app.features.letterboxd.LetterboxdRatePromptRequest
import com.nuvio.app.features.letterboxd.LetterboxdRatePromptSettingsStore
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.letterboxd.LetterboxdViewingRef
import com.nuvio.app.features.letterboxd.PreferencePromptLog
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerScreenArgs
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** App-wide F5 wiring: the prompt outlives the player and its writes outlive the dialog. */
internal object LetterboxdRatePrompts {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val coordinator: LetterboxdRatePromptCoordinator by lazy {
        LetterboxdRatePromptCoordinator(
            scope = scope,
            settings = { LetterboxdRatePromptSettingsStore.state.value },
            journalThreshold = { JournalRepository.settings.value.watchedThresholdPercent },
            account = { LetterboxdRepository.state.value },
            ensureReady = {
                LetterboxdRepository.ensureLoaded() // not suspending: wait for the account to be read
                withTimeoutOrNull(10_000L) { LetterboxdRepository.state.first { it.isLoaded } }
                LetterboxdRatePromptSettingsStore.ensureLoaded()
                LetterboxdRateLimitSettings.ensureLoaded()
            },
            prepare = ::prepare,
            log = PreferencePromptLog(nowMs = { kotlin.time.Clock.System.now().toEpochMilliseconds() }),
        )
    }

    /** Watched on Letterboxd (one write at most, shared with the detail-page observer), then the prompt. */
    private suspend fun prepare(ref: LetterboxdViewingRef): Pair<MetaDetails, com.nuvio.app.features.letterboxd.LetterboxdFilm>? {
        val meta = try {
            MetaDetailsRepository.fetch(type = "movie", id = ref.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: MetaDetails(id = ref.id, type = "movie", name = ref.title, poster = ref.poster)
        val target = LetterboxdNativeSync.Target(
            key = meta.id,
            resolveFilm = { resolveLetterboxdFilm(meta) },
            removeFromLibrary = {
                val tmdbId = runCatching { TmdbService.ensureTmdbId(meta.id, "movie", meta.imdbId)?.toIntOrNull() }.getOrNull()
                LetterboxdLibraryBridge.removeWatchedMovie(meta, tmdbId)
            },
        )
        val film = LetterboxdNativeActions.sync.ensureWatched(target) ?: return null
        return meta to film
    }
}

/**
 * F5 player hook (inside the player, desktop only, films only): follows the playback position and reports
 * the end of the session to [LetterboxdRatePrompts]. Reads the snapshot through [snapshot] in a
 * [snapshotFlow] at a coarse resolution so the player isn't recomposed for it.
 */
@Composable
internal fun LetterboxdRatePromptPlayerEffect(args: PlayerScreenArgs, snapshot: () -> PlayerPlaybackSnapshot) {
    if (!LetterboxdRepository.isSupported || !args.parentMetaType.equals("movie", ignoreCase = true)) return
    val currentSnapshot by rememberUpdatedState(snapshot)
    LaunchedEffect(args.parentMetaId) {
        LetterboxdRepository.ensureLoaded()
        LetterboxdRatePromptSettingsStore.ensureLoaded()
        val session = LetterboxdRatePrompts.coordinator.startSession(
            LetterboxdViewingRef(
                id = args.parentMetaId,
                title = args.title,
                poster = args.poster,
                initialPositionMs = args.initialPositionMs,
                initialProgressFraction = args.initialProgressFraction,
            ),
        )
        try {
            snapshotFlow {
                val value = currentSnapshot()
                Triple(value.positionMs / 2_000L, value.durationMs / 1_000L, value.isEnded)
            }.collect {
                val value = currentSnapshot()
                session.observe(value.positionMs, value.durationMs, value.isEnded)
            }
        } finally {
            session.close()
        }
    }
}

/** F5 root hook: shows the pending prompt once no player is on screen. */
@Composable
internal fun LetterboxdRatePromptHost() {
    if (!LetterboxdRepository.isSupported) return
    val coordinator = LetterboxdRatePrompts.coordinator
    val request by coordinator.pending.collectAsStateWithLifecycle()
    val players by coordinator.activePlayers.collectAsStateWithLifecycle()
    val current = request ?: return
    if (players > 0) return
    LetterboxdRatePromptDialog(current, onClose = { coordinator.dismiss(current) })
}

@Composable
private fun LetterboxdRatePromptDialog(request: LetterboxdRatePromptRequest, onClose: () -> Unit) {
    val meta = request.meta
    val controller = remember(request.id) {
        LetterboxdFilmPanelController(
            source = ClientLetterboxdFilmSource.default,
            // App scope: a rating / review sent right before closing still goes through.
            scope = LetterboxdRatePrompts.scope,
            onBecameWatched = { removeWatchedFilmFromLibrary(meta, request.film.tmdbId) },
            includeCommunity = false,
        )
    }
    LaunchedEffect(controller) {
        // Film already resolved (persistent index) and its state just read: no new request in general.
        controller.load(request.film.tmdbId, meta.imdbId ?: meta.id.substringBefore(':').takeIf { it.startsWith("tt") })
    }
    LaunchedEffect(controller) {
        LetterboxdNativeActions.sync.changes.collect { slug ->
            if (slug == controller.state.value.film?.slug) controller.loadPersonal()
        }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    var ratingDraft by remember(request.id) { mutableIntStateOf(-1) }
    val close = {
        // Stars picked less than a moment ago are still waiting for their commit: send them now.
        val saved = state.myState?.rating?.let { (it * 2).toInt() } ?: 0
        if (ratingDraft > 0 && ratingDraft != saved && state.busy == null) controller.setRating(ratingDraft / 2.0)
        onClose()
    }
    LetterboxdRatePromptContent(
        title = meta.name,
        poster = meta.poster,
        state = state,
        onRetry = controller::retry,
        onToggleLike = controller::toggleLike,
        onRate = controller::setRating,
        onSaveReview = controller::saveReview,
        onRatingDraft = { ratingDraft = it },
        onClose = close,
    )
}

/** Stateless rendering of the prompt (also used by the screenshot test). */
@Composable
internal fun LetterboxdRatePromptContent(
    title: String,
    poster: String?,
    state: LetterboxdFilmPanelState,
    onRetry: () -> Unit = {},
    onToggleLike: () -> Unit = {},
    onRate: (Double?) -> Unit = {},
    onSaveReview: (LetterboxdLogEntryDraft, String?) -> Unit = { _, _ -> },
    onRatingDraft: (Int) -> Unit = {},
    onClose: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(onDismissRequest = onClose, title = stringResource(Res.string.letterboxd_rate_prompt_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(6.dp))
                    .background(tokens.colors.textPrimary.copy(alpha = 0.08f)),
            ) {
                if (poster != null) {
                    NuvioAsyncImage(model = poster, contentDescription = null, modifier = Modifier.width(48.dp).height(72.dp), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.myState?.watched == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = LetterboxdGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.letterboxd_rate_prompt_watched), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textSecondary)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            LetterboxdMyActivityPanel(
                title = title,
                state = state,
                onRetry = onRetry,
                onToggleLike = onToggleLike,
                onRate = onRate,
                onSaveReview = onSaveReview,
                onRatingDraft = onRatingDraft,
            )
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.letterboxd_rate_prompt_not_now), onClick = onClose)
            DialogButton(text = stringResource(Res.string.letterboxd_rate_prompt_done), style = DialogButtonStyle.Primary, onClick = onClose)
        }
    }
}
