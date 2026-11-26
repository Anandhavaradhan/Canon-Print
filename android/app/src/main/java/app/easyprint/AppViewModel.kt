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
    /** Backs were fed in reverse order (calibrated printer): the finished pile ends up reversed. */
    val reversedBacks: Boolean = false,
)

data class UiState(
    val files: List<OpenedFile> = emptyList(),
    val importing: Int = 0,
    val settings: PrintSettings = PrintSettings(),
    val plan: Plan? = null,
    val planError: String? = null,
    val printers: List<PrinterChoice> = emptyList(),
    /** null until a printer has been found or added */
    val printer: PrinterChoice? = null,
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

    private var renderer: DocRenderer? = null
    private var discovered: List<PrinterChoice.Direct> = emptyList()
    private var canonFound: List<PrinterChoice.Canon> = emptyList()
    private val discovery = PrinterDiscovery(app) { list -> discovered = list; refreshPrinterList() }
    private var scanJob: Job? = null
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
        scanForCanon()
        startStatusPolling()
    }

    fun onStop() {
        discovery.stop()
        scanJob?.cancel()
        statusJob?.cancel()
    }

    override fun onCleared() {
        discovery.stop()
        renderer?.close()
    }

    // ------------------------------------------------------------------ printers
    /** Look for Canon printers on the Wi-Fi the way Canon's own software does (SNMP). */
    fun scanForCanon() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            update { it.copy(searching = true) }
            repeat(3) { round ->
                val hosts = withContext(Dispatchers.IO) { CanonScanner.candidates(getApplication()) }
                val found = withContext(Dispatchers.IO) { runCatching { CanonScanner.probe(hosts) }.getOrDefault(emptyList()) }
                if (found.isNotEmpty()) {
                    canonFound = found.map { PrinterChoice.Canon(it.model, it.host, it.mac) }
                    refreshPrinterList()
                }
                if (round == 0) update { it.copy(searching = false) }
                if (canonFound.isNotEmpty()) return@launch
                delay(4000)
            }
            update { it.copy(searching = false) }
        }
    }

    private fun refreshPrinterList() {
        val manual = store.manualPrinters
        // A Canon printer found by SNMP is better than the same machine seen over IPP.
        val canon = (canonFound + manual.filterIsInstance<PrinterChoice.Canon>())
            .distinctBy { it.host }
        val ipp = (discovered + manual.filterIsInstance<PrinterChoice.Direct>())
            .distinctBy { it.host }.filter { d -> canon.none { it.host == d.host } }
        val list: List<PrinterChoice> = (canon.sortedBy { !isCanonG(it.name) && !it.name.contains("G3", true) } + ipp.sortedWith(compareBy({ !isCanonG(it.name) }, { it.name.lowercase() })))
        val current = _state.value.printer
        val wanted = store.lastPrinter
        // Same printer may come back with a new address (DHCP): match a remembered Canon by MAC too.
        val chosen = list.firstOrNull { it.key == current?.key }
            ?: list.firstOrNull { it.key == wanted }
            ?: list.firstOrNull { it is PrinterChoice.Canon && it.manual && wanted?.startsWith("canon-mac:") == true && it.mac.isBlank() }
            ?: list.firstOrNull { it is PrinterChoice.Canon && wanted?.startsWith("canon-mac:") == true && wanted.removePrefix("canon-mac:") == it.mac }
            ?: list.firstOrNull()
        val changed = chosen?.key != current?.key
        update { it.copy(printers = list, printer = chosen) }
        if (changed) { printerInfo = null; startStatusPolling() }
    }

    fun selectPrinter(p: PrinterChoice) {
        store.lastPrinter = if (p is PrinterChoice.Canon && p.mac.isNotBlank()) "canon-mac:${p.mac}" else p.key
        printerInfo = null
        update { it.copy(printer = p, status = PrinterStatus()) }
        startStatusPolling()
    }

    /** Add a printer by IP address. Canon's own protocol is tried first, IPP second. */
    fun addManualPrinter(address: String, onResult: (Boolean) -> Unit) {
        val clean = address.trim().removePrefix("ipp://").removePrefix("http://").removePrefix("socket://").trimEnd('/')
        val host = clean.substringBefore('/').substringBefore(':')
        val explicitPort = clean.substringBefore('/').substringAfter(':', "").toIntOrNull()
        if (host.isBlank()) { onResult(false); return }
        viewModelScope.launch {
            val added: PrinterChoice? = withContext(Dispatchers.IO) {
                val canonPort = explicitPort?.takeIf { it != 631 } ?: CanonG3000.PORT_RAW
                val snmp = CanonG3000.parseDiscovery(host, runCatching {
                    Snmp.get(host, CanonG3000.COMMUNITY_CANON, CanonG3000.DISCOVERY_OIDS, 2500)
                }.getOrNull())
                when {
                    snmp != null -> PrinterChoice.Canon(snmp.model, host, snmp.mac, manual = true, port = canonPort)
                    explicitPort != 631 && CanonOutput.reachable(host, canonPort) ->
                        PrinterChoice.Canon("Canon printer at $host", host, manual = true, port = canonPort)
                    else -> {
                        val ippPort = explicitPort ?: 631
                        val path = clean.substringAfter('/', "ipp/print").ifBlank { "ipp/print" }
                        runCatching { IppClient(host, ippPort, path, 6000).getPrinterAttributes() }.getOrNull()
                            ?.let { PrinterChoice.Direct(it.makeAndModel, host, ippPort, path, manual = true) }
                    }
                }
            }
            if (added == null) {
                say("No printer answered at $host. Check the address in your Wi-Fi router’s list of devices, and that the printer is on.")
                onResult(false)
                return@launch
            }
            store.manualPrinters = store.manualPrinters.filter { it.host() != host } + added
            refreshPrinterList()
            selectPrinter(added)
            say("Added ${added.label}.")
            onResult(true)
        }
    }

    fun forgetPrinter(p: PrinterChoice) {
        store.manualPrinters = store.manualPrinters.filter { it.key != p.key }
        canonFound = canonFound.filter { it.key != p.key }
        refreshPrinterList()
    }

    private fun startStatusPolling() {
        statusJob?.cancel()
        val p = _state.value.printer
        if (p == null) {
            update { it.copy(status = PrinterStatus("unknown", "", emptyList())) }
            return
        }
        statusJob = viewModelScope.launch {
            while (isActive) {
                val status = withContext(Dispatchers.IO) { readStatus(p) }
                if (_state.value.printer?.key != p.key) break
                update { it.copy(status = status) }
                delay(if (_state.value.wizard != null) 4000 else 8000)
            }
        }
    }

    private fun readStatus(p: PrinterChoice): PrinterStatus = when (p) {
        is PrinterChoice.Canon -> {
            val r = runCatching {
                Snmp.get(p.host, CanonG3000.COMMUNITY_PUBLIC, listOf(CanonG3000.OID_HR_PRINTER_STATUS, CanonG3000.OID_HR_ERROR_STATE), 2000)
            }.getOrNull()
            if (r != null && r.error == 0) {
                val st = (r.get(CanonG3000.OID_HR_PRINTER_STATUS) as? Snmp.Value.Int32)?.v
                val err = (r.get(CanonG3000.OID_HR_ERROR_STATE) as? Snmp.Value.Bytes)?.v
                val (state, msg) = CanonG3000.describeStatus(st, err)
                PrinterStatus(state, msg, readInk(p.host), p.name)
            } else if (CanonOutput.reachable(p.host, p.port)) {
                PrinterStatus("ready", "Connected", emptyList(), p.name)
            } else {
                PrinterStatus("error", "Can’t reach the printer. Is it on and on the same Wi-Fi?", emptyList(), p.name)
            }
        }
        is PrinterChoice.Direct -> {
            val info = runCatching { p.client().getPrinterAttributes() }.getOrNull()
            if (info == null) PrinterStatus("error", "Can’t reach the printer. Is it on and on the same Wi-Fi?", emptyList())
            else {
                printerInfo = info
                val (st, msg) = describePrinterState(info)
                if (!info.supportsPwg) PrinterStatus("warning", "This printer can’t take jobs from Easy Print", info.markers, info.makeAndModel)
                else PrinterStatus(st, msg, info.markers, info.makeAndModel)
            }
        }
    }

    /** Ink levels from the standard Printer MIB, when the printer reports them (MegaTanks often can't measure). */
    private fun readInk(host: String): List<PrinterInfo.Marker> = runCatching {
        val desc = Snmp.walk(host, CanonG3000.COMMUNITY_PUBLIC, CanonG3000.OID_SUPPLY_DESC, 8)
        val lvl = Snmp.walk(host, CanonG3000.COMMUNITY_PUBLIC, CanonG3000.OID_SUPPLY_LEVEL, 8)
        val max = Snmp.walk(host, CanonG3000.COMMUNITY_PUBLIC, CanonG3000.OID_SUPPLY_MAX, 8)
        desc.mapIndexedNotNull { i, (_, d) ->
            val name = (d as? Snmp.Value.Bytes)?.text() ?: return@mapIndexedNotNull null
            val l = (lvl.getOrNull(i)?.second as? Snmp.Value.Int32)?.v ?: return@mapIndexedNotNull null
            val m = (max.getOrNull(i)?.second as? Snmp.Value.Int32)?.v ?: 100
            val pct = if (l >= 0 && m > 0) (l * 100 / m).toInt() else -1
            val color = when {
                name.contains("cyan", true) -> "#00A0E0"
                name.contains("magenta", true) -> "#E4007F"
                name.contains("yellow", true) -> "#FFD800"
                else -> "#000000"
            }
            PrinterInfo.Marker(name, color, pct, "ink")
        }
    }.getOrDefault(emptyList())

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
        val printer = st.printer ?: run { say("Choose your printer first (tap the printer card)."); return }
        val jobs = buildJobs(plan)
        val p = Pending(DocRenderer(st.files), plan, jobs, printer, jobTitle())
        addHistory(p.title, plan, printer)
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
            val printer = _state.value.printer ?: run { say("Choose your printer first (tap the printer card)."); r.close(); return@launch }
            runPending(Pending(r, plan, buildJobs(plan), printer, "Two-sided test"), calibration = true)
        }
    }

    private fun runPending(p: Pending, calibration: Boolean) {
        pending = p
        val twoPass = p.jobs.manualDuplex && p.jobs.passes.size > 1
        update { it.copy(wizard = Wizard(WizardStep.PREPARING, p.jobs.sheets, p.jobs.removeTopSheet, calibration, "Getting ready…", twoPass = twoPass, reversedBacks = p.plan.settings.reverseBacks)) }
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
                        "This printer doesn’t accept jobs this way. If it’s a Canon, add it by its IP address in the Printer menu."
                    )
                    val file = File(cache, "pass-${p.next}.pwg")
                    withContext(Dispatchers.Default) { DirectOutput.writeRaster(p.renderer, p.plan, pass, info, p.plan.settings, file, progress) }
                    update { it.copy(wizard = it.wizard?.copy(progress = "Sending to the printer…")) }
                    p.jobId = withContext(Dispatchers.IO) { printer.client().printJob(file, DirectOutput.jobOptions(info, p.plan.settings, pass, name)) }
                    file.delete()
                }
                is PrinterChoice.Canon -> {
                    val file = File(cache, "pass-${p.next}.canon")
                    val jobId = ((System.currentTimeMillis() / 1000) % 100_000_000).toInt()
                    withContext(Dispatchers.Default) {
                        CanonOutput.writeJob(p.renderer, p.plan, pass, p.plan.settings, name, jobId, file, progress)
                    }
                    update { it.copy(wizard = it.wizard?.copy(progress = "Sending to the printer…")) }
                    withContext(Dispatchers.IO) {
                        CanonOutput.send(printer.host, printer.port, file) { sent, total ->
                            update { it.copy(wizard = it.wizard?.copy(progress = "Sending to the printer… ${sent * 100 / total.coerceAtLeast(1)}%")) }
                        }
                    }
                    file.delete()
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
                is OutOfMemoryError -> "This phone ran out of memory preparing the page. Try fewer pages at a time."
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
