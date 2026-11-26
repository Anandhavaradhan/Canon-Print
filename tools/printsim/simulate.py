"""A virtual Canon G3000 and a virtual person.

1. Decodes the spooled PWG raster jobs with CUPS's own libcups raster reader
   (independent of the app's encoder).
2. Feeds paper through a simulated printer, doing exactly what the app's
   on-screen instructions say between the two passes.
3. Reads the finished pile like a person would (flipping along the long or
   short edge) and checks every page is present, in order, and upright.

Printer behaviours can be varied to check that the two-sided test can fix a
printer that feeds or stacks paper differently from the G3000.
"""
import ctypes
import ctypes.util
import json
import os
import sys

import numpy as np

import probe

# ---------------------------------------------------------------- libcups decoding
_cups = ctypes.CDLL(ctypes.util.find_library("cups") or "libcups.so.2")
_cups.cupsRasterOpen.restype = ctypes.c_void_p
_cups.cupsRasterOpen.argtypes = [ctypes.c_int, ctypes.c_int]
_cups.cupsRasterReadHeader2.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
_cups.cupsRasterReadHeader2.restype = ctypes.c_uint
_cups.cupsRasterReadPixels.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint]
_cups.cupsRasterReadPixels.restype = ctypes.c_uint
_cups.cupsRasterClose.argtypes = [ctypes.c_void_p]


STEP = 8


