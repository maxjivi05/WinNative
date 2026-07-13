package com.winlator.cmod.feature.retro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.winlator.cmod.runtime.container.Shortcut

private val SHADERS = listOf("default" to "Default", "crt" to "CRT", "lcd" to "LCD", "sharp" to "Sharp")

@Composable
fun RetroGameSettingsDialog(
    shortcut: Shortcut,
    onDismiss: () -> Unit,
) {
    val system = RetroShortcuts.systemForShortcut(shortcut)
    var shader by remember { mutableStateOf(shortcut.getExtra(RetroShortcuts.KEY_SHADER, "default")) }
    var touchControls by remember { mutableStateOf(shortcut.getExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, "1") != "0") }
    var audio by remember { mutableStateOf(shortcut.getExtra(RetroShortcuts.KEY_AUDIO, "1") != "0") }

    fun persist() {
        shortcut.putExtra(RetroShortcuts.KEY_SHADER, shader)
        shortcut.putExtra(RetroShortcuts.KEY_TOUCH_CONTROLS, if (touchControls) "1" else "0")
        shortcut.putExtra(RetroShortcuts.KEY_AUDIO, if (audio) "1" else "0")
        shortcut.saveData()
    }

    Dialog(onDismissRequest = { persist(); onDismiss() }) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = system?.displayName ?: "Console Game",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Emulator settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Text(
                    text = "Video filter",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SHADERS.forEach { (key, label) ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = shader == key, onClick = { shader = key })
                                .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = shader == key, onClick = { shader = key })
                        Text(label, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                ToggleRow("On-screen controls", touchControls) { touchControls = it }
                ToggleRow("Sound", audio) { audio = it }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { persist(); onDismiss() }) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
