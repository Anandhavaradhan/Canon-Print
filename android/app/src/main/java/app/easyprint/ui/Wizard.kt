package app.easyprint.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.easyprint.Wizard
import app.easyprint.WizardStep
import app.easyprint.core.plural
import java.util.Locale

/** Speaks instructions aloud (accessibility). */
class Speaker(context: android.content.Context) {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { ready = it == TextToSpeech.SUCCESS }.apply {
        language = Locale.getDefault(); setSpeechRate(0.95f)
    }
    fun say(text: String) { if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "easyprint") }
    fun stop() = runCatching { tts.stop() }
    fun shutdown() = runCatching { tts.shutdown() }
}

@Composable
fun rememberSpeaker(): Speaker {
    val ctx = LocalContext.current
    val s = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { s.shutdown() } }
    return s
}

@Composable
fun WizardDialog(
    w: Wizard,
    autoSpeak: Boolean,
    lastPageFirst: Boolean,
    onFrontsOut: () -> Unit,
    onPrintBacks: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onCalibrated: (Boolean, Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val speaker = rememberSpeaker()
    val total = if (w.twoPass) 3 else 0
    val stepNo = when (w.step) {
        WizardStep.PREPARING, WizardStep.FRONTS -> 1
        WizardStep.TURN -> 2
        WizardStep.BACKS, WizardStep.DONE, WizardStep.CALIBRATE -> 3
        WizardStep.ERROR -> 0
    }
    val (title, spoken) = wizardText(w, lastPageFirst)
    LaunchedEffect(w.step) { if (autoSpeak) speaker.say("$title. $spoken") }

    Dialog(
        onDismissRequest = { if (w.step == WizardStep.DONE || w.step == WizardStep.ERROR) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        WizardContent(w, lastPageFirst, speaker, onFrontsOut, onPrintBacks, onStop, onClose, onCalibrated)
    }
}

@Composable
fun WizardContent(
    w: Wizard,
    lastPageFirst: Boolean,
    speaker: Speaker?,
    onFrontsOut: () -> Unit,
    onPrintBacks: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onCalibrated: (Boolean, Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val total = if (w.twoPass) 3 else 0
    val stepNo = when (w.step) {
        WizardStep.PREPARING, WizardStep.FRONTS -> 1
        WizardStep.TURN -> 2
        WizardStep.BACKS, WizardStep.DONE, WizardStep.CALIBRATE -> 3
        WizardStep.ERROR -> 0
    }
    val (title, spoken) = wizardText(w, lastPageFirst)
    run {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = p.surface,
            border = if (p.highContrast) androidx.compose.foundation.BorderStroke(3.dp, p.text) else null,
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (total > 0 && w.step != WizardStep.ERROR) {
                    Row(Modifier.fillMaxWidth().semantics { contentDescription = "Step $stepNo of $total" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(total) { i ->
                            Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)).background(if (i < stepNo) p.accent else p.surface3))
                        }
                    }
                }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

                when (w.step) {
                    WizardStep.PREPARING, WizardStep.BACKS -> {
                        StatusLine(w.progress ?: "Sending…")
                        if (w.step == WizardStep.PREPARING && w.twoPass)
                            Text("First the fronts of ${plural(w.sheets, "sheet")} will print.", style = MaterialTheme.typography.bodyLarge)
                    }
                    WizardStep.FRONTS -> {
                        if (w.frontsDone) DoneLine("The printer says the fronts are finished.")
                        else StatusLine("Printing the front sides of ${plural(w.sheets, "sheet")}…")
                        Text("Wait until all ${plural(w.sheets, "sheet")} have come out of the printer.", style = MaterialTheme.typography.bodyLarge)
                        Text("Leave them in the tray for now, and don’t change their order.", style = MaterialTheme.typography.bodyMedium, color = p.muted)
                        w.message?.let { ErrorLine(it) }
                    }
                    WizardStep.TURN -> {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface2)
                                .border(1.dp, p.border, RoundedCornerShape(16.dp)).padding(14.dp)
                        ) { TurnPileArt(Modifier.fillMaxWidth().height(130.dp)) }
                        TurnSteps(w.removeTopSheet)
                    }
                    WizardStep.DONE -> {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(p.ok.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, null, tint = p.ok, modifier = Modifier.size(36.dp))
                            }
                        }
                        Text(spoken, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        if (w.twoPass && !w.calibration) Text(
                            "Backs upside down or on the wrong sheets? Run the two-sided test once from the printer menu.",
                            style = MaterialTheme.typography.bodySmall, color = p.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    WizardStep.CALIBRATE -> CalibrationQuestions(onCalibrated, onClose)
                    WizardStep.ERROR -> ErrorLine(w.message ?: "Something went wrong.")
                }

                if (w.step != WizardStep.CALIBRATE) Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (w.step in listOf(WizardStep.FRONTS, WizardStep.TURN)) {
                        IconButton(onClick = { speaker?.say("$title. $spoken") }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Read aloud")
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { speaker?.stop(); onStop() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
                    }
                    when (w.step) {
                        WizardStep.FRONTS -> Button(onClick = onFrontsOut, modifier = Modifier.heightIn(min = 52.dp)) {
                            Text(if (w.calibration) "Both are out" else "All fronts are out")
                        }
                        WizardStep.TURN -> Button(onClick = { speaker?.stop(); onPrintBacks() }, modifier = Modifier.heightIn(min = 52.dp)) {
                            Icon(Icons.Filled.Print, null); Spacer(Modifier.width(8.dp)); Text("Print the backs")
                        }
                        WizardStep.DONE, WizardStep.ERROR -> Button(onClick = onClose, modifier = Modifier.heightIn(min = 52.dp)) { Text(if (w.step == WizardStep.DONE) "Done" else "Close") }
                        else -> {}
                    }
                }
            }
        }
    }
}

