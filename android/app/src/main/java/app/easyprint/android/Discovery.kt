package app.easyprint.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/**
 * Finds IPP printers on the local network (DNS-SD "_ipp._tcp"), the same way
 * Android's own Default Print Service and Mopria do.
 */
class PrinterDiscovery(context: Context, private val onChange: (List<PrinterChoice.Direct>) -> Unit) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, PrinterChoice.Direct>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        if (listener != null) return
        lock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("easyprint-discovery")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { listener = null } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { queue.add(info); resolveNext() } }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) onChange(found.values.toList())
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { lock?.release() }
        lock = null
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = queue.poll() ?: return
        resolving = true
        val cb = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    resolving = false
                    val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                        ?: info.hostAddresses.firstOrNull()?.hostAddress
                    else info.host?.hostAddress
                    if (host != null) {
                        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
                        val rp = txt["rp"]?.ifBlank { null } ?: "ipp/print"
                        val model = txt["ty"]?.ifBlank { null } ?: txt["product"]?.trim('(', ')') ?: info.serviceName
                        val name = info.serviceName.ifBlank { model }
                        found[info.serviceName] = PrinterChoice.Direct(name = name, host = host, port = info.port, path = rp)
                        onChange(found.values.toList())
                    }
                    resolveNext()
                }
            }
        }
        runCatching { nsd.resolveService(next, cb) }.onFailure { resolving = false; resolveNext() }
    }
}

/**
 * Finds Canon printers the way Canon's own software does: an SNMP v1 request for
 * Canon's private OIDs (community "canon_admin") to every address on the phone's
 * Wi-Fi subnet. Unicast, because many routers drop replies to broadcasts.
 */
object CanonScanner {
    /** Addresses to probe: the /24 around each of the phone's IPv4 addresses on Wi-Fi/Ethernet. */
    fun candidates(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return emptyList()
        val nets = buildList {
            cm.activeNetwork?.let { add(it) }
            @Suppress("DEPRECATION") addAll(cm.allNetworks)
        }.distinct()
        val out = LinkedHashSet<String>()
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            for (la in lp.linkAddresses) {
                val a = la.address as? java.net.Inet4Address ?: continue
                if (a.isLoopbackAddress) continue
                val b = a.address
                for (i in 1..254) {
                    if (i == (b[3].toInt() and 0xff)) continue
                    out += "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.$i"
                }
            }
        }
        return out.toList()
    }

    /** Probe [hosts] in parallel with one socket; returns the Canon printers that answered. */
    fun probe(hosts: List<String>, timeoutMs: Int = 2500, port: Int = app.easyprint.core.Snmp.PORT): List<app.easyprint.core.CanonG3000.Found> {
        if (hosts.isEmpty()) return emptyList()
        val found = LinkedHashMap<String, app.easyprint.core.CanonG3000.Found>()
        java.net.DatagramSocket().use { s ->
            val req = app.easyprint.core.Snmp.request(app.easyprint.core.CanonG3000.COMMUNITY_CANON, 1, app.easyprint.core.CanonG3000.DISCOVERY_OIDS)
            for (h in hosts) runCatching {
                s.send(java.net.DatagramPacket(req, req.size, java.net.InetAddress.getByName(h), port))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val dp = java.net.DatagramPacket(buf, buf.size)
                try { s.receive(dp) } catch (_: java.net.SocketTimeoutException) { break }
                val host = dp.address.hostAddress ?: continue
                app.easyprint.core.CanonG3000.parseDiscovery(host, app.easyprint.core.Snmp.parse(buf, dp.length))?.let { found[host] = it }
            }
        }
        return found.values.toList()
    }
}
