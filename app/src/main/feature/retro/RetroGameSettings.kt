package com.winlator.cmod.feature.retro

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.winlator.cmod.feature.library.GameSettingsNav
import com.winlator.cmod.feature.shortcuts.LibraryShortcutArtwork
import com.winlator.cmod.runtime.container.Shortcut
import java.io.File
import com.winlator.cmod.shared.ui.nav.LocalPaneNav
import com.winlator.cmod.shared.ui.nav.PaneNavRegistry
import com.winlator.cmod.shared.ui.nav.paneHighlight
import com.winlator.cmod.shared.ui.nav.paneNavItem
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.widget.chasingBorder

private val BgDeep = Color(0xFF11111C)
private val SidebarBg = Color(0xFF11111C)
private val ContentBg = Color(0xFF11111C)
private val CardSurface = Color(0xFF1C1C2A)
private val CardBorder = Color(0xFF2A2A3A)
private val InputSurface = Color(0xFF171722)
private val InputBorder = Color(0xFF2A2A3A)
private val AccentBlue = Color(0xFF1A9FFF)
private val TextPrimary = Color(0xFFF0F4FF)
private val TextSecondary = Color(0xFF7A8FA8)
private val TextDim = Color(0xFF6E7681)
private val DividerColor = Color(0xFF2A2A3A)
private val NavHighlight = Color(0xFF4FC3F7)
private val DangerRed = Color(0xFFFF6B6B)

private val LabelSize = 11.sp
private val ValueSize = 12.sp
private val GroupCorner = 12.dp
private val GroupPadding = 12.dp
private val FieldCorner = 8.dp
private val ItemGap = 10.dp
private val TightGap = 4.dp

private val SHADER_KEYS = listOf("default", "crt", "lcd", "sharp")
private val SHADER_LABELS = listOf("Default", "CRT", "LCD", "Sharp")

class RetroSettingsState(
    val shortcut: Shortcut,
) {
    val system: RetroSystem? = RetroShortcuts.systemForShortcut(shortcut)
    val coreOptions: List<RetroCoreOption> = RetroCoreOptions.forSystem(system)
    val name: String = shortcut.getExtra("custom_name", shortcut.name)
    val romPath: String = shortcut.getExtra(RetroShortcuts.KEY_ROM)

    var shader by mutableStateOf(
        shortcut.getExtra(RetroShortcuts.KEY_SHADER, "default").lowercase().let {
            if (it in SHADER_KEYS) it else "default"
        },
    )
    var touchControls by mutableStateOf(shortcut.getExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, "1") != "0")
    var audio by mutableStateOf(shortcut.getExtra(RetroShortcuts.KEY_AUDIO, "1") != "0")
    val optionValues =
        mutableStateMapOf<String, String>().apply {
            coreOptions.forEach { option ->
                put(
                    option.key,
                    shortcut.getExtra(RetroShortcuts.VAR_PREFIX + option.key).ifEmpty { option.defaultValue },
                )
            }
        }
    val artworkSelected = mutableStateMapOf<LibraryShortcutArtwork.LibraryArtworkSlot, Boolean>()
    var currentSection by mutableIntStateOf(0)

    init {
        syncArtwork()
    }

    fun syncArtwork() {
        LibraryShortcutArtwork.LibraryArtworkSlot.entries.forEach { slot ->
            val path = shortcut.getExtra(slot.extraKey)
            artworkSelected[slot] = path.isNotBlank() && File(path).isFile
        }
    }

    fun save() {
        shortcut.putExtra(RetroShortcuts.KEY_SHADER, shader)
        shortcut.putExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (touchControls) "1" else "0")
        shortcut.putExtra(RetroShortcuts.KEY_AUDIO, if (audio) "1" else "0")
        coreOptions.forEach { option ->
            shortcut.putExtra(
                RetroShortcuts.VAR_PREFIX + option.key,
                optionValues[option.key] ?: option.defaultValue,
            )
        }
        shortcut.saveData()
    }
}

private data class RetroSection(
    val icon: ImageVector,
    val label: String,
)

