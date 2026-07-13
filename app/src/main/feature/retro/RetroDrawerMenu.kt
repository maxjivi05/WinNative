package com.winlator.cmod.feature.retro

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.shared.theme.WinNativeBackground
import com.winlator.cmod.shared.theme.WinNativeOutline
import com.winlator.cmod.shared.theme.WinNativePanel
import com.winlator.cmod.shared.theme.WinNativeSurface
import com.winlator.cmod.shared.theme.WinNativeTextPrimary
import com.winlator.cmod.shared.theme.WinNativeTextSecondary
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import com.winlator.cmod.shared.ui.widget.chasingBorder

private const val DrawerSheetAlpha = 0.86f
private const val DrawerSurfaceAlpha = 0.72f
private const val DrawerPressedAlpha = 0.88f
private const val DrawerGradientLift = 0.014f

private val DrawerAccent = Color(0xFF2196F3)
private val DrawerActiveAccent = Color(0xFF29B6F6)
private val DrawerFocusFill = Color(0xFF0E2438)
private val DrawerTextPrimary = WinNativeTextPrimary.copy(alpha = 0.88f)
private val DrawerTextSecondary = WinNativeTextSecondary.copy(alpha = 0.82f)
private val RetroSheetColor = WinNativeBackground.copy(alpha = DrawerSheetAlpha)
private val TopRailSurfaceColor = WinNativeSurface.copy(alpha = DrawerSheetAlpha)
private val PaneSurfacePressed = Color(0xFF232B3A).copy(alpha = DrawerPressedAlpha)
private val PaneInnerResting = WinNativePanel.copy(alpha = DrawerSurfaceAlpha)
private val PaneInnerPressed = Color(0xFF242B3A).copy(alpha = DrawerPressedAlpha)
private val TileExitResting = Color(0xFF3A2125).copy(alpha = DrawerSurfaceAlpha)
private val TileExitPressed = Color(0xFF4A2A30).copy(alpha = DrawerPressedAlpha)
private val RestingCardBorder = WinNativeOutline.copy(alpha = 0.72f)
private val ActiveCardBorder = DrawerActiveAccent
private val GlassExitTint = Color(0xFFE07B6B)
private val DividerColor = WinNativeOutline.copy(alpha = 0.6f)

private val DrawerWidth = 300.dp
private val DrawerStartPadding = 6.dp
private val DrawerVerticalPadding = 6.dp

enum class RetroPane { DISPLAY, SYSTEM, SOUND, CONTROLS }

data class RetroTabSpec(
    val pane: RetroPane?,
    val icon: ImageVector,
    val label: String,
)

sealed class RetroMenuEntry {
    class Action(
        val label: String,
        val icon: ImageVector,
        val active: Boolean = false,
        val danger: Boolean = false,
        val onClick: () -> Unit,
    ) : RetroMenuEntry()

    class Toggle(
        val label: String,
        val subtitle: String? = null,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : RetroMenuEntry()

    class Choice(
        val label: String,
        val valueLabel: String,
        val onCycle: (Int) -> Unit,
    ) : RetroMenuEntry()

    class Radio(
        val label: String,
        val selected: Boolean,
        val onSelect: () -> Unit,
    ) : RetroMenuEntry()
}

class RetroMenuController {
    var visible by mutableStateOf(false)
        private set
    var pane by mutableStateOf<RetroPane?>(null)
        private set
    var region by mutableIntStateOf(1)
    var railIndex by mutableIntStateOf(0)
    var contentIndex by mutableIntStateOf(0)
    var bottomIndex by mutableIntStateOf(0)
    var controllerActive by mutableStateOf(false)
    var tabs by mutableStateOf<List<RetroTabSpec>>(emptyList())
    var entries by mutableStateOf<List<RetroMenuEntry>>(emptyList())
        private set
    var bottomEntries by mutableStateOf<List<RetroMenuEntry.Action>>(emptyList())
        private set
    var entriesProvider: ((RetroPane?) -> List<RetroMenuEntry>)? = null
    var bottomProvider: (() -> List<RetroMenuEntry.Action>)? = null

    val gridColumns: Int get() = if (pane == null) 3 else 1

    fun open() {
        pane = null
        railIndex = 0
        region = 1
        contentIndex = 0
        bottomIndex = 0
        controllerActive = false
        rebuild()
        visible = true
    }

    fun close() {
        visible = false
    }

    fun showPane(target: RetroPane?) {
        pane = target
        rebuild()
        region = 1
        contentIndex = 0
        railIndex = tabs.indexOfFirst { it.pane == target }.coerceAtLeast(0)
    }

