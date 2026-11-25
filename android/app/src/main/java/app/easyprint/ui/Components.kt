package app.easyprint.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    step: Int? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (step != null) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(p.accentSoft).border(1.dp, p.accentLine, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("$step", style = MaterialTheme.typography.labelMedium, color = p.accent, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            action?.invoke()
        }
        content()
    }
}

/** A big selectable card used for radio-style choices. */
@Composable
fun ChoiceTile(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    horizontal: Boolean = false,
    art: @Composable (selected: Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val border by animateColorAsState(if (selected) p.accent else p.border, label = "tileBorder")
    val bg = if (selected) p.accentSoft else p.surface2
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(if (selected) 2.dp else 1.5.dp, border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 56.dp)
    ) {
        if (horizontal) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                art(selected)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
                Spacer(Modifier.width(30.dp))
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                art(selected)
                Spacer(Modifier.height(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted, textAlign = TextAlign.Center)
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp).clip(CircleShape).background(p.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, tint = p.onAccent, modifier = Modifier.size(14.dp)) }
        }
    }
}

/** Two-line segmented control (radio group). */
@Composable
fun <T> Segmented(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    title: (T) -> String,
    subtitle: ((T) -> String)? = null,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(12.dp))
                .border(1.5.dp, p.border, RoundedCornerShape(12.dp))
                .selectableGroup()
        ) {
            options.forEachIndexed { i, o ->
                val sel = o == selected
                val bg by animateColorAsState(if (sel) p.accent else p.surface2, label = "seg")
                if (i > 0) Box(Modifier.width(1.5.dp).fillMaxHeight().background(p.border))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(bg)
                        .selectable(selected = sel, role = Role.RadioButton, onClick = { onSelect(o) })
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(title(o), style = MaterialTheme.typography.titleSmall, color = if (sel) p.onAccent else p.text, textAlign = TextAlign.Center)
                    if (subtitle != null) Text(
                        subtitle(o), style = MaterialTheme.typography.bodySmall,
                        color = if (sel) p.onAccent.copy(alpha = 0.85f) else p.muted, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, label: String, unit: (Int) -> String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).border(1.5.dp, p.border, RoundedCornerShape(12.dp)).background(p.surface2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onChange(value - 1) }, enabled = value > 1, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "One fewer")
            }
            Text(
                "$value", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 48.dp).semantics {
                    contentDescription = unit(value); liveRegion = LiveRegionMode.Polite
                },
            )
            IconButton(onClick = { onChange(value + 1) }, enabled = value < 999, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "One more")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = text(selected), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(text(o)) },
                    onClick = { onSelect(o); open = false },
                    leadingIcon = if (o == selected) ({ Icon(Icons.Filled.Check, null) }) else null,
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun StatusDot(state: String, size: Dp = 10.dp) {
    val p = LocalPalette.current
    val c = when (state) {
        "ready" -> p.ok
        "busy" -> p.accent
        "warning" -> p.warn
        "error" -> p.err
        else -> p.borderStrong
    }
    Box(Modifier.size(size).clip(CircleShape).background(c))
}

/** Ink tanks from the printer's marker-levels (only shown if the printer reports them). */
@Composable
fun InkBars(markers: List<app.easyprint.core.PrinterInfo.Marker>, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val known = markers.filter { it.level in 0..100 }
    if (known.isEmpty()) return
    val desc = known.joinToString { "${it.name}: ${it.level}%" }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Ink levels. $desc" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        known.forEach { m ->
            val color = runCatching { Color(android.graphics.Color.parseColor(m.color.split('#').getOrNull(1)?.let { "#$it" } ?: m.color)) }
                .getOrDefault(p.muted)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 22.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(p.surface3).border(1.dp, p.border, RoundedCornerShape(4.dp))) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(m.level / 100f).background(color))
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(p.surface2)
            .border(1.dp, p.border, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = if (leading != null) 12.dp else 16.dp, end = if (trailing != null) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
        trailing?.invoke()
    }
}
