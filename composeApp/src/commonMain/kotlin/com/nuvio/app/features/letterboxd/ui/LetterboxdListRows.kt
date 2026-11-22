package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.catalog.LetterboxdListCatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.letterboxd.LetterboxdFilm
import com.nuvio.app.features.letterboxd.LetterboxdListConfig
import com.nuvio.app.features.letterboxd.LetterboxdListRowEntry
import com.nuvio.app.features.letterboxd.LetterboxdLists
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.letterboxd.buildLetterboxdListRow
import com.nuvio.app.features.letterboxd.hasMoreThanRow
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Home rows of the Letterboxd lists added in Settings › Letterboxd (one row per enabled list, in the chosen order).
 * Hook: one call in HomeScreen's LazyColumn. Hidden on platforms without Letterboxd and when no list is enabled.
 * "View all" opens the app's catalog screen ([LetterboxdListCatalogTarget]).
 */
internal fun LazyListScope.letterboxdListRows(
    sectionPadding: Dp,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onCatalogClick: ((HomeCatalogSection) -> Unit)?,
) {
    if (!LetterboxdRepository.isSupported) return
    item(key = "letterboxd_list_rows", contentType = "letterboxd_lists") {
        LetterboxdListRowsSection(sectionPadding, onPosterClick, onPosterLongClick, onCatalogClick)
    }
}

@Composable
private fun LetterboxdListRowsSection(
    sectionPadding: Dp,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
    onCatalogClick: ((HomeCatalogSection) -> Unit)?,
) {
    val feed = LetterboxdLists.feed
    val account by LetterboxdRepository.state.collectAsStateWithLifecycle()
    val state by feed.state.collectAsStateWithLifecycle()
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        LetterboxdRepository.ensureLoaded()
        feed.load()
    }
    val connected = account.hasCredentials
    val visible = account.isLoaded && state.isLoaded
    val lists = if (visible) state.settings.visibleLists(connected) else emptyList()
    ScreenActivityEffect(lists.isNotEmpty(), connected) { active ->
        if (active && lists.isNotEmpty()) feed.onRowsVisible(connected)
    }
    if (lists.isEmpty()) return

    val scope = rememberCoroutineScope()
    val watchedKeys = watched.watchedKeys
    val subtitle = stringResource(Res.string.letterboxd_lists_catalog_subtitle)
    Column(modifier = Modifier.fillMaxWidth()) {
        lists.forEach { config ->
            val entries = remember(state, config.key) { buildLetterboxdListRow(state, config.key) }
            val cached = state.lists[config.key]
            val error = state.errors[config.key]
            val title = config.title.ifBlank { cached?.title ?: config.slug }
            LetterboxdListRowContent(
                title = title,
                entries = entries,
                sectionPadding = sectionPadding,
                isRefreshing = config.key in state.refreshing,
                errorMessage = error?.let {
                    stringResource(Res.string.letterboxd_lists_error, stringResource(it.messageResource()))
                },
                emptyMessage = stringResource(
                    if (cached == null) Res.string.letterboxd_lists_loading else Res.string.letterboxd_lists_empty,
                ),
                isWatched = { it.film?.isWatchedIn(watchedKeys) == true },
                onRefresh = { feed.refresh(connected, onlyKey = config.key) },
                onViewAll = onCatalogClick?.takeIf { state.hasMoreThanRow(config.key) }?.let { open ->
                    { open(config.toCatalogSection(title, subtitle)) }
                },
                onClick = onPosterClick?.let { open ->
                    { entry ->
                        scope.launch {
                            val film = feed.filmForOpen(entry.slug)
                            val preview = film?.toMetaPreview(entry.title)
                            if (preview != null) {
                                open(preview)
                            } else {
                                val reason = feed.state.value.openError?.messageResource()?.let { getString(it) }.orEmpty()
                                NuvioToastController.show(getString(Res.string.letterboxd_friends_open_failed, reason), 5000L)
                            }
                        }
                    }
                },
                onLongClick = onPosterLongClick?.let { open ->
                    { entry -> entry.film?.toMetaPreview(entry.title)?.let(open) }
                },
            )
        }
    }
}

private fun LetterboxdListConfig.toCatalogSection(title: String, subtitle: String) = HomeCatalogSection(
    key = "letterboxd_list:$key",
    title = title,
    subtitle = subtitle,
    addonName = subtitle,
    target = LetterboxdListCatalogTarget(key),
    items = emptyList(),
)

/** Stateless row (header with refresh and "View all", error line, posters). */
@Composable
internal fun LetterboxdListRowContent(
    title: String,
    entries: List<LetterboxdListRowEntry>,
    sectionPadding: Dp,
    isRefreshing: Boolean,
    errorMessage: String?,
    emptyMessage: String,
    isWatched: (LetterboxdListRowEntry) -> Boolean,
    onRefresh: () -> Unit,
    onViewAll: (() -> Unit)?,
    onClick: ((LetterboxdListRowEntry) -> Unit)?,
    onLongClick: ((LetterboxdListRowEntry) -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ListHeader(title, sectionPadding, isRefreshing, onRefresh, onViewAll)
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
        val posterCardStyle = rememberPosterCardStyleUiState()
        NuvioShelfSection(
            title = "",
            entries = entries,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            key = { it.slug },
        ) { entry ->
            NuvioPosterCard(
                title = entry.title,
                imageUrl = entry.posterUrl,
                basePosterWidthDp = desktopCatalogShelfPosterBaseWidthDp(posterCardStyle.widthDp),
                detailLine = entry.year?.toString(),
                isWatched = isWatched(entry),
                onClick = onClick?.let { { it(entry) } },
                onLongClick = onLongClick?.takeIf { entry.film != null }?.let { { it(entry) } },
            )
        }
    }
}

@Composable
private fun ListHeader(
    title: String,
    sectionPadding: Dp,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onViewAll: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = sectionPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .then(if (onViewAll != null) Modifier.clickable(onClick = onViewAll) else Modifier),
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
        if (onViewAll != null) {
            // Same look as the catalog rows' "View all" pill (private in ShelfComponents).
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .clickable(onClick = onViewAll),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.home_view_all),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

internal fun LetterboxdFilm.isWatchedIn(watchedKeys: Set<String>): Boolean {
    if (watchedKeys.isEmpty()) return false
    val ids = listOfNotNull(imdbId, tmdbId?.let { "tmdb:$it" })
    return ids.any { id -> watchedItemKeys(type = "movie", id = id).any(watchedKeys::contains) }
}
