package com.nuvio.app.features.journal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.journal.JournalEntry
import com.nuvio.app.features.journal.JournalKind
import com.nuvio.app.features.journal.JournalLetterboxdState
import com.nuvio.app.features.journal.JournalPeriod
import com.nuvio.app.features.journal.JournalQuery
import com.nuvio.app.features.journal.JournalRepository
import com.nuvio.app.features.journal.JournalSettings
import com.nuvio.app.features.journal.JournalSort
import com.nuvio.app.features.journal.JournalSource
import com.nuvio.app.features.journal.JournalSourceFilter
import com.nuvio.app.features.journal.JournalStats
import com.nuvio.app.features.journal.JournalStatus
import com.nuvio.app.features.journal.JournalStatusFilter
import com.nuvio.app.features.journal.JournalTypeFilter
import com.nuvio.app.features.journal.applyJournalQuery
import com.nuvio.app.features.journal.groupJournalByMonth
import com.nuvio.app.features.journal.journalGenres
import com.nuvio.app.features.journal.journalStats
import com.nuvio.app.features.journal.journalYears
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.letterboxd.ui.messageResource
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Journal (F4): every movie and series watched or in progress (Nuvio + journal history + Letterboxd films),
 * with filters, sorting, search, month sections and small stats. A click opens the detail page.
 */
@Composable
internal fun JournalScreen(
    onOpen: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
) {
    val entriesFlow = remember {
        JournalRepository.start()
        WatchProgressRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        JournalRepository.entries()
    }
    val entries by entriesFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by JournalRepository.settings.collectAsStateWithLifecycle()
    val letterboxd by JournalRepository.letterboxd.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(JournalQuery()) }
    val scope = rememberCoroutineScope()
    val openFailed = stringResource(Res.string.journal_lb_open_failed)

    ScreenActivityEffect(Unit) { active ->
        if (!active) return@ScreenActivityEffect
        LetterboxdRepository.ensureLoaded()
        JournalRepository.letterboxd.refresh()
    }
    LaunchedEffect(entries) { entries?.let(JournalRepository::requestMeta) }

    val open: (JournalEntry) -> Unit = { entry ->
        scope.launch {
            val preview = JournalRepository.previewFor(entry)
            if (preview != null) onOpen(preview) else NuvioToastController.show(openFailed, 4000L)
        }
    }
    JournalContent(
        entries = entries,
        settings = settings,
        letterboxd = letterboxd,
        journalSince = JournalRepository.journalStartedAt,
        query = query,
        onQueryChange = { query = it },
        onOpen = open,
        onToggleWatched = JournalRepository::setWatched,
        onRefreshLetterboxd = { JournalRepository.letterboxd.refresh(force = true) },
        onThresholdChange = JournalRepository::setWatchedThreshold,
        modifier = modifier,
        topChromePadding = topChromePadding,
    )
}

/** Stateless Journal screen (list, filters, stats). */
@Composable
internal fun JournalContent(
    entries: List<JournalEntry>?,
    settings: JournalSettings,
    letterboxd: JournalLetterboxdState,
    journalSince: Long,
    query: JournalQuery,
    onQueryChange: (JournalQuery) -> Unit,
    onOpen: (JournalEntry) -> Unit,
    onToggleWatched: (JournalEntry, Boolean) -> Unit,
    onRefreshLetterboxd: () -> Unit,
    onThresholdChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    nowMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
) {
    val listState = rememberLazyListState()
    val allEntries = entries.orEmpty()
    val visible = remember(allEntries, query) { applyJournalQuery(allEntries, query, nowMs, ::localIsoDate) }
    val groups = remember(visible, query.sort) {
        if (query.sort == JournalSort.Date) groupJournalByMonth(visible, ::localIsoDate) else null
    }
    val stats = remember(visible) { journalStats(visible) }
    val genres = remember(allEntries) { journalGenres(allEntries) }
    val years = remember(allEntries) { journalYears(allEntries) }
    val dates = rememberJournalDateFormatter()
    val open = onOpen

    NuvioScreen(
        modifier = modifier.fillMaxSize(),
        topPadding = if (topChromePadding != null) 0.dp else null,
        listState = listState,
    ) {
        item(key = "journal_header") {
            NuvioScreenHeader(
                title = stringResource(Res.string.journal_title),
                topPadding = topChromePadding,
                actions = {
                    if (letterboxd.connected) {
                        if (letterboxd.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = onRefreshLetterboxd) {
                                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(Res.string.journal_action_refresh))
                            }
                        }
                    }
                },
            )
        }
        item(key = "journal_controls") {
            JournalControls(
                query = query,
                onQueryChange = onQueryChange,
                genres = genres,
                years = years,
                showSourceFilter = letterboxd.connected,
                settings = settings,
                onThresholdChange = onThresholdChange,
            )
        }
        item(key = "journal_summary") {
            JournalSummary(stats = stats, letterboxd = letterboxd, journalSince = journalSince, dates = dates)
        }
        when {
            entries == null -> item(key = "journal_loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            visible.isEmpty() -> item(key = "journal_empty") {
                Text(
                    text = stringResource(if (allEntries.isEmpty()) Res.string.journal_empty else Res.string.journal_empty_filtered),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
            groups != null -> groups.forEach { group ->
                item(key = "journal_month_${group.yearMonth ?: "unknown"}") {
                    Text(
                        text = group.yearMonth?.let { dates.month(it) } ?: stringResource(Res.string.journal_date_unknown_group),
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                    )
                }
                items(group.entries, key = { "journal_${it.key}" }) { entry ->
                    JournalEntryRow(entry, dates, onClick = { open(entry) }, onToggleWatched = { onToggleWatched(entry, it) })
                }
            }
            else -> items(visible, key = { "journal_${it.key}" }) { entry ->
                JournalEntryRow(entry, dates, onClick = { open(entry) }, onToggleWatched = { onToggleWatched(entry, it) })
            }
        }
    }
}

