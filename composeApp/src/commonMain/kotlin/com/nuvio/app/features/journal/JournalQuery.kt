package com.nuvio.app.features.journal

enum class JournalTypeFilter { All, Movies, Series }

enum class JournalStatusFilter { All, InProgress, Watched }

enum class JournalSourceFilter { All, Nuvio, Letterboxd }

/** Based on the last viewing date. */
enum class JournalPeriod { All, Last7Days, Last30Days, Last3Months, Last12Months, ThisYear }

enum class JournalSort { Date, Title, Rating, Year }

data class JournalQuery(
    val type: JournalTypeFilter = JournalTypeFilter.All,
    val status: JournalStatusFilter = JournalStatusFilter.All,
    val source: JournalSourceFilter = JournalSourceFilter.All,
    val genre: String? = null,
    val year: Int? = null,
    val period: JournalPeriod = JournalPeriod.All,
    val sort: JournalSort = JournalSort.Date,
    val search: String = "",
)

/** Month section of the list (sorted by date); [yearMonth] is `yyyy-MM`, null for entries without a date. */
data class JournalGroup(
    val yearMonth: String?,
    val entries: List<JournalEntry>,
)

data class JournalStats(
    val entries: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val episodes: Int = 0,
    val minutes: Int = 0,
)

private const val DayMs = 24L * 60 * 60 * 1000

/**
 * Filters, searches and sorts [entries]. [localDate] maps an epoch to the local `yyyy-MM-dd` (for "this year").
 */
fun applyJournalQuery(
    entries: List<JournalEntry>,
    query: JournalQuery,
    nowMs: Long,
    localDate: (Long) -> String?,
): List<JournalEntry> {
    val search = normalizeForSearch(query.search.trim())
    val currentYear = localDate(nowMs)?.take(4)
    val filtered = entries.filter { entry ->
        when (query.type) {
            JournalTypeFilter.All -> true
            JournalTypeFilter.Movies -> entry.kind == JournalKind.Movie
            JournalTypeFilter.Series -> entry.kind == JournalKind.Series
        } && when (query.status) {
            JournalStatusFilter.All -> true
            JournalStatusFilter.InProgress -> entry.status == JournalStatus.InProgress
            JournalStatusFilter.Watched -> entry.status == JournalStatus.Watched
        } && when (query.source) {
            JournalSourceFilter.All -> true
            JournalSourceFilter.Nuvio -> JournalSource.Nuvio in entry.sources
            JournalSourceFilter.Letterboxd -> JournalSource.Letterboxd in entry.sources
        } && (query.genre == null || entry.genres.any { it.equals(query.genre, ignoreCase = true) }) &&
            (query.year == null || entry.year == query.year) &&
            matchesPeriod(entry.lastWatchedAt, query.period, nowMs, currentYear, localDate) &&
            (search.isEmpty() || normalizeForSearch(entry.title).contains(search))
    }
    val byTitle = compareBy<JournalEntry> { normalizeForSearch(it.title) }
    val byDateDesc = compareByDescending<JournalEntry, Long?>(nullsFirst()) { it.lastWatchedAt }
    return when (query.sort) {
        JournalSort.Date -> filtered.sortedWith(byDateDesc.then(byTitle))
        JournalSort.Title -> filtered.sortedWith(byTitle)
        JournalSort.Rating -> filtered.sortedWith(
            compareByDescending<JournalEntry, Double?>(nullsFirst()) { it.rating }.then(byDateDesc).then(byTitle),
        )
        JournalSort.Year -> filtered.sortedWith(
            compareByDescending<JournalEntry, Int?>(nullsFirst()) { it.year }.then(byTitle),
        )
    }
}

private fun matchesPeriod(
    date: Long?,
    period: JournalPeriod,
    nowMs: Long,
    currentYear: String?,
    localDate: (Long) -> String?,
): Boolean {
    if (period == JournalPeriod.All) return true
    if (date == null) return false
    return when (period) {
        JournalPeriod.All -> true
        JournalPeriod.Last7Days -> date >= nowMs - 7 * DayMs
        JournalPeriod.Last30Days -> date >= nowMs - 30 * DayMs
        JournalPeriod.Last3Months -> date >= nowMs - 91 * DayMs
        JournalPeriod.Last12Months -> date >= nowMs - 365 * DayMs
        JournalPeriod.ThisYear -> currentYear != null && localDate(date)?.take(4) == currentYear
    }
}

/** Month sections in list order (entries without a date last). Only meaningful when sorted by date. */
fun groupJournalByMonth(entries: List<JournalEntry>, localDate: (Long) -> String?): List<JournalGroup> {
    val groups = LinkedHashMap<String?, MutableList<JournalEntry>>()
    entries.forEach { entry ->
        val month = entry.lastWatchedAt?.let(localDate)?.take(7)
        groups.getOrPut(month) { mutableListOf() } += entry
    }
    val dated = groups.filterKeys { it != null }.map { (month, list) -> JournalGroup(month, list) }
    val undated = groups[null]?.let { listOf(JournalGroup(null, it)) }.orEmpty()
    return dated + undated
}

fun journalStats(entries: List<JournalEntry>): JournalStats = JournalStats(
    entries = entries.size,
    movies = entries.count { it.kind == JournalKind.Movie },
    series = entries.count { it.kind == JournalKind.Series },
    episodes = entries.filter { it.kind == JournalKind.Series }.sumOf { it.episodesWatched },
    minutes = entries.sumOf { it.watchedMinutes },
)

/** Genres and years present in [entries], for the filter menus. */
fun journalGenres(entries: List<JournalEntry>): List<String> =
    entries.flatMap { it.genres }.map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }.sortedBy { normalizeForSearch(it) }

fun journalYears(entries: List<JournalEntry>): List<Int> = entries.mapNotNull { it.year }.distinct().sortedDescending()
