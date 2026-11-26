package app.easyprint

import app.easyprint.android.CanonScanner
import app.easyprint.core.CanonG3000
import app.easyprint.core.Snmp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Needs net-snmp's snmpd on 127.0.0.1:1161 and tools/printsim/fake_g3000.py on 127.0.0.1:161. */
class SnmpInteropTest {
    private val enabled = System.getenv("SNMP_INTEROP") != null

    @Test fun appClientAgainstNetSnmpAgent() {
        assumeTrue(enabled)
        val r = Snmp.get("127.0.0.1", "public", listOf("1.3.6.1.2.1.1.1.0"), port = 1161)
        assertNotNull("net-snmp answered the app's GetRequest", r)
        val descr = (r!!.get("1.3.6.1.2.1.1.1.0") as Snmp.Value.Bytes).text()
        println("net-snmp sysDescr via app client: $descr")
        assertTrue(descr.contains("net-snmp test agent"))
        val walk = Snmp.walk("127.0.0.1", "public", "1.3.6.1.2.1.1", max = 5, port = 1161)
        println("walk via app client: ${walk.map { it.first }}")
        assertTrue(walk.size >= 3)
        assertNull("wrong community gets no answer", Snmp.get("127.0.0.1", "nope", listOf("1.3.6.1.2.1.1.1.0"), 800, port = 1161, retries = 0))
    }

    @Test fun canonDiscoveryAgainstFakeG3000() {
        assumeTrue(enabled)
        // Scan a few addresses (only one printer exists) exactly like the app scans the Wi-Fi /24.
        val found = CanonScanner.probe(listOf("127.0.0.2", "127.0.0.1", "127.0.0.3"), 1500)
        println("found: $found")
        assertEquals(1, found.size)
        assertEquals("Canon G3000 series", found[0].model)
        assertEquals("F4A99712ABCD", found[0].mac)
        val st = Snmp.get("127.0.0.1", CanonG3000.COMMUNITY_PUBLIC, listOf(CanonG3000.OID_HR_PRINTER_STATUS, CanonG3000.OID_HR_ERROR_STATE))!!
        val status = CanonG3000.describeStatus((st.get(CanonG3000.OID_HR_PRINTER_STATUS) as Snmp.Value.Int32).v, (st.get(CanonG3000.OID_HR_ERROR_STATE) as Snmp.Value.Bytes).v)
        println("status: $status")
        assertEquals("ready", status.first)
    }
}
