"""A fake Canon PIXMA G3000 on the network, for end-to-end tests.

- SNMP agent (UDP): Canon's private OIDs answer only to community "canon_admin"
  (like the real printers); standard Host Resources / Printer MIB answer to "public".
- Raw print port (TCP 9100): receives IVEC + PWG Raster jobs, validates them strictly
  (XML, block order, job ids, datasize, PWG geometry via libcups) and saves them.

Usage: python3 fake_g3000.py <jobs_dir> [--snmp-port 161] [--port 9100] [--no-paper]
"""
import argparse
import json
import os
import re
import socket
import sys
import tempfile
import threading
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
import simulate as sim  # noqa: E402  (libcups decoder)

IVEC = "{http://www.canon.com/ns/cmd/2008/07/common/}"
VCN = "{http://www.canon.com/ns/cmd/2008/07/canon/}"

# imageable area in pixels at 600 dpi, from canong3000.ppd
GEOMETRY = {
    "iso_a4_210x297mm": (4800, 6826), "na_letter_8.5x11in": (4800, 6411), "na_legal_8.5x14in": (4800, 8211),
    "iso_a5_148x210mm": (3335, 4771), "jis_b5_182x257mm": (4138, 5881), "na_index-4x6_4x6in": (2239, 3411),
    "na_5x7_5x7in": (2839, 4011), "custom_canon_203x254mm": (4639, 5811),
    "na_number-10_4.125x9.5in": (2314, 4825), "iso_dl_110x220mm": (2437, 4322),
}
PAPERTYPES = {"stationery", "photographic", *(f"custom-media-type-canon-{n}" for n in (3, 6, 9, 14, 15, 16, 17, 18))}


# ---------------------------------------------------------------- minimal BER / SNMP v1
def ber_len(b, p):
    n = b[p]; p += 1
    if n < 0x80:
        return n, p
    k = n & 0x7F
    return int.from_bytes(b[p:p + k], "big"), p + k


def tlv(b, p):
    t = b[p]; n, p = ber_len(b, p + 1)
    return t, b[p:p + n], p + n


def enc_len(n):
    if n < 0x80: return bytes([n])
    if n < 0x100: return bytes([0x81, n])
    return bytes([0x82, n >> 8, n & 0xFF])