private fun buildRetroSections(state: RetroSettingsState): List<RetroSection> {
    val sections = mutableListOf<RetroSection>()
    sections += RetroSection(Icons.Outlined.Tune, "General")
    sections += RetroSection(Icons.Outlined.Monitor, "Graphics")
    if (state.coreOptions.isNotEmpty()) {
        sections += RetroSection(Icons.Outlined.Memory, "${state.system?.shortName ?: "Core"} Options")
    }
    sections += RetroSection(Icons.Outlined.SportsEsports, "Input")
    sections += RetroSection(Icons.AutoMirrored.Outlined.VolumeUp, "Audio")
    return sections
}

@Composable
fun RetroGameSettingsContent(
    state: RetroSettingsState,
    nav: GameSettingsNav? = null,
    onPickArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onRemoveArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val sections = remember(state) { buildRetroSections(state) }
    val hasSystemSection = state.coreOptions.isNotEmpty()
    val selectedIdx = state.currentSection

    if (nav != null) {
        SideEffect {
            nav.sidebarCount = sections.size
            nav.onSelectSection = { state.currentSection = it }
            nav.onSave = onSave
            nav.onCancel = onCancel
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(BgDeep),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            RetroSidebar(
                title = state.name,
                subtitle = state.system?.displayName ?: "",
                sections = sections,
                currentIndex = selectedIdx,
                onSectionSelected = { state.currentSection = it },
                onSave = onSave,
                onCancel = onCancel,
                nav = nav,
                modifier = Modifier.width(220.dp).fillMaxHeight(),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(DividerColor))
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(ContentBg),
            ) {
                RetroSectionContent(
                    sectionIndex = selectedIdx,
                    hasSystemSection = hasSystemSection,
                    state = state,
                    nav = nav,
                    onPickArtwork = onPickArtwork,
                    onRemoveArtwork = onRemoveArtwork,
                )
            }
        }
    }
}

@Composable
private fun RetroSectionContent(
    sectionIndex: Int,
    hasSystemSection: Boolean,
    state: RetroSettingsState,
    nav: GameSettingsNav? = null,
    onPickArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onRemoveArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
) {
    AnimatedContent(
        targetState = sectionIndex,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (
                slideInHorizontally(animationSpec = tween(220)) { direction * it / 6 } +
                    fadeIn(tween(200))
            ).togetherWith(
                slideOutHorizontally(animationSpec = tween(180)) { -direction * it / 6 } +
                    fadeOut(tween(120)),
            )
        },
        label = "RetroSectionTransition",
    ) { idx ->
        val contentNav = remember(nav) { nav?.let { PaneNavRegistry(initialSignal = it.contentSignal) } }
        val isCurrent = idx == sectionIndex
        if (nav != null && contentNav != null) {
            contentNav.controllerActive = nav.active && nav.inContent && isCurrent
            contentNav.onEdgeLeft = { nav.exitToSidebar() }
            if (isCurrent) {
                nav.onContentBack = {
                    if (contentNav.overlay != null) {
                        contentNav.overlayClose?.invoke()
                        true
                    } else {
                        false
                    }
                }
            }
            LaunchedEffect(nav.contentSignal) {
                if (isCurrent) contentNav.processNav(nav.contentSignal, nav.contentDir)
            }
            LaunchedEffect(nav.contentResetSignal) {
                if (isCurrent) contentNav.reset()
            }
        }
        val sectionBody: @Composable () -> Unit = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                val systemIdx = 2
                when {
                    idx == 0 -> RetroGeneralSection(state, onPickArtwork, onRemoveArtwork)
                    idx == 1 -> RetroGraphicsSection(state)
                    hasSystemSection && idx == systemIdx -> RetroSystemSection(state)
                    idx == (if (hasSystemSection) 3 else 2) -> RetroInputSection(state)
                    else -> RetroAudioSection(state)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (contentNav != null) {
            CompositionLocalProvider(LocalPaneNav provides contentNav) { sectionBody() }
        } else {
            sectionBody()
        }
    }
}

