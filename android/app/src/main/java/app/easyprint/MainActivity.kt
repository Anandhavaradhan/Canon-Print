package app.easyprint

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.easyprint.android.PrinterChoice
import app.easyprint.android.SavedPreset
import app.easyprint.android.OpenedFile
import app.easyprint.core.PrintSettings
import app.easyprint.ui.*
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.addUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm.activity = WeakReference(this)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
            var sheet by remember { mutableStateOf<String?>(null) }

            val actions = remember {
                object : Actions {
                    override fun pickFiles() = picker.launch(arrayOf("application/pdf", "image/*", "text/plain"))
                    override fun removeFile(f: OpenedFile) = vm.removeFile(f)
                    override fun moveFile(index: Int, delta: Int) = vm.moveFile(index, delta)
                    override fun settings(t: (PrintSettings) -> PrintSettings) = vm.setSettings(t)
                    override fun applySaved(p: SavedPreset) = vm.applySaved(p)
                    override fun deletePreset(p: SavedPreset) = vm.deletePreset(p)
                    override fun savePreset(name: String) = vm.savePreset(name)
                    override fun print() = vm.print()
                    override fun openPrinters() { sheet = "printers" }
                    override fun openDisplay() { sheet = "display" }
                    override fun openHistory() { sheet = "history" }
                    override fun openHelp() { sheet = "help" }
                    override fun runTest() { sheet = null; vm.runCalibration() }
                    override fun reset() = vm.resetSettings()
                }
            }

            EasyPrintTheme(state.look) {
                MainScreen(state, actions, vm::preview, snackbar)
                when (sheet) {
                    "printers" -> PrinterSheet(
                        state.printers, state.printer, state.status, state.searching,
                        onSelect = { vm.selectPrinter(it) },
                        onAdd = { addr, done -> vm.addManualPrinter(addr, done) },
                        onForget = { vm.forgetPrinter(it) },
                        onTest = { actions.runTest() },
                        onDismiss = { sheet = null },
                    )
                    "display" -> DisplaySheet(state.look, vm::setLook) { sheet = null }
                    "history" -> HistorySheet(state.history, { sheet = null; vm.reopen(it) }, vm::clearHistory) { sheet = null }
                    "help" -> HelpSheet { sheet = null }
                }
                state.wizard?.let { w ->
                    WizardDialog(
                        w, autoSpeak = state.look.speak, lastPageFirst = state.settings.lastPageFirst,
                        onFrontsOut = vm::goToTurnStep, onPrintBacks = vm::printBacks, onStop = vm::stopWizard,
                        onClose = vm::closeWizard, onCalibrated = vm::applyCalibration,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() { super.onStart(); vm.onStart() }
    override fun onStop() { super.onStop(); vm.onStop() }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u == null) {
                    // Shared plain text (no file): print it as a text document.
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        val f = java.io.File(cacheDir, "Shared text.txt").apply { writeText(text) }
                        listOf(Uri.fromFile(f))
                    } ?: emptyList()
                } else listOf(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.addUris(uris)
    }
}