def enc(t, v): return bytes([t]) + enc_len(len(v)) + v
def enc_int(i): return enc(0x02, i.to_bytes(max(1, (i.bit_length() + 8) // 8), "big", signed=True))


def oid_dec(c):
    parts = [c[0] // 40, c[0] % 40]; v = 0
    for x in c[1:]:
        v = (v << 7) | (x & 0x7F)
        if not x & 0x80:
            parts.append(v); v = 0
    return ".".join(map(str, parts))


def oid_enc(s):
    p = [int(x) for x in s.split(".")]
    out = bytearray([p[0] * 40 + p[1]])
    for v in p[2:]:
        tmp = [v & 0x7F]; v >>= 7
        while v: tmp.append(0x80 | (v & 0x7F)); v >>= 7
        out += bytes(reversed(tmp))
    return bytes(out)


def oid_key(s): return tuple(int(x) for x in s.split("."))


class Agent:
    def __init__(self, no_paper=False):
        canon = {
            "1.3.6.1.4.1.1602.1.3.1.13.0": enc(0x04, bytes.fromhex("F4A99712ABCD")),
            "1.3.6.1.4.1.1602.1.2.1.8.1.3.1.1": enc(0x04, b"KMNB12345"),
            "1.3.6.1.4.1.1602.1.1.1.1.0": enc(0x04, b"Canon G3000 series"),
            "1.3.6.1.4.1.1602.1.3.1.12.0": enc_int(5),
            "1.3.6.1.4.1.1602.1.1.1.10.0": enc_int(1),
        }
        public = {
            "1.3.6.1.2.1.1.1.0": enc(0x04, b"Canon G3000 series"),
            "1.3.6.1.2.1.25.3.5.1.1.1": enc_int(3),
            "1.3.6.1.2.1.25.3.5.1.2.1": enc(0x04, bytes([0x40 if no_paper else 0, 0])),
        }
        for i, (name, lvl) in enumerate([("Black", -3), ("Cyan", -3), ("Magenta", -3), ("Yellow", -3)], start=1):
            public[f"1.3.6.1.2.1.43.11.1.1.6.1.{i}"] = enc(0x04, name.encode())
            public[f"1.3.6.1.2.1.43.11.1.1.8.1.{i}"] = enc_int(-2)  # MegaTanks can't measure: "unknown"
            public[f"1.3.6.1.2.1.43.11.1.1.9.1.{i}"] = enc_int(lvl)
        self.tables = {"canon_admin": canon, "public": public}
        self.requests = []

    def handle(self, data):
        _, msg, _ = tlv(data, 0)
        _, ver, p = tlv(msg, 0)
        _, comm, p = tlv(msg, p)
        pdu_t, pdu, _ = tlv(msg, p)
        _, rid, q = tlv(pdu, 0)
        _, _, q = tlv(pdu, q); _, _, q = tlv(pdu, q)
        _, vbl, _ = tlv(pdu, q)
        oids, r = [], 0
        while r < len(vbl):
            _, vb, r = tlv(vbl, r)
            _, o, _ = tlv(vb, 0)
            oids.append(oid_dec(o))
        community = comm.decode(errors="replace")
        self.requests.append((community, pdu_t, oids))
        table = self.tables.get(community)
        if table is None:
            return None  # wrong community: real agents stay silent
        err, out = 0, bytearray()
        for o in oids:
            if pdu_t == 0xA1:  # GetNext
                nxt = sorted((k for k in table if oid_key(k) > oid_key(o)), key=oid_key)
                if not nxt: err = 2; o2, v = o, enc(0x05, b"")
                else: o2, v = nxt[0], table[nxt[0]]
            else:
                o2, v = o, table.get(o)
                if v is None: err = 2; v = enc(0x05, b"")
            out += enc(0x30, enc(0x06, oid_enc(o2)) + v)
        body = enc(0x02, rid) + enc_int(err) + enc_int(0) + enc(0x30, bytes(out))
        return enc(0x30, enc(0x02, ver) + enc(0x04, comm) + enc(0xA2, body))


# ---------------------------------------------------------------- job validation
def validate(data):
    """Return (pages[(header, image)], problems[])"""
    problems, pages, ops, p = [], [], [], 0
    job_ids, cfg = set(), {}
    while p < len(data):
        if not data.startswith(b"<?xml", p):
            problems.append(f"garbage at byte {p}"); break
        e = data.find(b"</cmd>", p)
        if e < 0:
            problems.append("unterminated block"); break
        xml = data[p:e + 6]; p = e + 6
        try:
            root = ET.fromstring(xml)
        except ET.ParseError as ex:
            problems.append(f"malformed XML: {ex}"); continue
        op = root.find(f".//{IVEC}operation").text
        ops.append(op)
        ps = root.find(f".//{IVEC}param_set")
        job_ids.add(ps.find(f"{IVEC}jobID").text)
        if op == "SetConfiguration":
            cfg = {c.tag.split('}')[1]: c.text for c in ps}
        if op == "VendorCmd":
            if ps.find(f"{VCN}nextpage") is None:
                problems.append("VendorCmd without vcn:nextpage (namespace prefix wrong?)")
        if op == "SendData":
            n = int(ps.find(f"{IVEC}datasize").text)
            payload = data[p:p + n]; p += n
            if len(payload) != n or not payload.startswith(b"RaS2"):
                problems.append("datasize does not match a PWG stream"); continue
            f = tempfile.NamedTemporaryFile(delete=False, suffix=".pwg"); f.write(payload); f.close()
            try:
                sim.STEP = 8
                dec = sim.read_pwg(f.name)
            except Exception as ex:  # noqa: BLE001
                problems.append(f"libcups could not read page: {ex}"); dec = []
            finally:
                os.unlink(f.name)
            if len(dec) != 1:
                problems.append(f"each SendData must carry exactly one page, got {len(dec)}")
            pages += dec
    expected_head = ["StartJob", "SetJobConfiguration", "SetConfiguration"]
    if ops[:3] != expected_head or ops[-1:] != ["EndJob"]:
        problems.append(f"bad block order: {ops}")
    body = ops[3:-1]
    if body != ["VendorCmd", "SendData"] * (len(body) // 2):
        problems.append(f"bad page blocks: {body}")
    if len(job_ids) != 1 or not all(re.fullmatch(r"\d{8}", j) for j in job_ids):
        problems.append(f"job ids: {job_ids}")
    want = GEOMETRY.get(cfg.get("papersize"))
    if want is None: problems.append(f"unknown papersize {cfg.get('papersize')}")
    if cfg.get("papertype") not in PAPERTYPES: problems.append(f"unknown papertype {cfg.get('papertype')}")
    if cfg.get("printcolormode") not in ("color", "monochrome"): problems.append("bad printcolormode")
    if cfg.get("duplexprint") != "OFF": problems.append("G3000 has no duplex unit: duplexprint must be OFF")
    for h, _ in pages:
        if (h["width"], h["height"]) != want: problems.append(f"page {h['width']}x{h['height']} != imageable {want}")
        if (h["bpp"], h["colorspace"], h["dpi"]) != (24, 19, 600): problems.append(f"raster format {h['bpp']}/{h['colorspace']}/{h['dpi']}")
    return pages, problems, cfg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jobs")
    ap.add_argument("--snmp-port", type=int, default=161)
    ap.add_argument("--port", type=int, default=9100)
    ap.add_argument("--no-paper", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.jobs, exist_ok=True)
    agent = Agent(a.no_paper)

    def snmp():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", a.snmp_port))
        while True:
            data, addr = s.recvfrom(4096)
            try:
                reply = agent.handle(data)
            except Exception:  # noqa: BLE001
                reply = None
            if reply:
                s.sendto(reply, addr)

    threading.Thread(target=snmp, daemon=True).start()
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", a.port)); srv.listen(4)
    print(f"fake G3000 listening: tcp {a.port}, snmp udp {a.snmp_port}", flush=True)
    n = 0
    while True:
        c, _ = srv.accept()
        chunks = []
        c.settimeout(30)
        try:
            while True:
                b = c.recv(65536)
                if not b: break
                chunks.append(b)
        except socket.timeout:
            pass
        c.close()
        data = b"".join(chunks)
        if not data:
            continue  # reachability probe
        n += 1
        path = os.path.join(a.jobs, f"job{n}.bin")
        open(path, "wb").write(data)
        pages, problems, cfg = validate(data)
        rec = {"job": n, "bytes": len(data), "pages": len(pages), "config": cfg, "problems": problems,
               "snmp_seen": sorted({c for c, _, _ in agent.requests})}
        open(os.path.join(a.jobs, f"job{n}.json"), "w").write(json.dumps(rec, indent=1))
        print(json.dumps(rec), flush=True)


if __name__ == "__main__":
    main()