@Composable
private fun RetroSidebar(
    title: String,
    subtitle: String,
    sections: List<RetroSection>,
    currentIndex: Int,
    onSectionSelected: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    nav: GameSettingsNav? = null,
    modifier: Modifier = Modifier,
) {
    val cancelHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 0
    val saveHighlighted = nav != null && nav.active && !nav.inContent && nav.onActionRow && nav.actionCol == 1
    Column(
        modifier =
            modifier
                .background(SidebarBg)
                .padding(top = 14.dp, bottom = 12.dp),
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = LabelSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 2.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DividerColor),
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            sections.forEachIndexed { index, section ->
                RetroSidebarItem(
                    icon = section.icon,
                    label = section.label,
                    isSelected = currentIndex == index,
                    navHighlighted =
                        nav != null && nav.active && !nav.inContent && !nav.onActionRow &&
                            nav.sidebarIndex == index,
                    onClick = {
                        if (nav != null) nav.tapSection(index) else onSectionSelected(index)
                    },
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DividerColor),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                        .background(CardSurface)
                        .paneHighlight(cancelHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                        .clickable {
                            nav?.tapAction(0)
                            onCancel()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", color = TextSecondary, fontSize = LabelSize, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, AccentBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .background(AccentBlue.copy(alpha = 0.1f))
                        .paneHighlight(saveHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                        .clickable {
                            nav?.tapAction(1)
                            onSave()
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text("Save", color = AccentBlue, fontSize = LabelSize, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RetroSidebarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    navHighlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .chasingBorder(isFocused = isSelected, cornerRadius = 8.dp, borderWidth = 2.dp)
                .paneHighlight(navHighlighted, cornerRadius = 8.dp, highlightColor = NavHighlight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) AccentBlue else TextDim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = if (isSelected) TextPrimary else TextSecondary,
                fontSize = ValueSize,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RetroSettingGroup(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(GroupCorner))
                .background(CardSurface)
                .border(1.dp, CardBorder, RoundedCornerShape(GroupCorner))
                .padding(GroupPadding),
    ) {
        content()
    }
}

@Composable
private fun RetroGroupTitle(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = LabelSize,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(bottom = ItemGap),
    )
}

@Composable
private fun RetroInfoRow(
    label: String,
    value: String,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = ItemGap)) {
        Text(
            label,
            color = TextSecondary,
            fontSize = LabelSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(bottom = TightGap),
        )
        Text(
            value.ifBlank { "-" },
            color = TextPrimary,
            fontSize = ValueSize,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RetroSettingDropdown(
    label: String,
    entries: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedText = entries.getOrElse(selectedIndex) { "" }
    val parentNav = LocalPaneNav.current
    val optionRegistry = remember { PaneNavRegistry() }
    LaunchedEffect(expanded) {
        if (expanded) {
            optionRegistry.reset()
            optionRegistry.controllerActive = true
            parentNav?.overlay = optionRegistry
            parentNav?.overlayClose = { expanded = false }
        } else if (parentNav?.overlay === optionRegistry) {
            parentNav.overlay = null
            parentNav.overlayClose = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = ItemGap)) {
        Text(
            label,
            color = TextSecondary,
            fontSize = LabelSize,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(bottom = TightGap),
        )
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FieldCorner))
                        .background(InputSurface)
                        .border(1.dp, InputBorder, RoundedCornerShape(FieldCorner))
                        .paneNavItem(
                            cornerRadius = FieldCorner,
                            onActivate = { expanded = true },
                            highlightColor = NavHighlight,
                        )
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedText,
                    color = TextPrimary,
                    fontSize = ValueSize,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(8.dp),
                containerColor = CardSurface,
                properties = PopupProperties(focusable = false),
            ) {
                CompositionLocalProvider(LocalPaneNav provides optionRegistry) {
                    entries.forEachIndexed { index, entry ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    entry,
                                    color = if (index == selectedIndex) AccentBlue else TextPrimary,
                                    fontSize = ValueSize,
                                    fontWeight = if (index == selectedIndex) FontWeight.Medium else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onSelected(index)
                                expanded = false
                            },
                            modifier =
                                (
                                    if (index == selectedIndex) {
                                        Modifier.background(AccentBlue.copy(alpha = 0.06f))
                                    } else {
                                        Modifier
                                    }
                                ).paneNavItem(
                                    cornerRadius = 6.dp,
                                    onActivate = {
                                        onSelected(index)
                                        expanded = false
                                    },
                                    isEntry = index == selectedIndex,
                                    highlightColor = NavHighlight,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetroSettingSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onCheckedChange(!checked) }
                .paneNavItem(
                    cornerRadius = 8.dp,
                    onActivate = { onCheckedChange(!checked) },
                    highlightColor = NavHighlight,
                    tapToSelect = true,
                )
                .padding(vertical = TightGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = TextPrimary,
            fontSize = ValueSize,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = outlinedSwitchColors(accentColor = AccentBlue, textSecondaryColor = TextSecondary),
        )
    }
}

@Composable
private fun RetroGeneralSection(
    state: RetroSettingsState,
    onPickArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
    onRemoveArtwork: ((LibraryShortcutArtwork.LibraryArtworkSlot) -> Unit)? = null,
) {
    RetroSettingGroup {
        RetroGroupTitle("GAME")
        RetroInfoRow("Name", state.name)
        RetroInfoRow("System", state.system?.displayName ?: "")
        RetroInfoRow("Emulator Core", state.system?.coreFileName ?: "")
        RetroInfoRow("ROM Path", state.romPath)
    }
    if (onPickArtwork != null && onRemoveArtwork != null) {
        Spacer(Modifier.height(ItemGap))
        RetroSettingGroup {
            RetroGroupTitle("LIBRARY ARTWORK")
            Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = "Game Card Image",
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GAME_CARD) },
                    )
                }
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = "Grid Image",
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.GRID] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GRID) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.GRID) },
                    )
                }
            }
            Spacer(Modifier.height(ItemGap))
            Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = "Carousel Image",
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.CAROUSEL] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.CAROUSEL) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.CAROUSEL) },
                    )
                }
                Box(Modifier.weight(1f)) {
                    RetroArtworkRow(
                        title = "List Image",
                        selected = state.artworkSelected[LibraryShortcutArtwork.LibraryArtworkSlot.LIST] == true,
                        onPick = { onPickArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.LIST) },
                        onRemove = { onRemoveArtwork(LibraryShortcutArtwork.LibraryArtworkSlot.LIST) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroArtworkRow(
    title: String,
    selected: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(GroupCorner))
                .background(InputSurface)
                .border(1.dp, InputBorder, RoundedCornerShape(GroupCorner))
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = ValueSize,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selected) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Custom image set",
                        color = TextSecondary,
                        fontSize = LabelSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!selected) {
                    RetroArtworkActionButton("Set image", AccentBlue, onPick)
                } else {
                    RetroArtworkActionButton("Remove", DangerRed, onRemove)
                }
            }
        }
    }
}

