package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.letterboxd.LetterboxdRateLimitSettings
import com.nuvio.app.features.letterboxd.LetterboxdRateLimits
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSliderRow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Settings › Letterboxd › "Network / rate limits": the delays and limits of the module, with their defaults. */
internal fun LazyListScope.letterboxdRateLimitsSettingsContent(isTablet: Boolean) {
    item(key = "letterboxd_rate_limits_settings") {
        val settings by LetterboxdRateLimitSettings.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { LetterboxdRateLimitSettings.ensureLoaded() }
        val scope = rememberCoroutineScope()
        val update: ((LetterboxdRateLimits) -> LetterboxdRateLimits) -> Unit = { transform ->
            scope.launch { LetterboxdRateLimitSettings.update(transform) }
        }
        val decimalSeparator = stringResource(Res.string.letterboxd_rl_decimal_separator)
        val seconds: @Composable (Int) -> String = { ms ->
            stringResource(Res.string.letterboxd_rl_value_seconds, formatSeconds(ms, decimalSeparator))
        }
        val pages: @Composable (Int) -> String = { stringResource(Res.string.letterboxd_rl_value_pages, it) }
        val films: @Composable (Int) -> String = { stringResource(Res.string.letterboxd_rl_value_films, it) }
        val minutes: @Composable (Int) -> String = { stringResource(Res.string.letterboxd_rl_value_minutes, it) }
        val off = stringResource(Res.string.letterboxd_rl_value_off)

        SettingsSection(title = stringResource(Res.string.letterboxd_section_rate_limits), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                RateLimitHelp(stringResource(Res.string.letterboxd_rl_help), isTablet)
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_request_gap),
                    value = settings.requestGapMs,
                    valueRange = LetterboxdRateLimits.RequestGapRangeMs,
                    step = 50,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(requestGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_sync_read_gap),
                    value = settings.syncReadGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(syncReadGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_sync_write_gap),
                    value = settings.syncWriteGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(syncWriteGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_friends_pages),
                    value = settings.friendsMaxPages,
                    valueRange = LetterboxdRateLimits.FriendsMaxPagesRange,
                    step = 1,
                    valueText = pages,
                    isTablet = isTablet,
                ) { value -> update { it.copy(friendsMaxPages = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_friends_page_gap),
                    value = settings.friendsPageGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(friendsPageGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_friends_batch),
                    value = settings.friendsResolveBatch,
                    valueRange = LetterboxdRateLimits.FriendsResolveBatchRange,
                    step = 1,
                    valueText = films,
                    isTablet = isTablet,
                ) { value -> update { it.copy(friendsResolveBatch = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_friends_resolve_gap),
                    value = settings.friendsResolveGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(friendsResolveGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_friends_min_auto),
                    value = settings.friendsMinAutoRefreshSec,
                    valueRange = LetterboxdRateLimits.MinAutoRefreshRangeSec,
                    step = 10,
                    valueText = { seconds(it * 1_000) },
                    isTablet = isTablet,
                ) { value -> update { it.copy(friendsMinAutoRefreshSec = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_own_diary),
                    value = settings.ownDiaryCacheMin,
                    valueRange = LetterboxdRateLimits.OwnDiaryCacheRangeMin,
                    step = 30,
                    valueText = minutes,
                    isTablet = isTablet,
                ) { value -> update { it.copy(ownDiaryCacheMin = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_personal_cache),
                    value = settings.personalCacheSec,
                    valueRange = LetterboxdRateLimits.PersonalCacheRangeSec,
                    step = 10,
                    valueText = { if (it == 0) off else seconds(it * 1_000) },
                    isTablet = isTablet,
                ) { value -> update { it.copy(personalCacheSec = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_journal_pages),
                    value = settings.journalMaxPages,
                    valueRange = LetterboxdRateLimits.JournalMaxPagesRange,
                    step = 1,
                    valueText = pages,
                    isTablet = isTablet,
                ) { value -> update { it.copy(journalMaxPages = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_journal_page_gap),
                    value = settings.journalPageGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(journalPageGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_journal_batch),
                    value = settings.journalResolveBatch,
                    valueRange = LetterboxdRateLimits.FriendsResolveBatchRange,
                    step = 1,
                    valueText = films,
                    isTablet = isTablet,
                ) { value -> update { it.copy(journalResolveBatch = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_journal_resolve_gap),
                    value = settings.journalResolveGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(journalResolveGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_journal_min_refresh),
                    value = settings.journalMinRefreshMin,
                    valueRange = LetterboxdRateLimits.JournalMinRefreshRangeMin,
                    step = 5,
                    valueText = minutes,
                    isTablet = isTablet,
                ) { value -> update { it.copy(journalMinRefreshMin = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_lists_page_gap),
                    value = settings.listsPageGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(listsPageGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_lists_batch),
                    value = settings.listsResolveBatch,
                    valueRange = LetterboxdRateLimits.FriendsResolveBatchRange,
                    step = 1,
                    valueText = films,
                    isTablet = isTablet,
                ) { value -> update { it.copy(listsResolveBatch = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_lists_resolve_gap),
                    value = settings.listsResolveGapMs,
                    valueRange = LetterboxdRateLimits.BulkGapRangeMs,
                    step = 500,
                    valueText = seconds,
                    isTablet = isTablet,
                ) { value -> update { it.copy(listsResolveGapMs = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                RateLimitSlider(
                    title = stringResource(Res.string.letterboxd_rl_lists_min_auto),
                    value = settings.listsMinAutoRefreshSec,
                    valueRange = LetterboxdRateLimits.MinAutoRefreshRangeSec,
                    step = 10,
                    valueText = { seconds(it * 1_000) },
                    isTablet = isTablet,
                ) { value -> update { it.copy(listsMinAutoRefreshSec = value) } }
                SettingsGroupDivider(isTablet = isTablet)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { scope.launch { LetterboxdRateLimitSettings.restoreDefaults() } },
                        enabled = settings != LetterboxdRateLimits(),
                    ) {
                        Text(stringResource(Res.string.letterboxd_rl_restore))
                    }
                }
            }
        }
    }
}

@Composable
private fun RateLimitSlider(
    title: String,
    value: Int,
    valueRange: IntRange,
    step: Int,
    valueText: @Composable (Int) -> String,
    isTablet: Boolean,
    onValueChange: (Int) -> Unit,
) {
    SettingsSliderRow(
        title = title,
        value = value,
        valueText = valueText,
        valueRange = valueRange,
        step = step,
        isTablet = isTablet,
        onValueChange = onValueChange,
    )
}

@Composable
private fun RateLimitHelp(text: String, isTablet: Boolean) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Milliseconds as seconds without trailing zeros: 1500 → "1.5", 350 → "0.35", 2000 → "2". */
private fun formatSeconds(ms: Int, decimalSeparator: String): String {
    val whole = ms / 1_000
    val fraction = (ms % 1_000).toString().padStart(3, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$whole" else "$whole$decimalSeparator$fraction"
}