    fun rebuild() {
        entries = entriesProvider?.invoke(pane) ?: emptyList()
        bottomEntries = bottomProvider?.invoke() ?: emptyList()
        if (contentIndex >= entries.size) contentIndex = (entries.size - 1).coerceAtLeast(0)
        if (bottomIndex >= bottomEntries.size) bottomIndex = (bottomEntries.size - 1).coerceAtLeast(0)
    }

    private fun activate(direction: Int) {
        when (val entry = entries.getOrNull(contentIndex)) {
            is RetroMenuEntry.Action -> if (direction == 0) entry.onClick()
            is RetroMenuEntry.Toggle -> entry.onChange(!entry.checked)
            is RetroMenuEntry.Choice -> entry.onCycle(if (direction < 0) -1 else 1)
            is RetroMenuEntry.Radio -> if (direction == 0) entry.onSelect()
            else -> {}
        }
    }

    private fun moveContent(delta: Int) {
        if (entries.isEmpty()) return
        val next = contentIndex + delta
        when {
            next < 0 -> region = 0
            next >= entries.size && pane == null -> region = 2
            next in entries.indices -> contentIndex = next
        }
    }

    fun handleKey(
        keyCode: Int,
        action: Int,
    ): Boolean {
        if (!visible) return false
        val navKeys =
            setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_BUTTON_START,
            )
        if (keyCode !in navKeys) return true
        if (action == KeyEvent.ACTION_DOWN) {
            controllerActive = true
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    when (region) {
                        1 -> if (pane == null) moveContent(-gridColumns) else moveContent(-1)
                        2 -> region = 1
                    }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    when (region) {
                        0 -> if (entries.isNotEmpty()) region = 1
                        1 -> if (pane == null) moveContent(gridColumns) else moveContent(1)
                    }
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    when (region) {
                        0 -> railIndex = (railIndex - 1 + tabs.size) % tabs.size
                        1 -> if (pane == null) moveContent(-1) else activate(-1)
                        2 -> if (bottomEntries.isNotEmpty()) {
                            bottomIndex = (bottomIndex - 1 + bottomEntries.size) % bottomEntries.size
                        }
                    }
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    when (region) {
                        0 -> railIndex = (railIndex + 1) % tabs.size
                        1 -> if (pane == null) moveContent(1) else activate(1)
                        2 -> if (bottomEntries.isNotEmpty()) {
                            bottomIndex = (bottomIndex + 1) % bottomEntries.size
                        }
                    }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    when (region) {
                        0 -> tabs.getOrNull(railIndex)?.let { showPane(it.pane) }
                        1 -> activate(0)
                        2 -> bottomEntries.getOrNull(bottomIndex)?.onClick?.invoke()
                    }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START,
                -> if (pane != null) showPane(null) else close()
            }
        }
        return true
    }
}

@Composable
fun RetroDrawerMenu(controller: RetroMenuController) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val paneScale = (maxHeight.value / 520f).coerceIn(0.78f, 1f)
        if (controller.visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controller.close() },
            )
        }

        val closedOffset = -(DrawerWidth + DrawerStartPadding + 8.dp)
        val sheetOffset by animateDpAsState(
            targetValue = if (controller.visible) 0.dp else closedOffset,
            animationSpec = tween(durationMillis = 200, easing = LinearEasing),
            label = "retroDrawerOffset",
        )
        if (sheetOffset > closedOffset) {
            Box(
                Modifier
                    .padding(start = DrawerStartPadding, top = DrawerVerticalPadding, bottom = DrawerVerticalPadding)
                    .fillMaxHeight()
                    .width(DrawerWidth)
                    .offset { androidx.compose.ui.unit.IntOffset(with(density) { sheetOffset.roundToPx() }, 0) }
                    .clip(RoundedCornerShape(20.dp))
                    .background(RetroSheetColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            ) {
                Column(Modifier.fillMaxSize()) {
                    RetroTopRail(controller, paneScale)
                    ThinDivider()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            targetState = controller.pane,
                            transitionSpec = {
                                fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith
                                    fadeOut(tween(220, easing = FastOutSlowInEasing))
                            },
                            label = "retroDrawerBody",
                        ) { pane ->
                            if (pane == null) {
                                RetroActionGrid(controller, paneScale)
                            } else {
                                RetroPaneList(controller, paneScale)
                            }
                        }
                    }
                    AnimatedVisibility(visible = controller.pane == null && controller.bottomEntries.isNotEmpty()) {
                        Column {
                            ThinDivider()
                            RetroBottomActions(controller, paneScale)
                        }
                    }
                }
            }
        }
    }
}

