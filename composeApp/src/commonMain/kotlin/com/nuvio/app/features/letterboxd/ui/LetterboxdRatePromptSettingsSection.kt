package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.journal.JournalRepository
import com.nuvio.app.features.letterboxd.LetterboxdRatePromptSettings
import com.nuvio.app.features.letterboxd.LetterboxdRatePromptSettingsStore
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSliderRow
import com.nuvio.app.features.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Settings › Letterboxd › "After a film" (F5 end-of-film prompt). */
internal fun LazyListScope.letterboxdRatePromptSettingsContent(isTablet: Boolean) {
    item(key = "letterboxd_rate_prompt_settings") {
        val scope = rememberCoroutineScope()
        val settings by LetterboxdRatePromptSettingsStore.state.collectAsStateWithLifecycle()
        val journal by JournalRepository.settings.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { LetterboxdRatePromptSettingsStore.ensureLoaded() }
        val update: ((LetterboxdRatePromptSettings) -> LetterboxdRatePromptSettings) -> Unit = { transform ->
            scope.launch { LetterboxdRatePromptSettingsStore.update(transform) }
        }
        val journalThreshold = journal.watchedThresholdPercent
        SettingsSection(title = stringResource(Res.string.letterboxd_section_rate_prompt), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.letterboxd_rate_prompt_setting_enabled),
                    description = stringResource(Res.string.letterboxd_rate_prompt_setting_enabled_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { value -> update { it.copy(enabled = value) } },
                )
                if (settings.enabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.letterboxd_rate_prompt_setting_follow_journal),
                        description = stringResource(Res.string.letterboxd_rate_prompt_setting_follow_journal_description, journalThreshold),
                        checked = settings.thresholdPercent == null,
                        isTablet = isTablet,
                        onCheckedChange = { follow -> update { it.copy(thresholdPercent = if (follow) null else journalThreshold) } },
                    )
                    if (settings.thresholdPercent != null) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSliderRow(
                            title = stringResource(Res.string.letterboxd_rate_prompt_setting_threshold),
                            value = settings.effectiveThreshold(journalThreshold),
                            valueText = { stringResource(Res.string.letterboxd_rate_prompt_setting_threshold_value, it) },
                            valueRange = LetterboxdRatePromptSettings.ThresholdRange,
                            step = 5,
                            isTablet = isTablet,
                            onValueChange = { value -> update { it.copy(thresholdPercent = value) } },
                        )
                    }
                }
            }
        }
    }
}
