package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.letterboxd.LetterboxdFilm
import com.nuvio.app.features.letterboxd.LetterboxdFriendOnFilm
import com.nuvio.app.features.letterboxd.LetterboxdFriends
import com.nuvio.app.features.letterboxd.LetterboxdFriendsEntry
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.letterboxd.buildLetterboxdFriendsEntries
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Home row "Recently rated by friends" (Letterboxd). Hook: one call in HomeScreen's LazyColumn.
 * Hidden on platforms without Letterboxd, when no account is connected, or when disabled in the settings.
 */
internal fun LazyListScope.letterboxdFriendsRow(
    sectionPadding: Dp,
    onPosterClick: ((MetaPreview) -> Unit)?,
) {
    if (!LetterboxdRepository.isSupported) return
    item(key = "letterboxd_friends_row", contentType = "letterboxd_friends") {
        LetterboxdFriendsRowSection(sectionPadding = sectionPadding, onPosterClick = onPosterClick)
    }
}

/** Nuvio item for a resolved Letterboxd film: IMDb id when known (Cinemeta), else `tmdb:<id>`. */
internal fun LetterboxdFilm.toMetaPreview(fallbackTitle: String): MetaPreview? {
    val id = imdbId ?: tmdbId?.let { "tmdb:$it" } ?: return null
    return MetaPreview(
        id = id,
        type = "movie",
        name = name ?: fallbackTitle,
        poster = posterUrl,
        releaseInfo = year?.toString(),
    )
}

@Composable
private fun LetterboxdFriendsRowSection(
    sectionPadding: Dp,
    onPosterClick: ((MetaPreview) -> Unit)?,
) {
    val feed = LetterboxdFriends.feed
    val account by LetterboxdRepository.state.collectAsStateWithLifecycle()
    val state by feed.state.collectAsStateWithLifecycle()
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        LetterboxdRepository.ensureLoaded()
        feed.load()
    }

    val visible = account.isLoaded && account.hasCredentials && state.isLoaded && state.settings.enabled
    ScreenActivityEffect(visible) { active ->
        if (active && visible) feed.onRowVisible()
    }
    if (!visible) return

    val watchedKeys = watched.watchedKeys
    val entries = remember(state, watchedKeys) {
        buildLetterboxdFriendsEntries(state, kotlin.time.Clock.System.now().toEpochMilliseconds()) { film ->
            film.isWatchedIn(watchedKeys)
        }
    }
    val scope = rememberCoroutineScope()
    val errorMessage = when {
        account.reconnectRequired -> stringResource(Res.string.letterboxd_error_auth_expired)
        state.error != null -> stringResource(Res.string.letterboxd_friends_error, stringResource(state.error!!.messageResource()))
        else -> null
    }

    val emptyMessage = stringResource(
        if (state.isRefreshing && state.fetchedAtEpochMs == 0L) Res.string.letterboxd_friends_loading else Res.string.letterboxd_friends_empty,
    )
    LetterboxdFriendsRowContent(
        entries = entries,
        sectionPadding = sectionPadding,
        isRefreshing = state.isRefreshing || state.opening != null,
        errorMessage = errorMessage,
        emptyMessage = emptyMessage,
        isWatched = { it.film?.isWatchedIn(watchedKeys) == true },
        onRefresh = { feed.refresh() },
        onClick = onPosterClick?.let { open ->
            { entry ->
                scope.launch {
                    val film = feed.filmForOpen(entry.slug)
                    val preview = film?.toMetaPreview(entry.title)
                    if (preview != null) {
                        open(preview)
                    } else {
                        val reason = feed.state.value.error?.messageResource()?.let { getString(it) }.orEmpty()
                        NuvioToastController.show(getString(Res.string.letterboxd_friends_open_failed, reason), 5000L)
                    }
                }
            }
        },
    )
}