private data class RailTileBounds(
    val offsetX: Float,
    val width: Float,
    val height: Float,
)

@Composable
private fun RetroTopRail(
    controller: RetroMenuController,
    paneScale: Float,
) {
    val density = LocalDensity.current
    val tileBounds = remember { mutableStateMapOf<Int, RailTileBounds>() }
    val railScroll = rememberScrollState()

    val selectedIndex =
        if (controller.controllerActive && controller.region == 0) {
            controller.railIndex
        } else {
            controller.tabs.indexOfFirst { it.pane == controller.pane }.coerceAtLeast(0)
        }
    val selectedBounds = tileBounds[selectedIndex]

    val indicatorAnimSpec = tween<Dp>(durationMillis = 240, easing = FastOutSlowInEasing)
    val indicatorX by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.offsetX.toDp() } } ?: 0.dp,
        animationSpec = indicatorAnimSpec,
        label = "retroRailX",
    )
    val indicatorWidth by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.width.toDp() } } ?: 0.dp,
        animationSpec = indicatorAnimSpec,
        label = "retroRailW",
    )
    val indicatorTileHeight by animateDpAsState(
        targetValue = selectedBounds?.let { with(density) { it.height.toDp() } } ?: 0.dp,
        animationSpec = indicatorAnimSpec,
        label = "retroRailH",
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selectedBounds != null) 1f else 0f,
        animationSpec = tween(160),
        label = "retroRailA",
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(TopRailSurfaceColor)
                .padding(
                    start = (10f * paneScale).dp,
                    end = (10f * paneScale).dp,
                    top = (5f * paneScale).dp,
                    bottom = (2f * paneScale).dp,
                ),
    ) {
        if (selectedBounds != null) {
            Box(
                modifier =
                    Modifier
                        .offset(
                            x = indicatorX - with(density) { railScroll.value.toDp() } + (6f * paneScale).dp,
                            y = indicatorTileHeight - (2f * paneScale).dp,
                        )
                        .width((indicatorWidth - (12f * paneScale).dp).coerceAtLeast(0.dp))
                        .height((2f * paneScale).dp)
                        .graphicsLayer { alpha = indicatorAlpha }
                        .clip(RoundedCornerShape(1.dp))
                        .background(DrawerAccent),
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(railScroll),
            horizontalArrangement = Arrangement.spacedBy((10f * paneScale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            controller.tabs.forEachIndexed { index, tab ->
                RetroRailTile(
                    icon = tab.icon,
                    label = tab.label,
                    selected =
                        if (controller.controllerActive && controller.region == 0) {
                            controller.railIndex == index
                        } else {
                            controller.pane == tab.pane
                        },
                    highlighted = controller.controllerActive && controller.region == 0 && controller.railIndex == index,
                    paneScale = paneScale,
                    onBoundsChanged = { tileBounds[index] = it },
                    onClick = {
                        controller.railIndex = index
                        controller.showPane(tab.pane)
                    },
                )
            }
        }
    }
}

@Composable
private fun RetroRailTile(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    highlighted: Boolean,
    paneScale: Float,
    onBoundsChanged: (RailTileBounds) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "retroTileScale",
    )
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                pressed && !selected -> PaneSurfacePressed
                else -> Color.Transparent
            },
        animationSpec = tween(120),
        label = "retroTileBg",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) DrawerAccent else DrawerTextPrimary,
        animationSpec = tween(120),
        label = "retroTileTint",
    )
    val cornerRadius = (12f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier =
            Modifier
                .defaultMinSize(minWidth = (60f * paneScale).dp)
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInParent()
                    onBoundsChanged(RailTileBounds(bounds.left, bounds.width, bounds.height))
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(bgColor)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(
                    start = (10f * paneScale).dp,
                    end = (10f * paneScale).dp,
                    top = (10f * paneScale).dp,
                    bottom = (7f * paneScale).dp,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size((22f * paneScale).dp))
        Spacer(Modifier.height((2f * paneScale).dp))
        Text(
            text = label,
            color = DrawerTextPrimary,
            fontSize = (12f * paneScale).sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RetroActionGrid(
    controller: RetroMenuController,
    paneScale: Float,
) {
    val actions = controller.entries
    val spacing = (8f * paneScale).dp
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = (10f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        actions.chunked(controller.gridColumns).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEachIndexed { colIndex, entry ->
                    val flatIndex = rowIndex * controller.gridColumns + colIndex
                    if (entry is RetroMenuEntry.Action) {
                        RetroActionCard(
                            entry = entry,
                            highlighted =
                                controller.controllerActive &&
                                    controller.region == 1 &&
                                    controller.contentIndex == flatIndex,
                            paneScale = paneScale,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            onClick = {
                                controller.contentIndex = flatIndex
                                entry.onClick()
                            },
                        )
                    }
                }
                repeat(controller.gridColumns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RetroActionCard(
    entry: RetroMenuEntry.Action,
    highlighted: Boolean,
    paneScale: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "retroCardScale",
    )
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(120),
        label = "retroCardBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (entry.active) ActiveCardBorder else RestingCardBorder,
        animationSpec = tween(120),
        label = "retroCardBorder",
    )
    val tint =
        when {
            entry.danger -> GlassExitTint
            entry.active -> DrawerActiveAccent
            else -> DrawerTextPrimary
        }
    val cornerRadius = (12f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    val topColor =
        Color(
            red = (bgColor.red + (1f - bgColor.red) * DrawerGradientLift).coerceIn(0f, 1f),
            green = (bgColor.green + (1f - bgColor.green) * DrawerGradientLift).coerceIn(0f, 1f),
            blue = (bgColor.blue + (1f - bgColor.blue) * DrawerGradientLift).coerceIn(0f, 1f),
            alpha = bgColor.alpha,
        )
    Column(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(Brush.verticalGradient(listOf(topColor, bgColor)))
                .border(1.dp, borderColor, shape)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.label,
            tint = tint,
            modifier = Modifier.size((24f * paneScale).dp),
        )
        Spacer(Modifier.height((4f * paneScale).dp))
        Text(
            text = entry.label,
            color = DrawerTextPrimary,
            fontSize = (12f * paneScale).sp,
            fontWeight = if (entry.active) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.2.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RetroPaneList(
    controller: RetroMenuController,
    paneScale: Float,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = (10f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
    ) {
        controller.entries.forEachIndexed { index, entry ->
            val highlighted =
                controller.controllerActive &&
                    controller.region == 1 &&
                    controller.contentIndex == index
            when (entry) {
                is RetroMenuEntry.Toggle ->
                    RetroBooleanRow(
                        entry = entry,
                        highlighted = highlighted,
                        paneScale = paneScale,
                        onClick = {
                            controller.contentIndex = index
                            entry.onChange(!entry.checked)
                        },
                    )
                is RetroMenuEntry.Choice ->
                    RetroChoiceRow(
                        entry = entry,
                        highlighted = highlighted,
                        paneScale = paneScale,
                        onClick = {
                            controller.contentIndex = index
                            entry.onCycle(1)
                        },
                    )
                is RetroMenuEntry.Radio ->
                    RetroRadioRow(
                        entry = entry,
                        highlighted = highlighted,
                        paneScale = paneScale,
                        onClick = {
                            controller.contentIndex = index
                            entry.onSelect()
                        },
                    )
                is RetroMenuEntry.Action ->
                    RetroActionCard(
                        entry = entry,
                        highlighted = highlighted,
                        paneScale = paneScale,
                        modifier = Modifier.fillMaxWidth().height((56f * paneScale).dp),
                        onClick = {
                            controller.contentIndex = index
                            entry.onClick()
                        },
                    )
            }
        }
    }
}

@Composable
private fun RetroRowShell(
    highlighted: Boolean,
    activeBorder: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                pressed -> PaneInnerPressed
                else -> PaneInnerResting
            },
        animationSpec = tween(140),
        label = "retroRowBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (activeBorder) ActiveCardBorder else RestingCardBorder,
        animationSpec = tween(140),
        label = "retroRowBorder",
    )
    val cornerRadius = (14f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bgColor)
                .border(1.dp, borderColor, shape)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = (12f * paneScale).dp, vertical = (8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun RetroBooleanRow(
    entry: RetroMenuEntry.Toggle,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(highlighted = highlighted, activeBorder = entry.checked, paneScale = paneScale, onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.subtitle ?: if (entry.checked) "Enabled" else "Disabled",
                color = DrawerTextSecondary,
                fontSize = (12f * paneScale).sp,
            )
        }
        Switch(
            checked = entry.checked,
            onCheckedChange = entry.onChange,
            colors = outlinedSwitchColors(DrawerAccent, DrawerTextSecondary),
        )
    }
}

@Composable
private fun RetroChoiceRow(
    entry: RetroMenuEntry.Choice,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(highlighted = highlighted, activeBorder = false, paneScale = paneScale, onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = DrawerTextPrimary,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.valueLabel,
                color = DrawerActiveAccent,
                fontSize = (12f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "‹ ›",
            color = DrawerTextSecondary,
            fontSize = (16f * paneScale).sp,
        )
    }
}

@Composable
private fun RetroRadioRow(
    entry: RetroMenuEntry.Radio,
    highlighted: Boolean,
    paneScale: Float,
    onClick: () -> Unit,
) {
    RetroRowShell(highlighted = highlighted, activeBorder = entry.selected, paneScale = paneScale, onClick = onClick) {
        Text(
            text = entry.label,
            color = if (entry.selected) DrawerActiveAccent else DrawerTextPrimary,
            fontSize = (14f * paneScale).sp,
            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (entry.selected) {
            Text(
                text = "✓",
                color = DrawerActiveAccent,
                fontSize = (14f * paneScale).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RetroBottomActions(
    controller: RetroMenuController,
    paneScale: Float,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = (10f * paneScale).dp, vertical = (8f * paneScale).dp),
        horizontalArrangement = Arrangement.spacedBy((8f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.bottomEntries.forEachIndexed { index, entry ->
            RetroBottomActionButton(
                entry = entry,
                highlighted =
                    controller.controllerActive &&
                        controller.region == 2 &&
                        controller.bottomIndex == index,
                paneScale = paneScale,
                modifier = Modifier.weight(1f),
                onClick = {
                    controller.bottomIndex = index
                    entry.onClick()
                },
            )
        }
    }
}

@Composable
private fun RetroBottomActionButton(
    entry: RetroMenuEntry.Action,
    highlighted: Boolean,
    paneScale: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val bgColor by animateColorAsState(
        targetValue =
            when {
                highlighted -> DrawerFocusFill
                entry.danger && pressed -> TileExitPressed
                entry.danger -> TileExitResting
                pressed -> PaneSurfacePressed
                else -> PaneInnerResting
            },
        animationSpec = tween(120),
        label = "retroBottomBg",
    )
    val borderColor =
        when {
            entry.danger -> GlassExitTint.copy(alpha = 0.34f)
            entry.active -> ActiveCardBorder
            else -> RestingCardBorder
        }
    val tint =
        when {
            entry.danger -> GlassExitTint
            entry.active -> DrawerActiveAccent
            else -> DrawerTextPrimary
        }
    val cornerRadius = (14f * paneScale).dp
    val shape = RoundedCornerShape(cornerRadius)
    Row(
        modifier =
            modifier
                .clip(shape)
                .background(bgColor)
                .border(1.dp, borderColor, shape)
                .then(
                    if (highlighted) {
                        Modifier.chasingBorder(cornerRadius = cornerRadius, borderWidth = 1.5.dp, animationDurationMs = 8200)
                    } else {
                        Modifier
                    },
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = (12f * paneScale).dp, vertical = (10f * paneScale).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.label,
            tint = tint,
            modifier = Modifier.size((18f * paneScale).dp),
        )
        Spacer(Modifier.width((8f * paneScale).dp))
        Text(
            text = entry.label,
            color = tint,
            fontSize = (13f * paneScale).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThinDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DividerColor),
    )
}

object RetroDrawerTabs {
    fun build(
        system: RetroSystem?,
        hasCoreOptions: Boolean,
    ): List<RetroTabSpec> {
        val tabs = mutableListOf<RetroTabSpec>()
        tabs += RetroTabSpec(null, Icons.Outlined.Apps, "Menu")
        tabs += RetroTabSpec(RetroPane.DISPLAY, Icons.Outlined.Monitor, "Display")
        if (hasCoreOptions) {
            tabs += RetroTabSpec(RetroPane.SYSTEM, Icons.Outlined.Tune, system?.shortName ?: "System")
        }
        tabs += RetroTabSpec(RetroPane.SOUND, Icons.AutoMirrored.Outlined.VolumeUp, "Sound")
        tabs += RetroTabSpec(RetroPane.CONTROLS, Icons.Outlined.SportsEsports, "Controls")
        return tabs
    }
}

object RetroDrawerIcons {
    val Resume = Icons.Outlined.PlayArrow
    val Pause = Icons.Outlined.Pause
    val Save = Icons.Outlined.Save
    val Load = Icons.Outlined.Download
    val Reset = Icons.Outlined.RestartAlt
    val FastForward = Icons.Outlined.FastForward
    val Disc = Icons.Outlined.Album
    val Hud = Icons.Outlined.Speed
    val Exit = Icons.AutoMirrored.Outlined.ExitToApp
}
