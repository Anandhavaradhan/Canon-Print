package app.easyprint.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.easyprint.UiState
import app.easyprint.android.OpenedFile
import app.easyprint.android.PrinterChoice
import app.easyprint.android.SavedPreset
import app.easyprint.core.*

/** Everything the screen can ask the app to do. */
interface Actions {
    fun pickFiles()
    fun removeFile(f: OpenedFile)
    fun moveFile(index: Int, delta: Int)
    fun settings(t: (PrintSettings) -> PrintSettings)
    fun applySaved(p: SavedPreset)
    fun deletePreset(p: SavedPreset)
    fun savePreset(name: String)
    fun print()
    fun openPrinters()
    fun openDisplay()
    fun openHistory()
    fun openHelp()
    fun runTest()
    fun reset()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: UiState, actions: Actions, preview: PreviewLoader, snackbar: SnackbarHostState) {
    val p = LocalPalette.current
    Scaffold(
        containerColor = p.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(p.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Print, null, tint = p.onAccent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Easy Print", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = actions::openHistory) { Icon(Icons.Outlined.History, "Recent prints") }
                    IconButton(onClick = actions::openDisplay) { Icon(Icons.Outlined.TextFields, "Text size and colours") }
                    IconButton(onClick = actions::openHelp) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, "Help") }
                },
            )
        },
        bottomBar = { PrintBar(state, actions) },
    ) { pad ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { PrinterCard(state, actions) }
            item { DocumentCard(state, actions) }
            state.plan?.let { plan -> if (state.files.isNotEmpty()) item { PreviewCard(plan, preview) } }
            item { QuickPicks(state, actions) }
            item { SidesCard(state.settings, actions) }
            item { LayoutCard(state.settings, actions) }
            item { ColourCard(state.settings, actions) }
            item { PagesCard(state, actions) }
            item { MoreOptions(state.settings, actions) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun PrinterCard(state: UiState, actions: Actions) {
    val p = LocalPalette.current
    val pr = state.printer
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = "Change printer", onClick = actions::openPrinters)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
            Icon(if (pr is PrinterChoice.System) Icons.Outlined.Apps else Icons.Outlined.Print, null, tint = p.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pr.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                StatusDot(state.status.state)
                Spacer(Modifier.width(6.dp))
                Text(
                    state.status.message.ifBlank { if (state.searching) "Looking for printers…" else "" },
                    style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2,
                )
            }
            InkBars(state.status.markers, Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("Change", style = MaterialTheme.typography.labelLarge, color = p.accent)
    }
}

@Composable
private fun DocumentCard(state: UiState, actions: Actions) {
    val p = LocalPalette.current
    if (state.files.isEmpty() && state.importing == 0) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(p.surface)
                .border(2.dp, p.borderStrong, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Description, null, tint = p.accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("What would you like to print?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("PDFs, photos and text files", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            Spacer(Modifier.height(16.dp))
            Button(onClick = actions::pickFiles, modifier = Modifier.heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
                Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose document")
            }
            Spacer(Modifier.height(10.dp))
            Text("Tip: in any app, tap Share → Easy Print", style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        return
    }
    SectionCard("Document", step = 1, action = {
        TextButton(onClick = actions::pickFiles) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add") }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.files.forEachIndexed { i, f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface2)
                        .border(1.dp, p.border, RoundedCornerShape(12.dp)).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                        Text(f.name.substringAfterLast('.', "DOC").take(4).uppercase(), style = MaterialTheme.typography.labelMedium, color = p.accent, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(plural(f.pages.size, "page"), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    if (state.files.size > 1) {
                        IconButton(onClick = { actions.moveFile(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move ${f.name} up") }
                        IconButton(onClick = { actions.moveFile(i, 1) }, enabled = i < state.files.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Move ${f.name} down") }
                    }
                    IconButton(onClick = { actions.removeFile(f) }) { Icon(Icons.Filled.Close, "Remove ${f.name}") }
                }
            }
            if (state.importing > 0) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Opening…", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPicks(state: UiState, actions: Actions) {
    var naming by remember { mutableStateOf(false) }
    SectionCard("Quick picks", action = {
        TextButton(onClick = { naming = true }) { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save current") }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BuiltInPresets.all.forEach { pr -> Pill(pr.name, onClick = { actions.settings(pr.apply) }) }
            state.presets.forEach { pr ->
                Pill(
                    pr.name, onClick = { actions.applySaved(pr) },
                    leading = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = LocalPalette.current.accent) },
                    trailing = {
                        IconButton(onClick = { actions.deletePreset(pr) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, "Delete ${pr.name}", Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
    }
    if (naming) NameDialog("Save as a quick pick", "Name (e.g. School notes)", "Save") { name ->
        naming = false
        if (name != null) actions.savePreset(name)
    }
}

@Composable
private fun SidesCard(s: PrintSettings, actions: Actions) {
    val p = LocalPalette.current
    SectionCard("Sides", step = 2) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(Sides.ONE, "One side", "Print on the front only"),
                Triple(Sides.LONG, "Flip on long edge", "Both sides · turns like a book"),
                Triple(Sides.SHORT, "Flip on short edge", "Both sides · flips up like a notepad"),
            ).forEach { (v, t, sub) ->
                ChoiceTile(
                    selected = s.sides == v, onClick = { actions.settings { it.copy(sides = v) } },
                    title = t, subtitle = sub, horizontal = true, enabled = !s.booklet || v == Sides.SHORT,
                    modifier = Modifier.fillMaxWidth(),
                ) { sel -> SidesArt(v, sel) }
            }
        }
        val note = when {
            s.booklet -> "Booklets always print on both sides. Fold the printed pile in half to make your booklet."
            s.twoSided && s.pagesPerSide == 2 -> "With 2 pages per side the paper is sideways: “short edge” turns it like a book, “long edge” flips it up like a calendar."
            s.twoSided && s.duplexMethod == DuplexMethod.MANUAL -> "Your printer prints the fronts first, then shows you how to put the pile back in for the backs."
            else -> null
        }
        AnimatedVisibility(note != null) {
            Text(
                note ?: "", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(p.accentSoft).border(1.dp, p.accentLine, RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
    }
}

@Composable
private fun LayoutCard(s: PrintSettings, actions: Actions) {
    SectionCard("Pages per side", step = 3) {
        val options = listOf(1 to "1 page", 2 to "2 pages", 4 to "4 pages", 0 to "Booklet")
        val current = if (s.booklet) 0 else s.pagesPerSide
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (v, label) ->
                        ChoiceTile(
                            selected = current == v,
                            onClick = {
                                actions.settings {
                                    if (v == 0) it.copy(booklet = true)
                                    else it.copy(booklet = false, pagesPerSide = v, sides = if (it.booklet) Sides.LONG else it.sides)
                                }
                            },
                            title = label, modifier = Modifier.weight(1f),
                        ) { sel -> LayoutArt(v, sel) }
                    }
                }
            }
        }
        if (!s.booklet) {
            Spacer(Modifier.height(12.dp))
            DropdownField(
                "More pages per side", PAGES_PER_SIDE_CHOICES, s.pagesPerSide, { "$it ${if (it == 1) "page" else "pages"} per side" },
                { n -> actions.settings { it.copy(pagesPerSide = n) } },
            )
            if (s.pagesPerSide > 1) SwitchRow("Border around each page", s.borders, { b -> actions.settings { it.copy(borders = b) } })
        }
    }
}

@Composable
private fun ColourCard(s: PrintSettings, actions: Actions) {
    SectionCard("Colour & quality", step = 4) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceTile(s.color == ColorMode.COLOR, { actions.settings { it.copy(color = ColorMode.COLOR) } }, "Colour",
                Modifier.weight(1f), subtitle = "All inks") { ColorSwatch(false) }
            ChoiceTile(s.color == ColorMode.BW, { actions.settings { it.copy(color = ColorMode.BW) } }, "Black & white",
                Modifier.weight(1f), subtitle = "Saves colour ink") { ColorSwatch(true) }
        }
        Spacer(Modifier.height(16.dp))
        Segmented(
            "Quality", Quality.entries, s.quality, { q -> actions.settings { it.copy(quality = q) } },
            title = { it.label },
            subtitle = { when (it) { Quality.DRAFT -> "Saves ink"; Quality.STANDARD -> "Everyday"; Quality.HIGH -> "Photos" } },
        )
    }
}

@Composable
private fun PagesCard(state: UiState, actions: Actions) {
    val s = state.settings
    SectionCard("Pages & copies", step = 5) {
        var range by remember(s.pageRange.isEmpty()) { mutableStateOf(s.pageRange) }
        OutlinedTextField(
            value = range,
            onValueChange = { v -> range = v; actions.settings { it.copy(pageRange = v) } },
            label = { Text("Which pages") },
            placeholder = { Text("All pages") },
            supportingText = { Text(state.planError ?: "Leave empty for all. Example: 1-3, 5, 8-") },
            isError = state.planError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Stepper(s.copies, { n -> actions.settings { it.copy(copies = n.coerceIn(1, 999)) } }, "Copies", { plural(it, "copy", "copies") })
        }
        if (s.copies > 1) SwitchRow("Keep copies in sets", s.collate, { c -> actions.settings { it.copy(collate = c) } }, "1, 2, 3 · 1, 2, 3 instead of 1, 1 · 2, 2 · 3, 3")
    }
}

@Composable
private fun MoreOptions(s: PrintSettings, actions: Actions) {
    val p = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).border(1.dp, p.border, RoundedCornerShape(18.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { open = !open }
                .padding(16.dp).semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("More options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text("Paper, size, margins, order, two-sided set-up", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField("Paper size", Paper.entries, s.paper, { it.label }, { v -> actions.settings { it.copy(paper = v) } })
                DropdownField("Paper type", PaperType.entries, s.paperType, { it.label }, { v -> actions.settings { it.copy(paperType = v) } })
                DropdownField("Orientation", Orientation.entries, s.orientation, { it.label }, { v -> actions.settings { it.copy(orientation = v) } })
                DropdownField("Size on paper", Scaling.entries, s.scaling, { it.label }, { v -> actions.settings { it.copy(scaling = v) } })
                if (s.scaling == Scaling.CUSTOM) Stepper(
                    s.scalePercent,
                    { v -> actions.settings { it.copy(scalePercent = it.scalePercent + if (v > it.scalePercent) 5 else -5) } },
                    "Custom size (%)", { "$it percent" },
                )
                DropdownField(
                    "Margins", listOf(0f, 3f, 5f, 10f, 20f), s.marginMm,
                    { when (it) { 0f -> "None"; 3f -> "Narrow (3 mm)"; 5f -> "Normal (5 mm)"; 10f -> "Wide (10 mm)"; else -> "Extra wide (20 mm)" } },
                    { v -> actions.settings { it.copy(marginMm = v) } },
                )
                DropdownField("Print only", PageSubset.entries, s.pageSubset, { it.label }, { v -> actions.settings { it.copy(pageSubset = v) } })
                if (s.pagesPerSide > 1) DropdownField("Order of pages on a side", PageOrder.entries, s.pageOrder, { it.label }, { v -> actions.settings { it.copy(pageOrder = v) } })
                SwitchRow("Print the last page first", s.lastPageFirst, { v -> actions.settings { it.copy(lastPageFirst = v) } }, "Pages land in order, face up (one-sided printing)")
                SwitchRow("Start each file on a new sheet", s.filesOnNewSheet, { v -> actions.settings { it.copy(filesOnNewSheet = v) } })

                HorizontalDivider(color = p.border)
                Text("Two-sided printing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Segmented(
                    "How the paper is turned over", DuplexMethod.entries, s.duplexMethod, { v -> actions.settings { it.copy(duplexMethod = v) } },
                    title = { if (it == DuplexMethod.MANUAL) "Guided" else "Automatic" },
                    subtitle = { if (it == DuplexMethod.MANUAL) "I turn the pile (G3000)" else "Printer has duplex" },
                )
                SwitchRow("Turn back sides upside down", s.rotateBacks, { v -> actions.settings { it.copy(rotateBacks = v) } }, "Fix if backs come out upside down")
                SwitchRow("Print back sides in reverse order", s.reverseBacks, { v -> actions.settings { it.copy(reverseBacks = v) } }, "Fix if backs land on the wrong sheets")
                OutlinedButton(onClick = actions::runTest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.FlipToBack, null); Spacer(Modifier.width(8.dp)); Text("Run the two-sided test (2 sheets)")
                }
                TextButton(onClick = actions::reset) { Text("Reset all settings") }
            }
        }
    }
}

@Composable
private fun PrintBar(state: UiState, actions: Actions) {
    val p = LocalPalette.current
    val plan = state.plan
    val ready = plan != null && state.files.isNotEmpty() && state.planError == null && state.importing == 0
    Surface(color = p.surface, shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val main = when {
                state.files.isEmpty() -> "Choose a document to begin."
                state.planError != null -> "Check the page settings"
                plan != null -> describe(plan)
                else -> "Getting ready…"
            }
            val sub = when {
                state.planError != null -> state.planError
                plan != null -> buildList {
                    add(if (plan.selectedPages == plan.sourcePages) plural(plan.sourcePages, "page") else "${plan.selectedPages} of ${plan.sourcePages} pages")
                    if (plan.twoSided && plan.settings.duplexMethod == DuplexMethod.MANUAL) add("prints in two rounds: fronts, then backs")
                }.joinToString(" · ")
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(main, style = MaterialTheme.typography.titleSmall, maxLines = 3)
                    if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (state.planError != null) p.err else p.muted, maxLines = 2)
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { if (state.files.isEmpty()) actions.pickFiles() else actions.print() },
                    enabled = ready || state.files.isEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.heightIn(min = 58.dp).shadow(if (ready) 6.dp else 0.dp, RoundedCornerShape(16.dp), spotColor = p.accent),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                ) {
                    Icon(if (state.files.isEmpty()) Icons.Filled.FolderOpen else Icons.Filled.Print, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.files.isEmpty()) "Open" else "Print", style = MaterialTheme.typography.titleMedium, color = Color.Unspecified)
                }
            }
        }
    }
}
