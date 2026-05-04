package com.winlator.cmod.shared.ui.dialog

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.winlator.cmod.runtime.system.LaunchLogBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen modal overlay that replaces [PreloaderDialog]'s "Loading…"
 * UI when Settings → Debug → Verbose Launch is on.
 *
 * Subscribes to [LaunchLogBus.state] and renders every event in arrival
 * order with a fixed-width font. The overlay is intentionally
 * non-dismissible (matches the preloader contract — the launch sequence
 * itself decides when to close it). When the launch finishes the host
 * dismisses the dialog.
 */
fun setupVerboseLaunchComposeView(view: ComposeView, activity: Activity) {
    // Dialogs don't propagate a ViewTreeLifecycleOwner / SavedStateRegistry
    // to their content view automatically — without these the AbstractCompose
    // View attach handler crashes with
    // "ViewTreeLifecycleOwner not found from ComposeView…".
    // PreloaderDialogContentKt does the same dance; mirror it here.
    if (activity is androidx.lifecycle.LifecycleOwner) {
        view.setViewTreeLifecycleOwner(activity)
    }
    if (activity is androidx.savedstate.SavedStateRegistryOwner) {
        view.setViewTreeSavedStateRegistryOwner(activity)
    }
    view.setViewCompositionStrategy(
        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
    )
    view.setContent {
        VerboseLaunchOverlay()
    }
}

@Composable
private fun VerboseLaunchOverlay() {
    val events by LaunchLogBus.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.scrollToItem(events.lastIndex)
        }
    }

    val statusBars = WindowInsets.statusBars.asPaddingValues()
    val navBars = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = statusBars.calculateTopPadding() + 12.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = navBars.calculateBottomPadding() + 12.dp,
                ),
        ) {
            Header(eventCount = events.size)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF050A14)),
            ) {
                items(events, key = { it.timestampMs.toString() + ":" + it.tag + ":" + it.message.hashCode() }) { ev ->
                    EventRow(ev)
                }
            }
        }
    }
}

@Composable
private fun Header(eventCount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Verbose Launch",
            color = Color(0xFF9EE0FF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(0.dp))
        Text(
            text = "  ($eventCount events)",
            color = Color(0xFF6B7A8C),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EventRow(ev: LaunchLogBus.Event) {
    val color = when (ev.level) {
        LaunchLogBus.Level.ERROR -> Color(0xFFFF8585)
        LaunchLogBus.Level.WARN -> Color(0xFFFFC36B)
        LaunchLogBus.Level.DEBUG -> Color(0xFF7C8AA0)
        LaunchLogBus.Level.INFO -> Color(0xFFBFE9FF)
    }
    val ts = TIME_FMT.format(Date(ev.timestampMs))
    Text(
        text = "[$ts] ${ev.tag}: ${ev.message}",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