@Composable
private fun JournalControls(
    query: JournalQuery,
    onQueryChange: (JournalQuery) -> Unit,
    genres: List<String>,
    years: List<Int>,
    showSourceFilter: Boolean,
    settings: JournalSettings,
    onThresholdChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NuvioInputField(
            value = query.search,
            onValueChange = { onQueryChange(query.copy(search = it)) },
            placeholder = stringResource(Res.string.journal_search_placeholder),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EnumChip(
                title = stringResource(Res.string.journal_filter_sort),
                selected = query.sort,
                options = JournalSort.entries.associateWith { sortLabel(it) },
            ) { onQueryChange(query.copy(sort = it)) }
            EnumChip(
                title = stringResource(Res.string.journal_filter_type),
                selected = query.type,
                options = mapOf(
                    JournalTypeFilter.All to stringResource(Res.string.journal_type_all),
                    JournalTypeFilter.Movies to stringResource(Res.string.journal_type_movies),
                    JournalTypeFilter.Series to stringResource(Res.string.journal_type_series),
                ),
            ) { onQueryChange(query.copy(type = it)) }
            EnumChip(
                title = stringResource(Res.string.journal_filter_status),
                selected = query.status,
                options = mapOf(
                    JournalStatusFilter.All to stringResource(Res.string.journal_status_all),
                    JournalStatusFilter.InProgress to stringResource(Res.string.journal_status_in_progress),
                    JournalStatusFilter.Watched to stringResource(Res.string.journal_status_watched),
                ),
            ) { onQueryChange(query.copy(status = it)) }
            if (showSourceFilter || query.source != JournalSourceFilter.All) {
                EnumChip(
                    title = stringResource(Res.string.journal_filter_source),
                    selected = query.source,
                    options = mapOf(
                        JournalSourceFilter.All to stringResource(Res.string.journal_source_all),
                        JournalSourceFilter.Nuvio to stringResource(Res.string.journal_source_nuvio),
                        JournalSourceFilter.Letterboxd to stringResource(Res.string.journal_source_letterboxd),
                    ),
                ) { onQueryChange(query.copy(source = it)) }
            }
            val allGenres = stringResource(Res.string.journal_genre_all)
            NuvioDropdownChip(
                title = stringResource(Res.string.journal_filter_genre),
                label = query.genre ?: allGenres,
                selectedKey = query.genre.orEmpty(),
                options = listOf(NuvioDropdownOption("", allGenres)) + genres.map { NuvioDropdownOption(it, it) },
                enabled = genres.isNotEmpty(),
                onSelected = { onQueryChange(query.copy(genre = it.key.ifBlank { null })) },
            )
            val allYears = stringResource(Res.string.journal_year_all)
            NuvioDropdownChip(
                title = stringResource(Res.string.journal_filter_year),
                label = query.year?.toString() ?: allYears,
                selectedKey = query.year?.toString().orEmpty(),
                options = listOf(NuvioDropdownOption("", allYears)) + years.map { NuvioDropdownOption(it.toString(), it.toString()) },
                enabled = years.isNotEmpty(),
                onSelected = { onQueryChange(query.copy(year = it.key.toIntOrNull())) },
            )
            EnumChip(
                title = stringResource(Res.string.journal_filter_period),
                selected = query.period,
                options = mapOf(
                    JournalPeriod.All to stringResource(Res.string.journal_period_all),
                    JournalPeriod.Last7Days to stringResource(Res.string.journal_period_7d),
                    JournalPeriod.Last30Days to stringResource(Res.string.journal_period_30d),
                    JournalPeriod.Last3Months to stringResource(Res.string.journal_period_3m),
                    JournalPeriod.Last12Months to stringResource(Res.string.journal_period_12m),
                    JournalPeriod.ThisYear to stringResource(Res.string.journal_period_this_year),
                ),
            ) { onQueryChange(query.copy(period = it)) }
            val thresholds = (JournalSettings.ThresholdRange step 5).toList()
            NuvioDropdownChip(
                title = stringResource(Res.string.journal_threshold_title),
                label = stringResource(Res.string.journal_threshold_value, settings.watchedThresholdPercent),
                selectedKey = settings.watchedThresholdPercent.toString(),
                options = thresholds.map { NuvioDropdownOption(it.toString(), stringResource(Res.string.journal_threshold_value, it)) },
                onSelected = { option -> option.key.toIntOrNull()?.let(onThresholdChange) },
            )
        }
    }
}

