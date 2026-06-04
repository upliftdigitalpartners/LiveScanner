package dev.fahim.livescanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fahim.livescanner.data.Feed
import dev.fahim.livescanner.data.FeedSource
import dev.fahim.livescanner.data.FeedType
import java.util.UUID

private enum class AddMode { URL, BROADCASTIFY }

@Composable
fun AddFeedDialog(
    onDismiss: () -> Unit,
    onAdd: (Feed) -> Unit,
) {
    var mode by remember { mutableStateOf(AddMode.URL) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var feedId by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(FeedType.SCANNER) }

    val valid = when (mode) {
        AddMode.URL -> url.startsWith("http://") || url.startsWith("https://")
        AddMode.BROADCASTIFY -> feedId.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == AddMode.URL,
                        onClick = { mode = AddMode.URL },
                        label = { Text("Direct URL") },
                    )
                    FilterChip(
                        selected = mode == AddMode.BROADCASTIFY,
                        onClick = { mode = AddMode.BROADCASTIFY },
                        label = { Text("Broadcastify ID") },
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                when (mode) {
                    AddMode.URL -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it.trim() },
                            label = { Text("Stream URL (http/https)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FeedType.entries.forEach { t ->
                                FilterChip(
                                    selected = t == type,
                                    onClick = { type = t },
                                    label = { Text(typeLabel(t)) },
                                )
                            }
                        }
                    }

                    AddMode.BROADCASTIFY -> {
                        OutlinedTextField(
                            value = feedId,
                            onValueChange = { input -> feedId = input.filter { it.isDigit() } },
                            label = { Text("Broadcastify feed ID") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "The number in broadcastify.com/listen/feed/<ID>. " +
                                "Free feeds are best-effort — for reliable audio add Premium credentials in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val feed = when (mode) {
                        AddMode.URL -> Feed(
                            id = "custom:" + UUID.randomUUID(),
                            name = name.ifBlank { url },
                            source = FeedSource.CUSTOM,
                            type = type,
                            streamUrl = url,
                        )

                        AddMode.BROADCASTIFY -> Feed(
                            id = "bcfy:$feedId",
                            name = name.ifBlank { "Broadcastify $feedId" },
                            source = FeedSource.BROADCASTIFY,
                            type = FeedType.SCANNER,
                            broadcastifyFeedId = feedId,
                            description = "Broadcastify feed #$feedId",
                        )
                    }
                    onAdd(feed)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SettingsDialog(
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    var user by remember { mutableStateOf(vm.broadcastifyUsername() ?: "") }
    var pass by remember { mutableStateOf("") }
    val existingPass = remember { vm.broadcastifyPassword() }
    val configured by vm.broadcastifyConfigured.collectAsStateWithLifecycle()
    var groqKey by remember { mutableStateOf(vm.groqApiKey() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Broadcastify Premium", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Optional. With Premium credentials, Broadcastify scanner feeds you add " +
                        "stream reliably via the direct audio endpoint. LiveATC feeds need no account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(if (configured) "Password (leave blank to keep)" else "Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (configured) {
                    Text(
                        "Credentials saved ✓",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text("AI transcription (Groq)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Optional. A free key from console.groq.com powers live ATC transcription " +
                        "(the captions toggle on the radar screen).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    label = { Text("Groq API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val u = user.trim().ifBlank { null }
                val p = when {
                    u == null -> null
                    pass.isNotBlank() -> pass
                    else -> existingPass
                }
                vm.saveBroadcastifyCredentials(u, p)
                vm.saveGroqApiKey(groqKey.trim().ifBlank { null })
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun typeLabel(type: FeedType): String = when (type) {
    FeedType.ATC -> "ATC"
    FeedType.SCANNER -> "Scanner"
    FeedType.OTHER -> "Other"
}