private fun wizardText(w: Wizard, lastPageFirst: Boolean): Pair<String, String> = when (w.step) {
    WizardStep.PREPARING -> (if (w.twoPass) "Step 1 of 3 · Printing the fronts" else "Printing") to (w.progress ?: "")
    WizardStep.FRONTS -> (if (w.calibration) "Two-sided test · Step 1" else "Step 1 of 3 · Printing the fronts") to
        "Wait until all ${plural(w.sheets, "sheet")} have come out of the printer. Leave them in the tray and don’t change their order."
    WizardStep.TURN -> "Step 2 of 3 · Put the pile back in" to buildString {
        if (w.removeTopSheet) append("Take the top sheet off the pile and put it aside. It’s the last page and has nothing on the back. ")
        append("Pick up the printed pile and keep it in the same order. Turn the whole pile over like a page in a book, from left to right. ")
        append("Put it in the rear paper tray, top edge going in first, with no other paper in the tray.")
    }
    WizardStep.BACKS -> "Step 3 of 3 · Printing the backs" to (w.progress ?: "")
    WizardStep.DONE -> if (w.twoPass) "All done!" to
        "Your ${plural(w.sheets, "sheet")} are printing on the back now. When they’re out, turn the pile over like a book page. Page 1 will be on top."
    else "Sent to the printer" to ("${plural(w.sheets, "sheet")} on the way." + if (lastPageFirst) " Pages will land in order, face up." else "")
    WizardStep.CALIBRATE -> "Two-sided test · Check the result" to
        "Pick up the sheet with page 1 and turn it over like a book page. Is page 2 on the back of page 1? Is page 2 the right way up?"
    WizardStep.ERROR -> "Couldn’t print" to (w.message ?: "")
}

@Composable
private fun StatusLine(text: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface2).border(1.dp, p.border, RoundedCornerShape(12.dp))
            .padding(14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DoneLine(text: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.ok.copy(alpha = 0.12f)).padding(14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, null, tint = p.ok)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorLine(text: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.err.copy(alpha = 0.12f)).padding(14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = p.err)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TurnSteps(removeTop: Boolean) {
    val p = LocalPalette.current
    val steps = buildList {
        if (removeTop) add(true to buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Take the top sheet off the pile") }
            append(" and put it aside. It’s the last page and has nothing on the back.")
        })
        add(false to buildAnnotatedString {
            append("Pick up the printed pile. "); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Keep it in the same order.") }
        })
        add(false to buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Turn the whole pile over like a page in a book") }
            append(" (left to right). The printed side now faces down, and the top of the pages still points away from you.")
        })
        add(false to buildAnnotatedString {
            append("Put it in the "); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("rear paper tray") }
            append(", top edge going in first, with no other paper in the tray.")
        })
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var n = 0
        steps.forEach { (attention, text) ->
            if (!attention) n++
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(if (attention) p.warn else p.accent),
                    contentAlignment = Alignment.Center,
                ) { Text(if (attention) "!" else "$n", color = if (attention) androidx.compose.ui.graphics.Color.White else p.onAccent, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalibrationQuestions(onSave: (Boolean, Boolean) -> Unit, onSkip: () -> Unit) {
    var order by remember { mutableStateOf(true) }
    var up by remember { mutableStateOf(true) }
    Text("Pick up the sheet with page 1 and turn it over like a book page.", style = MaterialTheme.typography.bodyLarge)
    YesNo("Is page 2 on the back of page 1?", order) { order = it }
    YesNo("Is page 2 the right way up (same top as page 1)?", up) { up = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        OutlinedButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip") }
        Button(onClick = { onSave(order, up) }, modifier = Modifier.heightIn(min = 52.dp)) { Text("Save") }
    }
}

@Composable
private fun YesNo(question: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, p.border, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        Text(question, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.selectableGroup().padding(top = 4.dp)) {
            listOf(true to "Yes", false to "No").forEach { (v, label) ->
                Row(
                    Modifier.selectable(selected = value == v, role = Role.RadioButton, onClick = { onChange(v) }).heightIn(min = 48.dp).padding(end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == v, onClick = null)
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
