package codes.t3.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.WrapText
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import codes.t3.android.BuildConfig
import codes.t3.android.data.AppSettings
import codes.t3.android.data.ColorTheme
import codes.t3.android.data.FollowUpBehavior
import codes.t3.android.ui.components.SectionHeader
import codes.t3.android.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    settings: AppSettings,
    environmentCount: Int,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onOpenEnvironments: () -> Unit,
    onOpenArchive: () -> Unit,
    onBack: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp + padding.calculateBottomPadding()), modifier = Modifier.padding(top = padding.calculateTopPadding())) {
            group("Connections") {
                NavRow(Icons.Rounded.Computer, "Environments", "$environmentCount saved", onOpenEnvironments)
            }
            group("Appearance") {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Contrast, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        val modes = listOf(
                            Triple(ThemeMode.System, "System", Icons.Rounded.SettingsBrightness),
                            Triple(ThemeMode.Light, "Light", Icons.Rounded.LightMode),
                            Triple(ThemeMode.Dark, "Dark", Icons.Rounded.DarkMode),
                        )
                        modes.forEachIndexed { index, (mode, label, icon) ->
                            ToggleButton(
                                checked = settings.themeMode == mode,
                                onCheckedChange = { onUpdate { s -> s.copy(themeMode = mode) } },
                                modifier = Modifier.weight(1f),
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                            ) {
                                Icon(icon, null, Modifier.padding(end = 6.dp))
                                Text(label)
                            }
                        }
                    }
                }
                ChoiceRow(
                    "Material You",
                    "Colors from your wallpaper",
                    selected = settings.colorTheme == ColorTheme.MaterialYou,
                ) { onUpdate { it.copy(colorTheme = ColorTheme.MaterialYou) } }
                ChoiceRow(
                    "T3 Code",
                    "The classic T3 blue on neutral surfaces",
                    selected = settings.colorTheme == ColorTheme.T3,
                ) { onUpdate { it.copy(colorTheme = ColorTheme.T3) } }
                SwitchRow(Icons.Rounded.Palette, "Pure black", "Use true black in dark mode", settings.pureBlack) { v ->
                    onUpdate { it.copy(pureBlack = v) }
                }
                SwitchRow(Icons.Rounded.WrapText, "Wrap code blocks", "Soft-wrap long lines instead of scrolling", settings.wrapCode) { v ->
                    onUpdate { it.copy(wrapCode = v) }
                }
            }
            group("Composer") {
                SwitchRow(Icons.Rounded.KeyboardReturn, "Enter sends", "Return sends the message; Shift+Return adds a line", settings.enterToSend) { v ->
                    onUpdate { it.copy(enterToSend = v) }
                }
            }
            group("While the agent is running") {
                ChoiceRow(
                    "Queue",
                    "Your message waits and runs after the current turn finishes.",
                    selected = settings.followUp == FollowUpBehavior.Queue,
                ) { onUpdate { it.copy(followUp = FollowUpBehavior.Queue) } }
                ChoiceRow(
                    "Steer",
                    "Your message reaches the agent right away, changing what it is working on.",
                    selected = settings.followUp == FollowUpBehavior.Steer,
                ) { onUpdate { it.copy(followUp = FollowUpBehavior.Steer) } }
            }
            group("Threads") {
                SwitchRow(Icons.Rounded.Forum, "Show settled threads", "List settled threads below active ones on Home", settings.showSettled) { v ->
                    onUpdate { it.copy(showSettled = v) }
                }
                NavRow(Icons.Rounded.Archive, "Archived threads", null, onOpenArchive)
            }
            group("Legacy") {
                SwitchRow(
                    Icons.Rounded.Lightbulb,
                    "Plan mode",
                    "Show the Plan/Build toggle in the composer. You can always type /plan or /default instead.",
                    settings.legacyPlanMode,
                ) { v -> onUpdate { it.copy(legacyPlanMode = v) } }
            }
            group("About") {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    headlineContent = { Text("T3 Code for Android") },
                    supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · native client for self-hosted T3 Code servers") },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        }
    }
}

/** A titled, rounded group of rows. */
fun LazyListScope.group(title: String, content: @Composable () -> Unit) {
    item(key = "h-$title") { SectionHeader(title) }
    item(key = "g-$title") {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) { Column { content() } }
    }
}

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
fun NavRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        colors = rowColors,
    )
}

@Composable
fun SwitchRow(icon: ImageVector?, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.selectable(checked, role = Role.Switch) { onChange(!checked) },
        leadingContent = icon?.let { { Icon(it, null) } },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = rowColors,
    )
}

@Composable
fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = rowColors,
    )
}
