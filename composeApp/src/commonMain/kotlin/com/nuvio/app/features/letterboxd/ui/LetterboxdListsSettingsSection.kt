package com.nuvio.app.features.letterboxd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.letterboxd.LetterboxdFriendsRefreshMode
import com.nuvio.app.features.letterboxd.LetterboxdFriendsSettings
import com.nuvio.app.features.letterboxd.LetterboxdListAddProblem
import com.nuvio.app.features.letterboxd.LetterboxdListConfig
import com.nuvio.app.features.letterboxd.LetterboxdLists
import com.nuvio.app.features.letterboxd.LetterboxdListsSettings
import com.nuvio.app.features.letterboxd.LetterboxdListsState
import com.nuvio.app.features.letterboxd.LetterboxdRepository
import com.nuvio.app.features.settings.SettingsChipRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSliderRow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Settings › Letterboxd › "Lists on Home": add lists (mine or by address), enable, order, remove; row options. */
internal fun LazyListScope.letterboxdListsSettingsContent(isTablet: Boolean) {
    item(key = "letterboxd_lists_settings") {
        val feed = LetterboxdLists.feed
        val state by feed.state.collectAsStateWithLifecycle()
        val account by LetterboxdRepository.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            LetterboxdRepository.ensureLoaded()
            feed.load()
        }
        val connected = account.hasCredentials
        SettingsSection(title = stringResource(Res.string.letterboxd_section_lists), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                Column(modifier = rowPadding(isTablet), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HelpText(stringResource(Res.string.letterboxd_lists_help))
                    AddByUrl(state)
                }
                SettingsGroupDivider(isTablet = isTablet)
                MyLists(state, connected, isTablet)
                if (state.settings.lists.isNotEmpty()) {
                    SettingsGroupDivider(isTablet = isTablet)
                    state.settings.lists.forEachIndexed { index, config ->
                        AddedListRow(
                            config = config,
                            filmCount = state.lists[config.key]?.totalFilms,
                            available = connected || !config.mine,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.settings.lists.lastIndex,
                            isTablet = isTablet,
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                RowOptions(state.settings, isTablet)
            }
        }
    }
}

@Composable
private fun AddByUrl(state: LetterboxdListsState) {
    val feed = LetterboxdLists.feed
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surface,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                feed.clearAddFeedback()
            },
            modifier = Modifier.weight(1f),
            enabled = !state.adding,
            singleLine = true,
            label = { Text(stringResource(Res.string.letterboxd_lists_url_label)) },
            placeholder = { Text("https://letterboxd.com/…/list/…/") },
            colors = fieldColors,
        )
        Button(
            onClick = {
                val input = url
                scope.launch { if (feed.addByUrl(input)) url = "" }
            },
            enabled = !state.adding && url.isNotBlank(),
        ) {
            Text(stringResource(Res.string.letterboxd_lists_add))
        }
    }
    if (state.adding) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            HelpText(stringResource(Res.string.letterboxd_lists_adding))
        }
    }
    val problem = state.addProblem?.let {
        stringResource(
            when (it) {
                LetterboxdListAddProblem.InvalidUrl -> Res.string.letterboxd_lists_invalid_url
                LetterboxdListAddProblem.AlreadyAdded -> Res.string.letterboxd_lists_already_added
                LetterboxdListAddProblem.Empty -> Res.string.letterboxd_lists_no_films
            },
        )
    } ?: state.addError?.let {
        stringResource(Res.string.letterboxd_lists_add_failed, stringResource(it.messageResource()))
    }
    if (problem != null) ErrorText(problem)
}

@Composable
private fun MyLists(state: LetterboxdListsState, connected: Boolean, isTablet: Boolean) {
    val feed = LetterboxdLists.feed
    val scope = rememberCoroutineScope()
    Column(modifier = rowPadding(isTablet), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.letterboxd_lists_mine),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.myListsLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                OutlinedButton(onClick = { feed.loadMyLists() }, enabled = connected && !state.adding) {
                    Text(stringResource(if (state.myLists == null) Res.string.letterboxd_lists_show_mine else Res.string.letterboxd_friends_refresh))
                }
            }
        }
        if (!connected) {
            HelpText(stringResource(Res.string.letterboxd_lists_mine_signed_out))
            return@Column
        }
        state.myListsError?.let { ErrorText(stringResource(Res.string.letterboxd_lists_error, stringResource(it.messageResource()))) }
        val mine = state.myLists ?: return@Column
        if (mine.isEmpty()) {
            HelpText(stringResource(Res.string.letterboxd_lists_mine_empty))
            return@Column
        }
        val added = state.settings.lists.mapTo(HashSet()) { it.key }
        mine.forEach { summary ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    summary.filmCount?.let { HelpText(stringResource(Res.string.letterboxd_lists_film_count, it)) }
                }
                if (summary.ref.key in added) {
                    HelpText(stringResource(Res.string.letterboxd_lists_added))
                } else {
                    TextButton(onClick = { scope.launch { feed.addMine(summary) } }, enabled = !state.adding) {
                        Text(stringResource(Res.string.letterboxd_lists_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddedListRow(
    config: LetterboxdListConfig,
    filmCount: Int?,
    available: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isTablet: Boolean,
) {
    val feed = LetterboxdLists.feed
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildList {
                add(stringResource(Res.string.letterboxd_lists_by, config.owner))
                filmCount?.let { add(stringResource(Res.string.letterboxd_lists_film_count, it)) }
                if (!available) add(stringResource(Res.string.letterboxd_lists_needs_account))
            }.joinToString(" · ")
            HelpText(details)
        }
        IconButton(onClick = { feed.move(config.key, -1) }, enabled = canMoveUp) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(Res.string.letterboxd_lists_move_up))
        }
        IconButton(onClick = { feed.move(config.key, 1) }, enabled = canMoveDown) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(Res.string.letterboxd_lists_move_down))
        }
        Switch(checked = config.enabled, onCheckedChange = { feed.setEnabled(config.key, it) })
        IconButton(onClick = { feed.remove(config.key) }) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.letterboxd_lists_remove),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RowOptions(settings: LetterboxdListsSettings, isTablet: Boolean) {
    val update = LetterboxdLists.feed::updateSettings
    SettingsSliderRow(
        title = stringResource(Res.string.letterboxd_lists_setting_posters),
        value = settings.postersPerRow,
        valueText = { stringResource(Res.string.letterboxd_friends_setting_max_value, it) },
        valueRange = LetterboxdListsSettings.PostersPerRowRange,
        step = 5,
        isTablet = isTablet,
        onValueChange = { value -> update { it.copy(postersPerRow = value) } },
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
        text = stringResource(Res.string.letterboxd_lists_setting_note),
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun rowPadding(isTablet: Boolean) =
    Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp)

@Composable
private fun HelpText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
