package app.easyprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.easyprint.PrinterStatus
import app.easyprint.android.*
import app.easyprint.core.plural
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismiss: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 14.dp).semantics { heading() })
            content()
        }
    }
}

@Composable
fun PrinterSheet(
    printers: List<PrinterChoice>,
    selected: PrinterChoice,
    status: PrinterStatus,
    searching: Boolean,
    onSelect: (PrinterChoice) -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit,
    onForget: (PrinterChoice.Direct) -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    var adding by remember { mutableStateOf(false) }
    AppSheet(onDismiss, "Printer") {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            printers.forEach { pr ->
                val sel = pr.key == selected.key
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) p.accentSoft else p.surface2)
                        .border(if (sel) 2.dp else 1.dp, if (sel) p.accent else p.border, RoundedCornerShape(14.dp))
                        .selectable(selected = sel, role = Role.RadioButton) { onSelect(pr) }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (pr is PrinterChoice.System) Icons.Outlined.Apps else Icons.Outlined.Print, null,
                        tint = if (sel) p.accent else p.muted, modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pr.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (pr) {
                                is PrinterChoice.Direct -> if (sel && status.message.isNotBlank()) status.message else "Wi-Fi · ${pr.host}"
                                PrinterChoice.System -> "Uses Android’s print screen: Canon Print Service, Mopria, Save as PDF…"
                            },
                            style = MaterialTheme.typography.bodySmall, color = p.muted,
                        )
                    }
                    if (pr is PrinterChoice.Direct && pr.manual) {
                        IconButton(onClick = { onForget(pr) }) { Icon(Icons.Outlined.Delete, "Forget ${pr.label}") }
                    }
                    RadioButton(selected = sel, onClick = null)
                }
            }
        }
        if (searching) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking for printers on your Wi-Fi…", style = MaterialTheme.typography.bodyMedium, color = p.muted)
        } else if (printers.none { it is PrinterChoice.Direct }) Text(
            "No Wi-Fi printers found. Check the printer is on and connected to the same Wi-Fi as this phone, or add it by its IP address.",
            style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(top = 14.dp),
        )

        Spacer(Modifier.height(16.dp))
        if (adding) {
            var address by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = address, onValueChange = { address = it }, singleLine = true,
                label = { Text("Printer IP address") }, placeholder = { Text("e.g. 192.168.1.23") },
                supportingText = { Text("Find it on the printer: LAN settings → Confirm LAN settings, or print the network settings page.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(onClick = { adding = false }) { Text("Cancel") }
                Button(enabled = address.isNotBlank() && !busy, onClick = { busy = true; onAdd(address) { ok -> busy = false; if (ok) adding = false } }) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = p.onAccent) else Text("Add printer")
                }
            }
        } else {
            ToolRow(Icons.Outlined.AddCircleOutline, "Add a printer by IP address", "If your printer isn’t found automatically") { adding = true }
        }
        ToolRow(Icons.Outlined.FlipToBack, "Two-sided test", "Prints 2 sheets to check the backs line up the right way") { onTest() }
    }
}

@Composable
fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(p.surface2)
            .border(1.dp, p.border, RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.accent)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
    }
}

@Composable
fun DisplaySheet(look: LookPrefs, onChange: (LookPrefs) -> Unit, onDismiss: () -> Unit) {
    AppSheet(onDismiss, "Display") {
        val sizes = listOf(1f to "Normal", 1.15f to "Large", 1.3f to "Larger", 1.5f to "Largest")
        Segmented(
            "Text size", sizes.map { it.first }, sizes.minBy { kotlin.math.abs(it.first - look.textScale) }.first,
            { onChange(look.copy(textScale = it)) }, title = { "A" }, subtitle = { s -> sizes.first { it.first == s }.second },
        )
        Spacer(Modifier.height(18.dp))
        Segmented("Colours", ThemeMode.entries, look.theme, { onChange(look.copy(theme = it)) },
            title = { when (it) { ThemeMode.SYSTEM -> "Auto"; ThemeMode.CONTRAST -> "Contrast"; else -> it.label } },
            subtitle = { when (it) { ThemeMode.SYSTEM -> "Like phone"; ThemeMode.CONTRAST -> "Strongest"; else -> " " } })
        Spacer(Modifier.height(12.dp))
        SwitchRow("Reduce animation", look.reduceMotion, { onChange(look.copy(reduceMotion = it)) })
        SwitchRow("Read two-sided instructions aloud", look.speak, { onChange(look.copy(speak = it)) }, "Uses your phone’s text-to-speech voice")
    }
}

@Composable
fun HistorySheet(items: List<HistoryEntry>, onOpen: (HistoryEntry) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AppSheet(onDismiss, "Recent prints") {
        if (items.isEmpty()) Text("Nothing printed yet.", style = MaterialTheme.typography.bodyLarge, color = p.muted)
        items.forEach { h ->
            val s = h.settings
            val what = listOfNotNull(
                plural(h.sheets, "sheet"),
                if (s.sides == app.easyprint.core.Sides.ONE) "one side" else "both sides",
                if (s.booklet) "booklet" else if (s.pagesPerSide > 1) "${s.pagesPerSide} per side" else null,
                if (s.color == app.easyprint.core.ColorMode.BW) "B&W" else "colour",
            ).joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(p.surface2)
                    .border(1.dp, p.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(h.time))}\n$what",
                        style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
                FilledTonalButton(onClick = { onOpen(h) }, modifier = Modifier.semantics { contentDescription = "Use ${h.title} again with the same settings" }) {
                    Text("Use again")
                }
            }
        }
        if (items.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) { Text("Clear history") }
    }
}

@Composable
fun HelpSheet(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AppSheet(onDismiss, "Help") {
        @Composable fun H(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp).semantics { heading() })
        @Composable fun P(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
        H("Printing on both sides")
        P("The G3000 series can’t turn paper over by itself, so Easy Print does it in two rounds. It prints all the fronts, shows you how to put the pile back, then prints the backs. It always uses the same simple move: turn the pile over like a page in a book.")
        P("• Long edge: reads like a book. Use this for most documents.\n• Short edge: flips up like a notepad or wall calendar.")
        P("If backs come out upside down or on the wrong sheets, run the two-sided test (Printer → Two-sided test). It fixes this for you.")
        H("Connecting")
        P("Your phone and printer must be on the same Wi-Fi. The printer appears automatically. If not, add it by IP address in the Printer menu.")
        P("“Other printers & Save as PDF” uses Android’s own print screen. It works with any printer that has a print service (Canon Print Service, Mopria), and can save PDFs.")
        H("Opening documents")
        P("Tap Choose document, or use Share → Easy Print from any app (Files, Gmail, Photos, Chrome, WhatsApp…). PDFs, photos and text files work. For Word or Excel files, choose Share → Print or Save as PDF in that app first.")
        H("Tips")
        P("• Load paper in the rear tray, print side facing up.\n• Draft + Black & white uses the least ink.\n• For photos choose High quality and the matching photo paper (More options).")
        Text("Works with TalkBack, large fonts and Switch Access.", style = MaterialTheme.typography.bodySmall, color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun NameDialog(title: String, label: String, confirm: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it.take(40) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
