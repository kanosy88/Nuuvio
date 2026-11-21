package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.components.RatingStars
import com.nuvio.app.features.letterboxd.ClientLetterboxdFilmSource
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelController
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelState
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelState.Action
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelState.Connection
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelState.Phase
import com.nuvio.app.features.letterboxd.LetterboxdFilmPanelState.PersonalPhase
import com.nuvio.app.features.letterboxd.LetterboxdFriendActivity
import com.nuvio.app.features.letterboxd.LetterboxdLibraryBridge
import com.nuvio.app.features.letterboxd.LetterboxdLogEntry
import com.nuvio.app.features.letterboxd.LetterboxdLogEntryDraft
import com.nuvio.app.features.letterboxd.LetterboxdRatingHistogram
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.settings.SettingsPage
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.SettingsPageRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * F1 hook: Letterboxd block on a film's detail page (films only; nothing for series or on platforms without
 * Letterboxd support). Added after the hero, the existing actions (Play…) are untouched.
 */
internal fun LazyListScope.letterboxdFilmDetailItem(
    meta: MetaDetails,
    horizontalPadding: Dp,
    contentMaxWidth: Dp = Dp.Unspecified,
    onOpenSettings: (() -> Unit)?,
) {
    if (!LetterboxdRepository.isSupported || !meta.type.equals("movie", ignoreCase = true)) return
    item(key = "detail-letterboxd-${meta.id}", contentType = "detail-letterboxd") {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(bottom = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            LetterboxdFilmBlock(
                meta = meta,
                onOpenSettings = onOpenSettings,
                modifier = if (contentMaxWidth == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.widthIn(max = contentMaxWidth).fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun LetterboxdFilmBlock(
    meta: MetaDetails,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var tmdbId by remember(meta.id) { mutableStateOf<Int?>(null) }
    val currentMeta by rememberUpdatedState(meta)
    val controller = remember(meta.id) {
        LetterboxdFilmPanelController(
            source = ClientLetterboxdFilmSource.default,
            scope = scope,
            onBecameWatched = { removeWatchedFilmFromLibrary(currentMeta, tmdbId) },
        )
    }
    LaunchedEffect(meta.id) {
        LetterboxdRepository.ensureLoaded()
        val resolved = runCatching { TmdbService.ensureTmdbId(meta.id, "movie", meta.imdbId)?.toIntOrNull() }.getOrNull()
        tmdbId = resolved
        val imdbId = meta.imdbId ?: meta.id.substringBefore(':').takeIf { it.startsWith("tt") }
        controller.load(resolved, imdbId)
    }
    LaunchedEffect(controller) {
        // Nuvio's watched / library buttons wrote to Letterboxd: show the new state.
        LetterboxdNativeActions.sync.changes.collect { slug ->
            if (slug == controller.state.value.film?.slug) controller.loadPersonal()
        }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    LetterboxdFilmBlockContent(
        title = meta.name,
        state = state,
        modifier = modifier,
        onRetry = controller::retry,
        onRetryPersonal = controller::loadPersonal,
        onOpenSettings = onOpenSettings,
        onToggleLike = controller::toggleLike,
        onRate = controller::setRating,
        onSaveReview = controller::saveReview,
    )
}

/** A rating / review made the film "watched" on Letterboxd: it leaves the Nuvio library too (with a notice). */
internal suspend fun removeWatchedFilmFromLibrary(meta: MetaDetails, tmdbId: Int?) {
    when (LetterboxdLibraryBridge.removeWatchedMovie(meta, tmdbId)) {
        LetterboxdLibraryBridge.Result.Removed -> NuvioToastController.show(getString(Res.string.letterboxd_film_removed_from_library))
        LetterboxdLibraryBridge.Result.NeedsConfirmation -> NuvioToastController.show(getString(Res.string.letterboxd_film_library_confirm), 4000L)
        LetterboxdLibraryBridge.Result.Failed -> NuvioToastController.show(getString(Res.string.letterboxd_film_library_failed), 4000L)
        LetterboxdLibraryBridge.Result.NotInLibrary -> Unit
    }
}

/** Stateless rendering of [LetterboxdFilmPanelState] (also used by the screenshot test). */
@Composable
internal fun LetterboxdFilmBlockContent(
    title: String,
    state: LetterboxdFilmPanelState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onRetryPersonal: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    onToggleLike: () -> Unit = {},
    onRate: (Double?) -> Unit = {},
    onSaveReview: (LetterboxdLogEntryDraft, String?) -> Unit = { _, _ -> },
) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(tokens.colors.textPrimary.copy(alpha = 0.04f))
            .border(1.dp, tokens.colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(Res.drawable.rating_letterboxd),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(Res.string.letterboxd_settings_title),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            val film = state.film
            if (film != null) {
                TextButton(onClick = { runCatching { uriHandler.openUri(film.url) } }) {
                    Text(stringResource(Res.string.letterboxd_film_open))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }

        when (state.phase) {
            Phase.Idle, Phase.Loading -> LoadingLine(stringResource(Res.string.letterboxd_film_loading))
            Phase.NotOnLetterboxd -> MutedText(stringResource(Res.string.letterboxd_film_not_found))
            Phase.Failed -> ErrorLine(
                message = stringResource((state.error ?: com.nuvio.app.features.letterboxd.LetterboxdError.Network).messageResource()),
                onRetry = onRetry,
            )
            Phase.Ready -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 720.dp
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        CommunitySection(state, Modifier.width(300.dp))
                        PersonalSection(title, state, Modifier.weight(1f), onRetryPersonal, onOpenSettings, onToggleLike, onRate, onSaveReview)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        CommunitySection(state, Modifier.fillMaxWidth())
                        PersonalSection(title, state, Modifier.fillMaxWidth(), onRetryPersonal, onOpenSettings, onToggleLike, onRate, onSaveReview)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ community (average + histogram)

@Composable
private fun CommunitySection(state: LetterboxdFilmPanelState, modifier: Modifier) {
    val film = state.film ?: return
    val tokens = MaterialTheme.nuvio
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            val average = film.averageRating
            Text(
                text = average?.let { formatOneDecimal(it) } ?: "–",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 36.sp),
                color = tokens.colors.textPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 6.dp)) {
                if (average != null) {
                    Text(starsText(average), color = LetterboxdGreen, fontSize = 14.sp)
                }
                Text(
                    text = film.ratingCount?.let { stringResource(Res.string.letterboxd_film_ratings_count, formatCompactCount(it)) }
                        ?: stringResource(Res.string.letterboxd_film_no_average),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
        val histogram = state.histogram
        if (histogram != null && histogram.total > 0) {
            RatingHistogram(histogram, highlight = state.myState?.rating)
        } else if (state.histogram == null && film.averageRating != null) {
            MutedText(stringResource(Res.string.letterboxd_film_histogram_unavailable))
        }
    }
}

@Composable
private fun RatingHistogram(histogram: LetterboxdRatingHistogram, highlight: Double?) {
    val tokens = MaterialTheme.nuvio
    val max = histogram.counts.maxOrNull()?.takeIf { it > 0 } ?: 1
    val highlightIndex = highlight?.let { (it * 2).roundToInt() - 1 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            histogram.counts.forEachIndexed { index, count ->
                val fraction = (count.toFloat() / max).coerceIn(0.03f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (index == highlightIndex) LetterboxdGreen else tokens.colors.textPrimary.copy(alpha = 0.28f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("½★", fontSize = 11.sp, color = tokens.colors.textMuted, modifier = Modifier.weight(1f))
            Text("★★★★★", fontSize = 11.sp, color = tokens.colors.textMuted)
        }
    }
}

// ------------------------------------------------------------------ personal (state, actions, review, friends)

@Composable
private fun PersonalSection(
    title: String,
    state: LetterboxdFilmPanelState,
    modifier: Modifier,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onToggleLike: () -> Unit,
    onRate: (Double?) -> Unit,
    onSaveReview: (LetterboxdLogEntryDraft, String?) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (state.connection) {
            Connection.NotConnected -> SettingsPrompt(stringResource(Res.string.letterboxd_film_connect_prompt), onOpenSettings, warning = false)
            Connection.ReconnectRequired -> SettingsPrompt(stringResource(Res.string.letterboxd_film_reconnect), onOpenSettings, warning = true)
            Connection.Connected -> when (state.personal) {
                PersonalPhase.Idle, PersonalPhase.Loading -> LoadingLine(stringResource(Res.string.letterboxd_film_personal_loading))
                PersonalPhase.Failed -> ErrorLine(
                    message = stringResource((state.personalError ?: com.nuvio.app.features.letterboxd.LetterboxdError.Network).messageResource()),
                    onRetry = onRetry,
                )
                PersonalPhase.Ready -> {
                    MyActivity(title, state, onToggleLike, onRate, onSaveReview)
                    FriendsSection(state)
                }
            }
        }
    }
}

/**
 * F5 (end-of-film prompt): the member's own actions of the block — watched state, like, review / diary
 * entry, half-star rating — without the community part. [onRatingDraft] reports the stars picked but not
 * sent yet (the rating is committed after a short pause), so closing the prompt can still send it.
 */
@Composable
internal fun LetterboxdMyActivityPanel(
    title: String,
    state: LetterboxdFilmPanelState,
    onRetry: () -> Unit,
    onToggleLike: () -> Unit,
    onRate: (Double?) -> Unit,
    onSaveReview: (LetterboxdLogEntryDraft, String?) -> Unit,
    onRatingDraft: (Int) -> Unit,
) {
    when (state.phase) {
        Phase.Idle, Phase.Loading -> LoadingLine(stringResource(Res.string.letterboxd_film_loading))
        Phase.NotOnLetterboxd -> MutedText(stringResource(Res.string.letterboxd_film_not_found))
        Phase.Failed -> ErrorLine(
            message = stringResource((state.error ?: com.nuvio.app.features.letterboxd.LetterboxdError.Network).messageResource()),
            onRetry = onRetry,
        )
        Phase.Ready -> when (state.connection) {
            Connection.NotConnected -> MutedText(stringResource(Res.string.letterboxd_film_connect_prompt))
            Connection.ReconnectRequired -> MutedText(stringResource(Res.string.letterboxd_film_reconnect))
            Connection.Connected -> when (state.personal) {
                PersonalPhase.Idle, PersonalPhase.Loading -> LoadingLine(stringResource(Res.string.letterboxd_film_personal_loading))
                PersonalPhase.Failed -> ErrorLine(
                    message = stringResource((state.personalError ?: com.nuvio.app.features.letterboxd.LetterboxdError.Network).messageResource()),
                    onRetry = onRetry,
                )
                PersonalPhase.Ready -> MyActivity(title, state, onToggleLike, onRate, onSaveReview, onRatingDraft)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyActivity(
    title: String,
    state: LetterboxdFilmPanelState,
    onToggleLike: () -> Unit,
    onRate: (Double?) -> Unit,
    onSaveReview: (LetterboxdLogEntryDraft, String?) -> Unit,
    onRatingDraft: (Int) -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val myState = state.myState ?: return
    val busy = state.busy != null
    var reviewDialog by remember { mutableStateOf<LetterboxdLogEntry?>(null) }
    var showReviewDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Read-only: Nuvio's own "watched" button (next to Play) drives it (LetterboxdNativeSyncEffect).
            StatusChip(
                selected = myState.watched,
                icon = if (myState.watched) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                label = stringResource(if (myState.watched) Res.string.letterboxd_film_watched else Res.string.letterboxd_film_not_watched),
                selectedColor = LetterboxdGreen,
            )
            ToggleChip(
                selected = myState.liked,
                icon = if (myState.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = stringResource(if (myState.liked) Res.string.letterboxd_film_liked else Res.string.letterboxd_film_like),
                selectedColor = LetterboxdOrange,
                enabled = !busy,
                loading = state.busy == Action.Like,
                onClick = onToggleLike,
            )
            ToggleChip(
                selected = false,
                icon = Icons.Rounded.Edit,
                label = stringResource(if (state.review != null) Res.string.letterboxd_film_edit_review else Res.string.letterboxd_film_write_review),
                selectedColor = LetterboxdGreen,
                enabled = !busy,
                loading = state.busy == Action.Review,
                onClick = {
                    reviewDialog = state.review
                    showReviewDialog = true
                },
            )
        }

        RatingRow(savedRating = myState.rating, busy = state.busy, onRate = onRate, onDraft = onRatingDraft)

        if (state.busy != null) {
            LoadingLine(stringResource(Res.string.letterboxd_film_saving))
        }
        state.actionError?.let { error ->
            Text(
                text = listOfNotNull(stringResource(error.messageResource()), state.actionErrorDetail).joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }

        state.review?.let { ReviewCard(it) }
    }

    if (showReviewDialog) {
        LetterboxdReviewDialog(
            filmTitle = title,
            editing = reviewDialog,
            initialRating = myState.rating,
            initialLiked = myState.liked,
            initialRewatch = myState.diaryEntryCount > 0,
            onDismiss = { showReviewDialog = false },
            onSave = { draft ->
                showReviewDialog = false
                onSaveReview(draft, reviewDialog?.id)
            },
        )
    }
}

/** Half-star rating, committed after a short pause so a drag across the stars sends a single request. */
@Composable
private fun RatingRow(savedRating: Double?, busy: Action?, onRate: (Double?) -> Unit, onDraft: (Int) -> Unit = {}) {
    val tokens = MaterialTheme.nuvio
    val saved = savedRating?.let { (it * 2).roundToInt() } ?: 0
    var draft by remember { mutableIntStateOf(saved) }
    LaunchedEffect(saved) { draft = saved }
    val currentOnDraft by rememberUpdatedState(onDraft)
    LaunchedEffect(draft) { currentOnDraft(draft) }
    val currentBusy by rememberUpdatedState(busy)
    LaunchedEffect(draft) {
        if (draft == saved || draft == 0) return@LaunchedEffect
        delay(800)
        snapshotFlow { currentBusy }.first { it == null }
        onRate(draft / 2.0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(Res.string.letterboxd_film_your_rating) + " · " +
                (if (draft > 0) formatRating(draft / 2.0) else stringResource(Res.string.letterboxd_film_not_rated)),
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            RatingStars(value = draft, onValueChange = { if (busy == null) draft = it }, starSize = 30.dp)
            if (saved > 0) {
                IconButton(onClick = { draft = 0; onRate(null) }, enabled = busy == null) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.letterboxd_film_remove_rating), tint = MaterialTheme.nuvio.colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(review: LetterboxdLogEntry) {
    val tokens = MaterialTheme.nuvio
    var revealed by remember(review.id) { mutableStateOf(!review.containsSpoilers) }
    var expanded by remember(review.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.textPrimary.copy(alpha = 0.05f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.letterboxd_film_your_review),
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
            )
            review.rating?.let {
                Spacer(Modifier.width(8.dp))
                Text(starsText(it), color = LetterboxdGreen, fontSize = 13.sp)
            }
            if (review.liked) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = LetterboxdOrange, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.weight(1f))
            val meta = listOfNotNull(
                review.diaryDate?.let { stringResource(Res.string.letterboxd_film_watched_on, it) },
                if (review.rewatch) stringResource(Res.string.letterboxd_film_rewatch) else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
        }
        if (!revealed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MutedText(stringResource(Res.string.letterboxd_film_spoilers_hidden), Modifier.weight(1f))
                TextButton(onClick = { revealed = true }) { Text(stringResource(Res.string.letterboxd_film_show)) }
            }
        } else {
            Text(
                text = review.reviewText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
            if ((review.reviewText?.length ?: 0) > 320 || (review.reviewText?.count { it == '\n' } ?: 0) > 5) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) Res.string.letterboxd_film_less else Res.string.letterboxd_film_more))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FriendsSection(state: LetterboxdFilmPanelState) {
    val tokens = MaterialTheme.nuvio
    val watchers = state.friends.filter { it.watched }
    val wanting = state.friends.filter { it.wantsToWatch }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.letterboxd_film_friends), style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
        when {
            state.friendsError != null -> MutedText(stringResource(state.friendsError.messageResource()))
            watchers.isEmpty() -> MutedText(stringResource(Res.string.letterboxd_film_friends_none))
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    watchers.forEach { FriendChip(it) }
                }
            }
        }
        if (wanting.isNotEmpty()) {
            MutedText(stringResource(Res.string.letterboxd_film_friends_want, wanting.joinToString(", ") { it.displayName }))
        }
    }
}

@Composable
private fun FriendChip(friend: LetterboxdFriendActivity) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tokens.colors.textPrimary.copy(alpha = 0.06f))
            .clickable { runCatching { uriHandler.openUri("https://letterboxd.com/${friend.username}/") } }
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = friend.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(28.dp).clip(CircleShape).background(tokens.colors.textPrimary.copy(alpha = 0.1f)),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(friend.displayName, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                friend.rating?.let { Text(starsText(it), color = LetterboxdGreen, fontSize = 12.sp) }
                if (friend.liked) {
                    if (friend.rating != null) Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = LetterboxdOrange, modifier = Modifier.size(12.dp))
                }
                if (friend.rating == null && !friend.liked) {
                    Text(stringResource(Res.string.letterboxd_film_watched), color = tokens.colors.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ review / diary dialog

@Composable
private fun LetterboxdReviewDialog(
    filmTitle: String,
    editing: LetterboxdLogEntry?,
    initialRating: Double?,
    initialLiked: Boolean,
    initialRewatch: Boolean,
    onDismiss: () -> Unit,
    onSave: (LetterboxdLogEntryDraft) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val today = remember { CurrentDateProvider.todayIsoDate() }
    var addToDiary by remember { mutableStateOf(editing?.let { it.diaryDate != null } ?: true) }
    var date by remember { mutableStateOf(editing?.diaryDate ?: today) }
    var rewatch by remember { mutableStateOf(editing?.rewatch ?: initialRewatch) }
    var rating by remember { mutableIntStateOf(((editing?.rating ?: initialRating) ?: 0.0).times(2).roundToInt()) }
    var liked by remember { mutableStateOf(editing?.liked ?: initialLiked) }
    var text by remember { mutableStateOf(editing?.reviewText.orEmpty()) }
    var spoilers by remember { mutableStateOf(editing?.containsSpoilers ?: false) }
    var showErrors by remember { mutableStateOf(false) }
    val dateValid = !addToDiary || Regex("""\d{4}-\d{2}-\d{2}""").matches(date.trim())
    val hasContent = addToDiary || text.isNotBlank()

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(if (editing != null) Res.string.letterboxd_review_title_edit else Res.string.letterboxd_review_title_new, filmTitle),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CheckRow(stringResource(Res.string.letterboxd_review_add_to_diary), addToDiary) { addToDiary = it }
            if (addToDiary) {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it.take(10) },
                    label = { Text(stringResource(Res.string.letterboxd_review_date)) },
                    singleLine = true,
                    isError = showErrors && !dateValid,
                    supportingText = if (showErrors && !dateValid) {
                        { Text(stringResource(Res.string.letterboxd_review_date_invalid)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                CheckRow(stringResource(Res.string.letterboxd_review_rewatch), rewatch) { rewatch = it }
            }
            Text(stringResource(Res.string.letterboxd_film_your_rating), style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatingStars(value = rating, onValueChange = { rating = it }, starSize = 30.dp)
                if (rating > 0) {
                    IconButton(onClick = { rating = 0 }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(Res.string.letterboxd_film_remove_rating), tint = MaterialTheme.nuvio.colors.textMuted)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { liked = !liked }) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(Res.string.letterboxd_film_like),
                        tint = if (liked) LetterboxdOrange else tokens.colors.textMuted,
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(Res.string.letterboxd_review_text)) },
                placeholder = { Text(stringResource(Res.string.letterboxd_review_placeholder)) },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            CheckRow(stringResource(Res.string.letterboxd_review_spoilers), spoilers) { spoilers = it }
            if (showErrors && !hasContent) {
                Text(stringResource(Res.string.letterboxd_review_empty), color = tokens.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_cancel), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_save),
                style = DialogButtonStyle.Primary,
                onClick = {
                    if (!dateValid || !hasContent) {
                        showErrors = true
                    } else {
                        onSave(
                            LetterboxdLogEntryDraft(
                                diaryDate = if (addToDiary) date.trim() else null,
                                rewatch = addToDiary && rewatch,
                                review = text.trim().takeIf { it.isNotEmpty() },
                                containsSpoilers = spoilers && text.isNotBlank(),
                                rating = rating.takeIf { it > 0 }?.let { it / 2.0 },
                                liked = liked,
                            ),
                        )
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------ small pieces

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textPrimary)
    }
}

@Composable
private fun ToggleChip(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selectedColor: Color,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) selectedColor else tokens.colors.textPrimary.copy(alpha = 0.2f)),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = if (selected) selectedColor else tokens.colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) selectedColor else tokens.colors.textPrimary)
    }
}

/** Same look as [ToggleChip], not clickable. */
@Composable
private fun StatusChip(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selectedColor: Color,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .height(40.dp)
            .border(1.dp, if (selected) selectedColor else tokens.colors.textPrimary.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) selectedColor else tokens.colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) selectedColor else tokens.colors.textSecondary)
    }
}

@Composable
private fun SettingsPrompt(message: String, onOpenSettings: (() -> Unit)?, warning: Boolean) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = if (warning) tokens.colors.warning else tokens.colors.textSecondary)
        if (onOpenSettings != null) {
            TextButton(onClick = onOpenSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(Res.string.letterboxd_film_open_settings))
            }
        }
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        MutedText(text)
    }
}

