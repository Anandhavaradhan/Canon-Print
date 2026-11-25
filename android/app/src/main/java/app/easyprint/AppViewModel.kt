package app.easyprint

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.easyprint.android.*
import app.easyprint.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

data class PrinterStatus(
    val state: String = "unknown", // ready | busy | warning | error | unknown
    val message: String = "",
    val markers: List<PrinterInfo.Marker> = emptyList(),
    val model: String = "",
)

enum class WizardStep { PREPARING, FRONTS, TURN, BACKS, DONE, CALIBRATE, ERROR }

data class Wizard(
    val step: WizardStep,
    val sheets: Int = 0,
    val removeTopSheet: Boolean = false,
    val calibration: Boolean = false,
    val progress: String? = null,
    /** The printer reported the fronts as finished (direct printing only). */
    val frontsDone: Boolean = false,
    val message: String? = null,
    val twoPass: Boolean = false,
)

data class UiState(
    val files: List<OpenedFile> = emptyList(),
    val importing: Int = 0,
    val settings: PrintSettings = PrintSettings(),
    val plan: Plan? = null,
    val planError: String? = null,
    val printers: List<PrinterChoice> = listOf(PrinterChoice.System),
    val printer: PrinterChoice = PrinterChoice.System,
    val status: PrinterStatus = PrinterStatus(),
    val wizard: Wizard? = null,
    val look: LookPrefs = LookPrefs(),
    val presets: List<SavedPreset> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val searching: Boolean = true,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val _state = MutableStateFlow(
        UiState(settings = store.settings, look = store.look, presets = store.presets, history = store.history)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    var activity: WeakReference<Activity>? = null

    private var renderer: DocRenderer? = null
    private var discovered: List<PrinterChoice.Direct> = emptyList()
    private val discovery = PrinterDiscovery(app) { list -> discovered = list; refreshPrinterList() }
    private var statusJob: Job? = null
    private var planJob: Job? = null
    private var printerInfo: PrinterInfo? = null

    private class Pending(
        val renderer: DocRenderer, val plan: Plan, val jobs: JobPlan, val printer: PrinterChoice,
        val title: String, var next: Int = 0, var jobId: Int = 0,
    )
    private var pending: Pending? = null
    private var pollJob: Job? = null

    init {
        refreshPrinterList()
        pruneStorage()
    }

    private fun update(f: (UiState) -> UiState) = _state.update(f)
    private fun say(msg: String) { _messages.tryEmit(msg) }

    // ------------------------------------------------------------------ lifecycle
    fun onStart() {
        discovery.start()
        update { it.copy(searching = true) }
        viewModelScope.launch { delay(8000); update { it.copy(searching = false) } }
        startStatusPolling()
    }

    fun onStop() {
        discovery.stop()
        statusJob?.cancel()
    }

    override fun onCleared() {
        discovery.stop()
        renderer?.close()
    }

    // ------------------------------------------------------------------ printers
    private fun refreshPrinterList() {
        val manual = store.manualPrinters
        val all = (discovered + manual.filter { m -> discovered.none { it.host == m.host } })
            .sortedWith(compareBy({ !isCanonG(it.name) }, { it.name.lowercase() }))
        val list: List<PrinterChoice> = all + PrinterChoice.System
        val current = _state.value.printer
        val wanted = store.lastPrinter
        val chosen = list.firstOrNull { it.key == current.key && current !is PrinterChoice.System }
            ?: list.firstOrNull { it.key == wanted }
            ?: list.firstOrNull { it is PrinterChoice.Direct && isCanonG(it.label) }
            ?: if (current is PrinterChoice.System && wanted == "system") PrinterChoice.System
            else list.firstOrNull { it is PrinterChoice.Direct } ?: PrinterChoice.System
        val changed = chosen.key != current.key
        update { it.copy(printers = list, printer = chosen) }
        if (changed) { printerInfo = null; startStatusPolling() }
    }

    fun selectPrinter(p: PrinterChoice) {
        store.lastPrinter = p.key
        printerInfo = null
        update { it.copy(printer = p, status = PrinterStatus()) }
        startStatusPolling()
    }

    fun addManualPrinter(address: String, onResult: (Boolean) -> Unit) {
        val clean = address.trim().removePrefix("ipp://").removePrefix("http://").trimEnd('/')
        val host = clean.substringBefore('/').substringBefore(':')
        val port = clean.substringBefore('/').substringAfter(':', "631").toIntOrNull() ?: 631
        val path = clean.substringAfter('/', "ipp/print").ifBlank { "ipp/print" }
        if (host.isBlank()) { onResult(false); return }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { IppClient(host, port, path, 6000).getPrinterAttributes() }.getOrNull() }
            if (info == null) {
                say("No printer answered at $host. Check the address on the printer’s network settings page.")
                onResult(false)
                return@launch
            }
            val p = PrinterChoice.Direct(info.makeAndModel, host, port, path, manual = true)
            store.manualPrinters = store.manualPrinters.filter { it.host != host } + p
            refreshPrinterList()
            selectPrinter(p)
            say("Added ${info.makeAndModel}.")
            onResult(true)
        }
    }

    fun forgetPrinter(p: PrinterChoice.Direct) {
        store.manualPrinters = store.manualPrinters.filter { it.host != p.host }
        refreshPrinterList()
    }

    private fun startStatusPolling() {
        statusJob?.cancel()
        val p = _state.value.printer
        if (p !is PrinterChoice.Direct) {
            update { it.copy(status = PrinterStatus("ready", "Choose a printer in Android’s print screen", emptyList())) }
            return
        }
        statusJob = viewModelScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { runCatching { p.client().getPrinterAttributes() }.getOrNull() }
                if (_state.value.printer.key != p.key) break
                if (info == null) {
                    update { it.copy(status = PrinterStatus("error", "Can’t reach the printer. Is it on and on the same Wi-Fi?", emptyList())) }
                } else {
                    printerInfo = info
                    val (st, msg) = describePrinterState(info)
                    val note = if (!info.supportsPwg) "This printer needs Android’s print screen" else msg
                    update { it.copy(status = PrinterStatus(if (!info.supportsPwg) "warning" else st, note, info.markers, info.makeAndModel)) }
                }
                delay(if (_state.value.wizard != null) 3000 else 7000)
            }
        }
    }

    // ------------------------------------------------------------------ files
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            for (uri in uris) {
                update { it.copy(importing = it.importing + 1) }
                try {
                    val f = withContext(Dispatchers.IO) { Documents.import(getApplication(), uri) }
                    update { it.copy(files = it.files + f) }
                    say("${f.name} is ready · ${plural(f.pages.size, "page")}")
                } catch (e: ImportException) {
                    say(e.message ?: "That file couldn’t be opened.")
                } catch (e: Exception) {
                    say("That file couldn’t be opened.")
                } finally {
                    update { it.copy(importing = it.importing - 1) }
                }
            }
            filesChanged()
        }
    }

    fun removeFile(f: OpenedFile) {
        update { it.copy(files = it.files - f) }
        filesChanged()
    }

    fun moveFile(index: Int, delta: Int) {
        val list = _state.value.files.toMutableList()
        val j = index + delta
        if (j !in list.indices) return
        list.add(j, list.removeAt(index))
        update { it.copy(files = list) }
        filesChanged()
    }

    fun clearFiles() { update { it.copy(files = emptyList()) }; filesChanged() }

    private fun filesChanged() {
        val old = renderer
        renderer = _state.value.files.takeIf { it.isNotEmpty() }?.let { DocRenderer(it) }
        if (old != null) viewModelScope.launch { delay(5000); old.close() }
        replan()
    }

    // ------------------------------------------------------------------ settings & plan
    fun setSettings(transform: (PrintSettings) -> PrintSettings) {
        val s = transform(_state.value.settings).normalized()
        update { it.copy(settings = s) }
        store.settings = s
        replan()
    }

    fun resetSettings() = setSettings { PrintSettings(rotateBacks = it.rotateBacks, reverseBacks = it.reverseBacks, duplexMethod = it.duplexMethod) }

    private fun replan() {
        planJob?.cancel()
        val r = renderer
        val s = _state.value.settings
        if (r == null) { update { it.copy(plan = null, planError = null) }; return }
        planJob = viewModelScope.launch(Dispatchers.Default) {
            delay(60)
            try {
                val plan = makePlan(r.pageSizes, s, r.fileStarts)
                update { it.copy(plan = plan, planError = null) }
            } catch (e: LayoutException) {
                update { it.copy(planError = e.message) }
            }
        }
    }

    /** Render a preview of one side. Null while the document changes. */
    suspend fun preview(plan: Plan, sheet: Int, back: Boolean, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        val r = renderer ?: return@withContext null
        val sh = plan.sheets.getOrNull(sheet) ?: return@withContext null
        val side = if (back) sh.back else sh.front
        val landscape = (side ?: sh.front).landscape
        runCatching { renderPreview(r, plan, side, landscape, widthPx) }.getOrNull()
    }

    // ------------------------------------------------------------------ presets & look
    fun savePreset(name: String) {
        val list = store.presets.filter { !it.name.equals(name, true) } + SavedPreset(UUID.randomUUID().toString().take(8), name, _state.value.settings)
        store.presets = list
        update { it.copy(presets = list) }
        say("Saved “$name” to Quick picks.")
    }

    fun deletePreset(p: SavedPreset) {
        val list = store.presets.filter { it.id != p.id }
        store.presets = list
        update { it.copy(presets = list) }
        say("Deleted “${p.name}”.")
    }

    fun applySaved(p: SavedPreset) = setSettings { cur ->
        p.settings.copy(rotateBacks = cur.rotateBacks, reverseBacks = cur.reverseBacks, duplexMethod = cur.duplexMethod)
    }

    fun setLook(l: LookPrefs) { store.look = l; update { it.copy(look = l) } }

    // ------------------------------------------------------------------ history
    fun reopen(e: HistoryEntry) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                e.files.mapNotNull { hf ->
                    runCatching { Documents.fromFile(UUID.randomUUID().toString().take(12), hf.name, File(hf.path), hf.kind) }.getOrNull()
                }
            }
            if (files.isEmpty()) { say("That print’s files are no longer available."); return@launch }
            update { it.copy(files = files, settings = e.settings) }
            store.settings = e.settings
            filesChanged()
            say("Loaded “${e.title}” with the same settings.")
        }
    }

    fun clearHistory() { store.history = emptyList(); update { it.copy(history = emptyList()) }; pruneStorage() }

    private fun addHistory(title: String, plan: Plan, printer: PrinterChoice) {
        val files = _state.value.files.map { HistoryFile(it.name, it.file.path, it.kind) }
        val entry = HistoryEntry(UUID.randomUUID().toString().take(10), System.currentTimeMillis(), title, files,
            plan.settings, printer.label, plan.physicalSheets)
        val list = (listOf(entry) + store.history).take(15)
        store.history = list
        update { it.copy(history = list) }
    }

    private fun pruneStorage() = viewModelScope.launch(Dispatchers.IO) {
        val keep = store.history.flatMap { h -> h.files.map { it.path } }.toSet() + _state.value.files.map { it.file.path }
        Documents.docsDir(getApplication()).listFiles()?.forEach { if (it.path !in keep && System.currentTimeMillis() - it.lastModified() > 60_000) it.delete() }
    }

    // ------------------------------------------------------------------ printing
    private fun jobTitle(): String {
        val f = _state.value.files
        return when {
            f.isEmpty() -> "Printout"
            f.size == 1 -> f[0].name.substringBeforeLast('.')
            else -> "${f[0].name.substringBeforeLast('.')} + ${f.size - 1} more"
        }
    }

    fun print() {
        val st = _state.value
        val plan = st.plan ?: return
        if (st.files.isEmpty() || st.wizard != null) return
        val jobs = buildJobs(plan)
        val p = Pending(DocRenderer(st.files), plan, jobs, st.printer, jobTitle())
        addHistory(p.title, plan, st.printer)
        runPending(p, calibration = false)
    }

    fun runCalibration() {
        if (_state.value.wizard != null) return
        viewModelScope.launch {
            val file = File(getApplication<Application>().cacheDir, "two-sided-test.pdf")
            val doc = withContext(Dispatchers.IO) {
                Documents.calibrationPdf(file)
                Documents.fromFile("calibration", "Two-sided test", file, DocKind.PDF)
            }
            val s = _state.value.settings.copy(
                sides = Sides.LONG, pagesPerSide = 1, booklet = false, copies = 1, pageRange = "",
                pageSubset = PageSubset.ALL, scaling = Scaling.FIT, orientation = Orientation.AUTO,
                duplexMethod = DuplexMethod.MANUAL, quality = Quality.DRAFT, color = ColorMode.BW,
            )
            val r = DocRenderer(listOf(doc))
            val plan = makePlan(r.pageSizes, s)
            runPending(Pending(r, plan, buildJobs(plan), _state.value.printer, "Two-sided test"), calibration = true)
        }
    }

    private fun runPending(p: Pending, calibration: Boolean) {
        pending = p
        val twoPass = p.jobs.manualDuplex && p.jobs.passes.size > 1
        update { it.copy(wizard = Wizard(WizardStep.PREPARING, p.jobs.sheets, p.jobs.removeTopSheet, calibration, "Getting ready…", twoPass = twoPass)) }
        viewModelScope.launch {
            val ok = sendNext(p)
            if (!ok) return@launch
            if (twoPass) {
                update { it.copy(wizard = it.wizard?.copy(step = WizardStep.FRONTS, progress = null)) }
                watchJob(p)
            } else {
                finish(p)
                update { it.copy(wizard = it.wizard?.copy(step = WizardStep.DONE, progress = null)) }
            }
        }
    }

    /** Called when the user has put the pile back in. */
    fun printBacks() {
        val p = pending ?: return
        pollJob?.cancel()
        update { it.copy(wizard = it.wizard?.copy(step = WizardStep.BACKS, progress = "Getting ready…")) }
        viewModelScope.launch {
            if (!sendNext(p)) return@launch
            val cal = _state.value.wizard?.calibration == true
            finish(p)
            update { it.copy(wizard = it.wizard?.copy(step = if (cal) WizardStep.CALIBRATE else WizardStep.DONE, progress = null)) }
        }
    }

    fun goToTurnStep() = update { it.copy(wizard = it.wizard?.copy(step = WizardStep.TURN)) }

    fun stopWizard() {
        val p = pending
        pollJob?.cancel()
        if (p != null && p.next < p.jobs.passes.size) say("Stopped. The back sides weren’t printed.")
        pending?.let { finish(it) }
        update { it.copy(wizard = null) }
    }

    fun closeWizard() { pending?.let { finish(it) }; update { it.copy(wizard = null) } }

    fun applyCalibration(orderOk: Boolean, upOk: Boolean) {
        setSettings { s -> s.copy(reverseBacks = if (orderOk) s.reverseBacks else !s.reverseBacks, rotateBacks = if (upOk) s.rotateBacks else !s.rotateBacks) }
        update { it.copy(wizard = null) }
        say(if (orderOk && upOk) "Your printer is set up correctly. Nothing to change."
            else "Fixed! Two-sided printing is now adjusted for your printer.")
    }

    private fun finish(p: Pending) {
        if (pending === p) pending = null
        viewModelScope.launch { delay(2000); p.renderer.close() }
    }

    private suspend fun sendNext(p: Pending): Boolean {
        val pass = p.jobs.passes[p.next]
        val suffix = if (p.jobs.passes.size > 1) " (${pass.label.lowercase()})" else ""
        val name = p.title + suffix
        val cache = getApplication<Application>().cacheDir
        val progress: (Int, Int) -> Unit = { i, n -> update { it.copy(wizard = it.wizard?.copy(progress = "Preparing page $i of $n…")) } }
        return try {
            when (val printer = p.printer) {
                is PrinterChoice.Direct -> {
                    val info = withContext(Dispatchers.IO) { runCatching { printer.client().getPrinterAttributes() }.getOrNull() } ?: printerInfo
                    if (info != null && !info.supportsPwg) throw IllegalStateException(
                        "This printer can’t be printed to directly. Choose “Other printers & Save as PDF” and use the Canon or Mopria print service."
                    )
                    val file = File(cache, "pass-${p.next}.pwg")
                    withContext(Dispatchers.Default) { DirectOutput.writeRaster(p.renderer, p.plan, pass, info, p.plan.settings, file, progress) }
                    update { it.copy(wizard = it.wizard?.copy(progress = "Sending to the printer…")) }
                    p.jobId = withContext(Dispatchers.IO) { printer.client().printJob(file, DirectOutput.jobOptions(info, p.plan.settings, pass, name)) }
                    file.delete()
                }
                PrinterChoice.System -> {
                    val file = File(cache, "pass-${p.next}.pdf")
                    withContext(Dispatchers.Default) { SystemOutput.writePdf(p.renderer, p.plan, pass, p.plan.settings, file, progress) }
                    val act = activity?.get() ?: throw IllegalStateException("Open the app and try again.")
                    SystemOutput.print(act, name, file, p.plan.settings, pass.sides, pass.pages.size)
                }
            }
            p.next++
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = when (e) {
                is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                    "The printer didn’t answer. Check it’s turned on and on the same Wi-Fi as this phone."
                is OutOfMemoryError -> "This job is too big for this phone at High quality. Try Standard quality."
                else -> e.message ?: "Printing failed."
            }
            update { it.copy(wizard = it.wizard?.copy(step = WizardStep.ERROR, message = msg, progress = null)) }
            finish(p)
            false
        }
    }

    private fun watchJob(p: Pending) {
        pollJob?.cancel()
        val printer = p.printer as? PrinterChoice.Direct ?: return
        if (p.jobId <= 0) return
        pollJob = viewModelScope.launch {
            repeat(400) {
                delay(2500)
                val st = withContext(Dispatchers.IO) { runCatching { printer.client().jobState(p.jobId) }.getOrNull() } ?: return@repeat
                when (st.first) {
                    9 -> { update { it.copy(wizard = it.wizard?.copy(frontsDone = true)) }; return@launch }
                    7, 8 -> {
                        update { it.copy(wizard = it.wizard?.copy(message = "The printer stopped this job. Check the printer’s screen.")) }
                        return@launch
                    }
                }
            }
        }
    }
}
