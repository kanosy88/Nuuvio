package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.letterboxd.LetterboxdFriends
import com.nuvio.app.features.letterboxd.LetterboxdFriendsRefreshMode
import com.nuvio.app.features.letterboxd.LetterboxdFriendsSettings
import com.nuvio.app.features.settings.SettingsChipRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSliderRow
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Settings › Letterboxd › "Recently rated by friends" (home row). */
internal fun LazyListScope.letterboxdFriendsSettingsContent(isTablet: Boolean) {
    item(key = "letterboxd_friends_settings") {
        val feed = LetterboxdFriends.feed
        val state by feed.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { feed.load() }
        val settings = state.settings
        val update = feed::updateSettings
        SettingsSection(title = stringResource(Res.string.letterboxd_section_friends), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.letterboxd_friends_setting_enabled),
                    description = stringResource(Res.string.letterboxd_friends_setting_enabled_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { value -> update { it.copy(enabled = value) } },
                )
                if (settings.enabled) FriendsOptions(settings, isTablet, update)
            }
        }
    }
}

@Composable
private fun FriendsOptions(
    settings: LetterboxdFriendsSettings,
    isTablet: Boolean,
    update: ((LetterboxdFriendsSettings) -> LetterboxdFriendsSettings) -> Unit,
) {
    SettingsGroupDivider(isTablet = isTablet)
    SettingsChipRow(
        title = stringResource(Res.string.letterboxd_friends_setting_types),
        description = stringResource(Res.string.letterboxd_friends_setting_types_description),
        isTablet = isTablet,
    ) {
        Chip(
            label = stringResource(Res.string.letterboxd_friends_setting_ratings),
            selected = settings.includeRatings,
            onClick = { update { it.copy(includeRatings = !it.includeRatings) } },
        )
        Chip(
            label = stringResource(Res.string.letterboxd_friends_setting_watches),
            selected = settings.includeWatches,
            onClick = { update { it.copy(includeWatches = !it.includeWatches) } },
        )
        Chip(
            label = stringResource(Res.string.letterboxd_friends_setting_reviews),
            selected = settings.includeReviews,
            onClick = { update { it.copy(includeReviews = !it.includeReviews) } },
        )
        Chip(
            label = stringResource(Res.string.letterboxd_friends_setting_likes),
            selected = settings.includeLikes,
            onClick = { update { it.copy(includeLikes = !it.includeLikes) } },
        )
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSliderRow(
        title = stringResource(Res.string.letterboxd_friends_setting_max),
        value = settings.maxPosters,
        valueText = { stringResource(Res.string.letterboxd_friends_setting_max_value, it) },
        valueRange = LetterboxdFriendsSettings.MaxPostersRange,
        step = 5,
        isTablet = isTablet,
        onValueChange = { value -> update { it.copy(maxPosters = value) } },
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsChipRow(
        title = stringResource(Res.string.letterboxd_friends_setting_period),
        description = stringResource(Res.string.letterboxd_friends_setting_period_description),
        isTablet = isTablet,
    ) {
        LetterboxdFriendsSettings.PeriodOptions.forEach { days ->
            Chip(
                label = stringResource(Res.string.letterboxd_friends_setting_period_value, days),
                selected = settings.periodDays == days,
                onClick = { update { it.copy(periodDays = days) } },
            )
        }
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.letterboxd_friends_setting_group),
        description = stringResource(Res.string.letterboxd_friends_setting_group_description),
        checked = settings.groupByFilm,
        isTablet = isTablet,
        onCheckedChange = { value -> update { it.copy(groupByFilm = value) } },
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsChipRow(
        title = stringResource(Res.string.letterboxd_friends_setting_min_rating),
        description = stringResource(Res.string.letterboxd_friends_setting_min_rating_description),
        isTablet = isTablet,
    ) {
        LetterboxdFriendsSettings.MinRatingOptions.forEach { min ->
            Chip(
                label = min?.let(::ratingStars) ?: stringResource(Res.string.letterboxd_friends_setting_min_rating_none),
                selected = settings.minRating == min,
                onClick = { update { it.copy(minRating = min) } },
            )
        }
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.letterboxd_friends_setting_hide_watched),
        description = stringResource(Res.string.letterboxd_friends_setting_hide_watched_description),
        checked = settings.hideWatchedByMe,
        isTablet = isTablet,
        onCheckedChange = { value -> update { it.copy(hideWatchedByMe = value) } },
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsChipRow(
        title = stringResource(Res.string.letterboxd_friends_setting_refresh),
        description = stringResource(Res.string.letterboxd_friends_setting_refresh_description),
        isTablet = isTablet,
    ) {
        LetterboxdFriendsRefreshMode.entries.forEach { mode ->
            Chip(
                label = stringResource(
                    when (mode) {
                        LetterboxdFriendsRefreshMode.OnLaunch -> Res.string.letterboxd_friends_refresh_on_launch
                        LetterboxdFriendsRefreshMode.EveryVisit -> Res.string.letterboxd_friends_refresh_every_visit
                        LetterboxdFriendsRefreshMode.EveryVisitWithDelay -> Res.string.letterboxd_friends_refresh_with_delay
                    },
                ),
                selected = settings.refreshMode == mode,
                onClick = { update { it.copy(refreshMode = mode) } },
            )
        }
    }
    if (settings.refreshMode == LetterboxdFriendsRefreshMode.EveryVisitWithDelay) {
        SettingsSliderRow(
            title = stringResource(Res.string.letterboxd_friends_setting_delay),
            value = settings.refreshDelayMinutes,
            valueText = { stringResource(Res.string.letterboxd_friends_setting_delay_value, it) },
            valueRange = LetterboxdFriendsSettings.RefreshDelayRange,
            step = 5,
            isTablet = isTablet,
            onValueChange = { value -> update { it.copy(refreshDelayMinutes = value) } },
        )
    }
    Text(
        text = stringResource(Res.string.letterboxd_friends_setting_note),
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