@Composable
private fun <T : Enum<T>> EnumChip(title: String, selected: T, options: Map<T, String>, onSelected: (T) -> Unit) {
    NuvioDropdownChip(
        title = title,
        label = options[selected].orEmpty(),
        selectedKey = selected.name,
        options = options.map { (value, label) -> NuvioDropdownOption(value.name, label) },
        onSelected = { option -> options.keys.firstOrNull { it.name == option.key }?.let(onSelected) },
    )
}

@Composable
private fun sortLabel(sort: JournalSort): String = stringResource(
    when (sort) {
        JournalSort.Date -> Res.string.journal_sort_date
        JournalSort.Title -> Res.string.journal_sort_title
        JournalSort.Rating -> Res.string.journal_sort_rating
        JournalSort.Year -> Res.string.journal_sort_year
    },
)

@Composable
private fun JournalSummary(stats: JournalStats, letterboxd: JournalLetterboxdState, journalSince: Long, dates: JournalDateFormatter) {
    val muted = MaterialTheme.nuvio.colors.textMuted
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val separator = stringResource(Res.string.letterboxd_rl_decimal_separator)
        Text(
            text = stringResource(Res.string.journal_stats, stats.entries, stats.movies, stats.series, stats.episodes, formatHours(stats.minutes, separator)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
        if (journalSince > 0L) {
            Text(stringResource(Res.string.journal_since, dates.date(journalSince)), style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (letterboxd.connected) {
            if (letterboxd.isRefreshing) Text(stringResource(Res.string.journal_lb_refreshing), style = MaterialTheme.typography.bodySmall, color = muted)
            letterboxd.error?.let { error ->
                Text(
                    stringResource(Res.string.journal_lb_error, stringResource(error.messageResource())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (letterboxd.incomplete) Text(stringResource(Res.string.journal_lb_incomplete), style = MaterialTheme.typography.bodySmall, color = muted)
            if (letterboxd.unresolvedCount > 0) {
                Text(stringResource(Res.string.journal_lb_unresolved, letterboxd.unresolvedCount), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (letterboxd.films.isNotEmpty()) Text(stringResource(Res.string.journal_lb_diary_note), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun JournalEntryRow(
    entry: JournalEntry,
    dates: JournalDateFormatter,
    onClick: () -> Unit,
    onToggleWatched: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val watched = entry.status == JournalStatus.Watched
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(8.dp)).background(tokens.colors.background),
        ) {
            if (entry.poster != null) {
                NuvioAsyncImage(
                    model = entry.poster,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                entry.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Badge(
                    text = stringResource(
                        when {
                            !watched -> Res.string.journal_status_in_progress
                            entry.kind == JournalKind.Series -> Res.string.journal_status_series_finished
                            else -> Res.string.journal_status_watched
                        },
                    ),
                    color = if (watched) tokens.colors.accent else tokens.colors.textMuted,
                )
                Badge(stringResource(if (entry.kind == JournalKind.Series) Res.string.journal_kind_series else Res.string.journal_kind_movie), tokens.colors.textMuted)
                if (JournalSource.Nuvio in entry.sources) Badge(stringResource(Res.string.journal_source_nuvio), tokens.colors.textMuted)
                if (JournalSource.Letterboxd in entry.sources) Badge(stringResource(Res.string.journal_source_letterboxd), LetterboxdGreen)
                entry.rating?.let { rating ->
                    Icon(Icons.Rounded.Star, contentDescription = null, modifier = Modifier.size(14.dp), tint = LetterboxdGreen)
                    Text(formatRating(rating), style = MaterialTheme.typography.labelMedium, color = tokens.colors.textPrimary)
                }
                if (entry.liked) {
                    Icon(Icons.Filled.Favorite, contentDescription = stringResource(Res.string.journal_liked), modifier = Modifier.size(14.dp), tint = LetterboxdOrange)
                }
            }
            JournalProgressLine(entry)
            val dateParts = buildList {
                when {
                    entry.startedAt != null -> add(stringResource(Res.string.journal_date_started, dates.date(entry.startedAt)))
                    JournalSource.Nuvio in entry.sources && entry.lastWatchedAt != null -> add(stringResource(Res.string.journal_started_unknown))
                }
                entry.lastWatchedAt?.let { add(stringResource(Res.string.journal_date_last, dates.date(it))) }
                entry.finishedAt?.let { add(stringResource(Res.string.journal_date_finished, dates.date(it))) }
                if (entry.timesWatched > 1) add(stringResource(Res.string.journal_times_watched, entry.timesWatched))
            }
            if (dateParts.isNotEmpty()) {
                Text(
                    text = dateParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.id != null) {
            IconButton(onClick = { onToggleWatched(!watched) }) {
                Icon(
                    imageVector = if (watched) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline,
                    contentDescription = stringResource(if (watched) Res.string.journal_action_mark_unwatched else Res.string.journal_action_mark_watched),
                    tint = if (watched) tokens.colors.accent else tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun JournalProgressLine(entry: JournalEntry) {
    val tokens = MaterialTheme.nuvio
    val label = when {
        entry.kind == JournalKind.Series && entry.episodesTotal != null ->
            stringResource(Res.string.journal_episodes_progress, entry.episodesWatched, entry.episodesTotal)
        entry.kind == JournalKind.Series -> stringResource(Res.string.journal_episodes_count, entry.episodesWatched)
        entry.status == JournalStatus.InProgress && entry.progress != null ->
            stringResource(Res.string.journal_progress_percent, (entry.progress * 100).toInt())
        else -> null
    } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textPrimary)
        entry.progress?.takeIf { entry.status == JournalStatus.InProgress || entry.kind == JournalKind.Series }?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = tokens.colors.accent,
                trackColor = tokens.colors.background,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/** Localized "Nov 8, 2026" / "8 novembre 2026" and "November 2026" month headers. */
internal class JournalDateFormatter(
    private val months: List<String>,
    private val dateFormat: (Int, String, Int) -> String,
    private val monthFormat: (String, Int) -> String,
) {
    fun date(epochMs: Long): String {
        val iso = localIsoDate(epochMs) ?: return ""
        val year = iso.take(4).toIntOrNull() ?: return iso
        val month = iso.substring(5, 7).toIntOrNull() ?: return iso
        val day = iso.substring(8, 10).toIntOrNull() ?: return iso
        return dateFormat(day, months.getOrElse(month - 1) { "$month" }, year)
    }

    /** [yearMonth] = `yyyy-MM`. */
    fun month(yearMonth: String): String {
        val year = yearMonth.take(4).toIntOrNull() ?: return yearMonth
        val month = yearMonth.drop(5).take(2).toIntOrNull() ?: return yearMonth
        return monthFormat(months.getOrElse(month - 1) { "$month" }.replaceFirstChar { it.uppercase() }, year)
    }
}

@Composable
private fun rememberJournalDateFormatter(): JournalDateFormatter {
    val months = listOf(
        stringResource(Res.string.date_month_january), stringResource(Res.string.date_month_february),
        stringResource(Res.string.date_month_march), stringResource(Res.string.date_month_april),
        stringResource(Res.string.date_month_may), stringResource(Res.string.date_month_june),
        stringResource(Res.string.date_month_july), stringResource(Res.string.date_month_august),
        stringResource(Res.string.date_month_september), stringResource(Res.string.date_month_october),
        stringResource(Res.string.date_month_november), stringResource(Res.string.date_month_december),
    )
    val dateTemplate = stringResource(Res.string.journal_date_format)
    val monthTemplate = stringResource(Res.string.journal_month_header)
    return remember(months, dateTemplate, monthTemplate) {
        JournalDateFormatter(
            months = months,
            dateFormat = { day, month, year ->
                dateTemplate.replace("%1\$d", day.toString()).replace("%2\$s", month).replace("%3\$d", year.toString())
            },
            monthFormat = { month, year -> monthTemplate.replace("%1\$s", month).replace("%2\$d", year.toString()) },
        )
    }
}

private fun localIsoDate(epochMs: Long): String? =
    runCatching { EpisodeReleaseDatePlatform.localIsoDateAtEpochMs(epochMs) }.getOrNull()

private fun formatRating(rating: Double): String =
    if (rating % 1.0 == 0.0) rating.toInt().toString() else rating.toString()

/** Hours with one decimal under 10 h, whole hours above. */
private fun formatHours(minutes: Int, decimalSeparator: String): String {
    if (minutes >= 600) return ((minutes + 30) / 60).toString()
    val tenths = (minutes * 10 + 30) / 60
    return "${tenths / 10}$decimalSeparator${tenths % 10}"
}

private val LetterboxdGreen = Color(0xFF00C030)
private val LetterboxdOrange = Color(0xFFFF8000)