@Composable
private fun RetroArtworkActionButton(
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.08f))
                .border(1.dp, tint.copy(alpha = 0.2f), RoundedCornerShape(9.dp))
                .paneNavItem(cornerRadius = 9.dp, onActivate = { onClick() }, highlightColor = NavHighlight)
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = LabelSize,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RetroGraphicsSection(state: RetroSettingsState) {
    RetroSettingGroup {
        RetroGroupTitle("VIDEO")
        RetroSettingDropdown(
            label = "Video Filter",
            entries = SHADER_LABELS,
            selectedIndex = SHADER_KEYS.indexOf(state.shader).coerceAtLeast(0),
            onSelected = { state.shader = SHADER_KEYS[it] },
        )
    }
}

@Composable
private fun RetroSystemSection(state: RetroSettingsState) {
    RetroSettingGroup {
        RetroGroupTitle((state.system?.shortName ?: "CORE").uppercase() + " OPTIONS")
        state.coreOptions.forEach { option ->
            val current = state.optionValues[option.key] ?: option.defaultValue
            RetroSettingDropdown(
                label = option.label,
                entries = option.valueLabels,
                selectedIndex = option.values.indexOf(current).coerceAtLeast(0),
                onSelected = { state.optionValues[option.key] = option.values[it] },
            )
        }
    }
}

@Composable
private fun RetroInputSection(state: RetroSettingsState) {
    RetroSettingGroup {
        RetroGroupTitle("INPUT")
        RetroSettingSwitch(
            label = "On-screen controls",
            checked = state.touchControls,
            onCheckedChange = { state.touchControls = it },
        )
    }
}

@Composable
private fun RetroAudioSection(state: RetroSettingsState) {
    RetroSettingGroup {
        RetroGroupTitle("AUDIO")
        RetroSettingSwitch(
            label = "Sound",
            checked = state.audio,
            onCheckedChange = { state.audio = it },
        )
    }
}