/** Stateless row (header with refresh button, error line, posters with friends' badges). */
@Composable
internal fun LetterboxdFriendsRowContent(
    entries: List<LetterboxdFriendsEntry>,
    sectionPadding: Dp,
    isRefreshing: Boolean,
    errorMessage: String?,
    emptyMessage: String,
    isWatched: (LetterboxdFriendsEntry) -> Boolean,
    onRefresh: () -> Unit,
    onClick: ((LetterboxdFriendsEntry) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FriendsHeader(sectionPadding = sectionPadding, isRefreshing = isRefreshing, onRefresh = onRefresh)
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                modifier = Modifier.padding(horizontal = sectionPadding),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (entries.isEmpty()) {
            if (errorMessage == null) {
                Text(
                    text = emptyMessage,
                    modifier = Modifier.padding(horizontal = sectionPadding),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        NuvioShelfSection(
            title = "",
            entries = entries,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            key = { it.key },
        ) { entry ->
            FriendsPoster(
                entry = entry,
                isWatched = isWatched(entry),
                onClick = onClick?.let { { it(entry) } },
            )
        }
    }
}

private fun LetterboxdFilm.isWatchedIn(watchedKeys: Set<String>): Boolean {
    if (watchedKeys.isEmpty()) return false
    val ids = listOfNotNull(imdbId, tmdbId?.let { "tmdb:$it" })
    return ids.any { id -> watchedItemKeys(type = "movie", id = id).any(watchedKeys::contains) }
}

@Composable
private fun FriendsHeader(sectionPadding: Dp, isRefreshing: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = sectionPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.letterboxd_friends_row_title),
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(Res.string.letterboxd_friends_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendsPoster(entry: LetterboxdFriendsEntry, isWatched: Boolean, onClick: (() -> Unit)?) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    Box {
        NuvioPosterCard(
            title = entry.title,
            imageUrl = entry.posterUrl,
            basePosterWidthDp = desktopCatalogShelfPosterBaseWidthDp(posterCardStyle.widthDp),
            detailLine = entry.film?.year?.toString(),
            isWatched = isWatched,
            onClick = onClick,
        )
        Box(modifier = Modifier.matchParentSize()) {
            FriendBadges(friends = entry.friends, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}

@Composable
private fun FriendBadges(friends: List<LetterboxdFriendOnFilm>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        friends.take(MAX_BADGES).forEach { friend -> FriendBadge(friend) }
        if (friends.size > MAX_BADGES) {
            BadgeSurface {
                Text(
                    text = stringResource(Res.string.letterboxd_friends_more, friends.size - MAX_BADGES),
                    style = badgeTextStyle(),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun FriendBadge(friend: LetterboxdFriendOnFilm) {
    BadgeSurface {
        Avatar(friend)
        val label = friend.rating?.let(::ratingStars)
        if (label != null) {
            Text(text = label, style = badgeTextStyle(), color = letterboxdGreen, maxLines = 1)
        } else if (!friend.liked) {
            Text(
                text = stringResource(if (friend.reviewed) Res.string.letterboxd_friends_reviewed else Res.string.letterboxd_friends_watched),
                style = badgeTextStyle(),
                color = Color.White,
                maxLines = 1,
            )
        }
        if (friend.liked) {
            Text(text = "♥", style = badgeTextStyle(), color = letterboxdOrange)
        }
    }
}

@Composable
private fun BadgeSurface(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(start = 2.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun Avatar(friend: LetterboxdFriendOnFilm) {
    val modifier = Modifier.size(18.dp).clip(CircleShape)
    if (friend.avatarUrl != null) {
        NuvioAsyncImage(
            model = friend.avatarUrl,
            contentDescription = friend.name,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text(text = friend.name.take(1).uppercase(), style = badgeTextStyle(), color = Color.White)
        }
    }
}

@Composable
private fun badgeTextStyle() = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

/** 3.5 → "★★★½" (Letterboxd style). */
internal fun ratingStars(rating: Double): String {
    val halves = (rating * 2).toInt().coerceIn(1, 10)
    return "★".repeat(halves / 2) + if (halves % 2 == 1) "½" else ""
}

private const val MAX_BADGES = 3
private val letterboxdGreen = Color(0xFF00E054)
private val letterboxdOrange = Color(0xFFFF8000)