def read_pwg(path):
    """Return list of (header dict, HxWx3 uint8 image) using libcups."""
    fd = os.open(path, os.O_RDONLY)
    r = _cups.cupsRasterOpen(fd, 0)  # CUPS_RASTER_READ
    pages = []
    try:
        hdr = ctypes.create_string_buffer(1796 + 4096)  # cups_page_header2_t (+slack)
        while _cups.cupsRasterReadHeader2(r, hdr):
            u = lambda off: int.from_bytes(hdr.raw[off:off + 4], sys.byteorder)
            h = dict(width=u(372), height=u(376), bpp=u(388), bpl=u(392), colorspace=u(400),
                     dpi=u(276), page_w=u(352), page_h=u(356), quality=u(484),
                     size_name=hdr.raw[1732:1796].split(b"\0")[0].decode())
            buf = ctypes.create_string_buffer(h["bpl"] * h["height"])
            n = _cups.cupsRasterReadPixels(r, buf, h["bpl"] * h["height"])
            assert n == h["bpl"] * h["height"], f"short read {n}"
            a = np.frombuffer(buf.raw, np.uint8).reshape(h["height"], h["width"], h["bpp"] // 8)
            if a.shape[2] == 1:
                a = np.repeat(a, 3, axis=2)
            pages.append((h, a[::STEP, ::STEP].copy()))  # downsample for speed
    finally:
        _cups.cupsRasterClose(r)
        os.close(fd)
    return pages


# ---------------------------------------------------------------- physical model
BLANK = None


class Sheet:
    """Faces stored in a canonical frame: face 0 viewed with edge E0 (the edge
    that led in pass 1) at the top; face 1 viewed after turning the sheet over
    like a book page from that view (so E0 is still at the top)."""

    def __init__(self, sid):
        self.id = sid
        self.face = [BLANK, BLANK]
        self.up = 0          # which face is facing up
        self.e0_leads = True  # does edge E0 lead into the printer?

    def print_on(self, img):
        stored = img if self.e0_leads else np.rot90(img, 2)
        assert self.face[self.up] is BLANK, f"sheet {self.id} printed twice on the same side!"
        self.face[self.up] = stored

    def book_turn(self):  # turn over around the vertical axis: top edge stays the top edge
        self.up ^= 1


class Printer:
    def __init__(self, feed_from_top=True, output_face_up=True, flips_sheet=False, end_over_end=False):
        self.end_over_end = end_over_end  # output path turns sheets over top-to-bottom
        self.feed_from_top = feed_from_top
        self.output_face_up = output_face_up
        self.flips_sheet = flips_sheet  # prints on the face that was DOWN in the tray

    def run(self, tray, images):
        """tray: list, index 0 = top of pile. Returns output pile (index 0 = top)."""
        out = []
        for img in images:
            if not tray:
                raise RuntimeError("printer ran out of paper")
            sheet = tray.pop(0 if self.feed_from_top else -1)
            if self.flips_sheet:
                sheet.up ^= 1
            sheet.print_on(img)
            if not self.output_face_up:
                sheet.book_turn()
            if self.end_over_end:  # calendar flip = book turn + 180° rotation
                sheet.book_turn()
                sheet.e0_leads = not sheet.e0_leads
            out.insert(0, sheet)
        return out, tray


CALENDAR_PERSON = False  # set True to simulate someone who always turns the pile end-over-end


def person_turns_pile(pile, reinsert=False):
    """'Turn the whole pile over like a page in a book (left to right) ... top edge in first.'"""
    for s in pile:
        s.book_turn()
        if CALENDAR_PERSON and reinsert:
            s.e0_leads = not s.e0_leads
    return list(reversed(pile))


def blank_img(shape):
    return np.full(shape, 255, np.uint8)


def view(sheet, face):
    img = sheet.face[face]
    return img


def read_pile(pile, sides, set_aside, booklet, allow_sideways=(), reversed_backs=False):
    """The person turns the finished pile over like a book page and reads it
    (from the bottom up when the app says page 1 is at the bottom)."""
    pile = person_turns_pile(pile) if sides != "ONE" else pile
    if reversed_backs:
        pile = list(reversed(pile))
    if set_aside is not None:
        pile = pile + [set_aside]
    sequence, problems = [], []
    for s in pile:
        front = s.face[s.up]
        back = s.face[s.up ^ 1]
        if front is None:
            problems.append(f"sheet {s.id}: blank face on top"); continue
        # Reading frame: rotate the front so its content is upright.
        best = None
        for k in range(4):
            v = np.rot90(front, k)
            found = probe.classify(v)
            ups = sum(1 for f in found if f[1] == "up")
            if found and (best is None or ups > best[0]):
                best = (ups, k, found)
        if best is None:
            problems.append(f"sheet {s.id}: unreadable front"); continue
        _, k, ffound = best
        fv = np.rot90(front, k)
        portrait_view = fv.shape[0] > fv.shape[1]
        def read_side(v):
            if not booklet:
                return [(p, o) for p, o, *_ in probe.classify(v)]
            w = v.shape[1] // 2
            res = []
            for half in (v[:, :w], v[:, w:]):
                f = probe.classify(half)
                res.append((f[0][0], f[0][1]) if f else (None, "up"))
            return res

        pages = read_side(fv)
        if back is not None and sides != "ONE":
            # Back as seen after the flip. Face 1 canonical = book-turn of face 0 canonical.
            # Viewing rotation R=rot90^k: book-turn shows R^-1(back); calendar flip shows rot180(R^-1(back)).
            long_is_vertical = portrait_view
            flip_vertical_axis = (sides == "LONG") == long_is_vertical
            bv = np.rot90(back, -k) if flip_vertical_axis else np.rot90(back, -k + 2)
            pages += read_side(bv)
        elif booklet:
            pages += [(None, "up"), (None, "up")]
        for p, o in pages:
            if o != "up" and not (o == "sideways" and p in allow_sideways):
                problems.append(f"sheet {s.id}: page {p} is {o}")
        sequence.append([p for p, _ in pages])
    return sequence, problems


def simulate(job_dir, printer=Printer(), verbose=False):
    meta = json.load(open(os.path.join(job_dir, "meta.json")))
    passes = [read_pwg(os.path.join(job_dir, f"pass{i}.pwg")) for i in range(meta["passes"])]
    hdr0 = passes[0][0][0]
    shape = passes[0][0][1].shape
    n_sheets = meta["sheets"]
    tray = [Sheet(i + 1) for i in range(n_sheets + 2)]  # a couple of spare sheets in the tray
    out, tray = printer.run(tray, [img for _, img in passes[0]])
    set_aside = None
    if meta["manualDuplex"] and len(passes) > 1:
        # What the app tells the person to do, step by step:
        if meta["removeTopSheet"]:
            set_aside = out.pop(0)       # "Take the top sheet off the pile and put it aside"
        pile = person_turns_pile(out, reinsert=True)  # "Turn the whole pile over like a page in a book"
        tray = pile                      # "Put it in the rear paper tray ... no other paper in the tray"
        out, tray = printer.run(tray, [img for _, img in passes[1]])
        if tray:
            raise AssertionError(f"{len(tray)} printed sheet(s) left in the tray")
    return meta, hdr0, out, set_aside


def check(name, job_dir, expected, sides, printer=Printer(), booklet=False, allow_sideways=()):
    meta, hdr, out, aside = simulate(job_dir, printer)
    seq, problems = read_pile(out, sides, aside, booklet, allow_sideways, reversed_backs=meta.get("reverseBacks", False))
    flat = [p for s in seq for p in s]
    if booklet:
        # unfold: sheet fronts [L,R], backs [L,R]; reading = R of fronts & L of backs going in, then back out
        n = len(seq)
        fr = [s[:2] for s in seq]; bk = [s[2:] for s in seq]
        flat = []
        for i in range(n):
            flat += fr[i][1:2] + bk[i][:1]
        for i in reversed(range(n)):
            flat += bk[i][1:2] + fr[i][:1]
        flat = [p for p in flat if p is not None]
    ok = flat == expected and not problems
    return ok, flat, problems, hdr, meta


if __name__ == "__main__":
    spool = sys.argv[1]
    print(read_pwg(os.path.join(spool, "long", "pass0.pwg"))[0][0])