@Composable
private fun ErrorLine(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.danger, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    }
}

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textMuted, modifier = modifier)
}

internal val LetterboxdGreen = Color(0xFF00C030)
private val LetterboxdOrange = Color(0xFFFF8000)

internal fun starsText(rating: Double): String {
    val halves = (rating * 2).roundToInt().coerceIn(0, 10)
    return "★".repeat(halves / 2) + if (halves % 2 == 1) "½" else ""
}

/** "3.5", "4" (rating out of 5, 0.5 steps). */
internal fun formatRating(rating: Double): String =
    if (rating % 1.0 == 0.0) rating.toInt().toString() else formatOneDecimal(rating)

internal fun formatOneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** 950 → "950", 12_345 → "12.3K", 1_234_567 → "1.2M". */
internal fun formatCompactCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> formatOneDecimal(count / 1_000.0).removeSuffix(".0") + "K"
    else -> formatOneDecimal(count / 1_000_000.0).removeSuffix(".0") + "M"
}

/** Opens Settings › Letterboxd on top of the current screen (used by the detail page's "not connected" link). */
@Composable
internal fun rememberLetterboxdSettingsOpener(navController: NuvioNavigator): (() -> Unit)? {
    if (!LetterboxdRepository.isSupported) return null
    val title = stringResource(Res.string.letterboxd_settings_title)
    return remember(navController, title) {
        { navController.navigate(SettingsPageRoute(pageName = SettingsPage.Letterboxd.name, title = title)) }
    }
}
