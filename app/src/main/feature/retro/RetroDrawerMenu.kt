package com.winlator.cmod.feature.retro

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed class RetroMenuEntry {
    class Header(
        val label: String,
    ) : RetroMenuEntry()

    class Action(
        val label: String,
        val onClick: () -> Unit,
    ) : RetroMenuEntry()

    class Toggle(
        val label: String,
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
    ) : RetroMenuEntry()

    class Choice(
        val label: String,
        val valueLabel: String,
        val onCycle: (Int) -> Unit,
    ) : RetroMenuEntry()
}

class RetroMenuController {
    var visible by mutableStateOf(false)
        private set
    var entries by mutableStateOf<List<RetroMenuEntry>>(emptyList())
        private set
    var focusIndex by mutableIntStateOf(0)

    fun open(items: List<RetroMenuEntry>) {
        entries = items
        focusIndex = items.indexOfFirst { it !is RetroMenuEntry.Header }.coerceAtLeast(0)
        visible = true
    }

    fun update(items: List<RetroMenuEntry>) {
        entries = items
        if (focusIndex >= items.size) {
            focusIndex = items.indexOfFirst { it !is RetroMenuEntry.Header }.coerceAtLeast(0)
        }
    }

    fun close() {
        visible = false
    }

    private fun moveFocus(direction: Int) {
        if (entries.isEmpty()) return
        var index = focusIndex
        repeat(entries.size) {
            index = (index + direction + entries.size) % entries.size
            if (entries[index] !is RetroMenuEntry.Header) {
                focusIndex = index
                return
            }
        }
    }

    private fun activate(direction: Int) {
        when (val entry = entries.getOrNull(focusIndex)) {
            is RetroMenuEntry.Action -> if (direction == 0) entry.onClick()
            is RetroMenuEntry.Toggle -> entry.onChange(!entry.checked)
            is RetroMenuEntry.Choice -> entry.onCycle(if (direction < 0) -1 else 1)
            else -> {}
        }
    }

    fun handleKey(
        keyCode: Int,
        action: Int,
    ): Boolean {
        if (!visible) return false
        val handled =
            keyCode in
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
        if (!handled) return false
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveFocus(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> activate(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> activate(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> activate(0)
            }
        } else if (action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_START,
                -> close()
            }
        }
        return true
    }
}

private val DrawerBackground = Color(0xF0141B24)
private val DrawerFocus = Color(0xFF0E2438)
private val DrawerAccent = Color(0xFF29B6F6)
private val DrawerText = Color(0xFFE6EDF3)
private val DrawerTextDim = Color(0xFF8B949E)

@Composable
fun RetroDrawerMenu(
    controller: RetroMenuController,
    title: String,
    systemLabel: String,
) {
    AnimatedVisibility(
        visible = controller.visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controller.close() },
        )
    }
    AnimatedVisibility(
        visible = controller.visible,
        enter = slideInHorizontally(initialOffsetX = { -it }),
        exit = slideOutHorizontally(targetOffsetX = { -it }),
    ) {
        Column(
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(DrawerBackground)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    title,
                    color = DrawerText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
                Text(systemLabel, color = DrawerAccent, fontSize = 11.sp)
            }
            val listState = rememberLazyListState()
            LaunchedEffect(controller.focusIndex, controller.visible) {
                if (controller.visible && controller.entries.isNotEmpty()) {
                    listState.animateScrollToItem(
                        controller.focusIndex.coerceIn(0, controller.entries.size - 1),
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(controller.entries) { index, entry ->
                    when (entry) {
                        is RetroMenuEntry.Header ->
                            Text(
                                entry.label,
                                color = DrawerTextDim,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp),
                            )
                        is RetroMenuEntry.Action ->
                            MenuRow(
                                focused = index == controller.focusIndex,
                                onClick = { controller.focusIndex = index; entry.onClick() },
                            ) {
                                Text(entry.label, color = DrawerText, fontSize = 13.sp)
                            }
                        is RetroMenuEntry.Toggle ->
                            MenuRow(
                                focused = index == controller.focusIndex,
                                onClick = { controller.focusIndex = index; entry.onChange(!entry.checked) },
                            ) {
                                Text(entry.label, color = DrawerText, fontSize = 13.sp)
                                Text(
                                    if (entry.checked) "On" else "Off",
                                    color = if (entry.checked) DrawerAccent else DrawerTextDim,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        is RetroMenuEntry.Choice ->
                            MenuRow(
                                focused = index == controller.focusIndex,
                                onClick = { controller.focusIndex = index; entry.onCycle(1) },
                            ) {
                                Text(entry.label, color = DrawerText, fontSize = 13.sp)
                                Text(
                                    entry.valueLabel,
                                    color = DrawerAccent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    focused: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) DrawerFocus else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        content()
    }
}
