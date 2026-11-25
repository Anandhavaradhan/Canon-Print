"""Page layout ("imposition") engine.

Everything clever about a print job happens here, inside the app, instead of
relying on the printer driver. That keeps the result identical on Windows,
macOS and Linux, and makes features possible that the Canon G3000 series
cannot do by itself (it has no automatic two-sided unit):

* pages per side (2-up, 4-up ...) with optional borders
* booklets
* page ranges, odd/even pages, copies, collation, reverse order
* guided manual two-sided printing (flip on long edge / short edge)

Terminology
-----------
*source page*   a page of the document the user opened.
*side*          one face of a sheet of paper; holds 1..16 source pages.
*sheet*         one physical piece of paper (a front side and maybe a back).
*logical side*  a side as the reader sees it (may be landscape).
*physical side* the same side rotated so it is always portrait, which is how
                paper travels through the printer. Two-sided printing is only
                reliable if we control that rotation ourselves.
"""

from __future__ import annotations

import io
import re
from dataclasses import dataclass, field

from pypdf import PdfReader, PdfWriter, Transformation
from pypdf.generic import DecodedStreamObject

MM = 72 / 25.4  # points per millimetre

# name: (label, width_pt, height_pt) - always portrait
PAPER_SIZES: dict[str, tuple[str, float, float]] = {
    "A4": ("A4 (210 × 297 mm)", 595.28, 841.89),
    "Letter": ("Letter (8.5 × 11 in)", 612.0, 792.0),
    "Legal": ("Legal (8.5 × 14 in)", 612.0, 1008.0),
    "A5": ("A5 (148 × 210 mm)", 419.53, 595.28),
    "B5": ("B5 (182 × 257 mm)", 515.91, 728.50),
    "4x6": ("Photo 4 × 6 in (10 × 15 cm)", 288.0, 432.0),
    "5x7": ("Photo 5 × 7 in (13 × 18 cm)", 360.0, 504.0),
    "8x10": ("Photo 8 × 10 in (20 × 25 cm)", 576.0, 720.0),
    "EnvDL": ("Envelope DL (110 × 220 mm)", 311.81, 623.62),
    "Env10": ("Envelope #10 (4.1 × 9.5 in)", 296.98, 684.0),
}

# pages per side -> (columns, rows) in the "wide" arrangement
GRIDS = {1: (1, 1), 2: (2, 1), 4: (2, 2), 6: (3, 2), 9: (3, 3), 16: (4, 4)}

DEFAULT_SETTINGS = {
    "paper": "A4",
    "paper_type": "plain",  # plain | photo_glossy | photo_matte | envelope
    "orientation": "auto",  # auto | portrait | landscape
    "pages_per_side": 1,
    "page_order": "across",  # across | down
    "borders": False,
    "scaling": "fit",  # fit | shrink | actual | custom
    "scale_percent": 100,
    "margin_mm": 5,
    "sides": "one",  # one | long | short
    "booklet": False,
    "page_range": "",
    "page_subset": "all",  # all | odd | even
    "copies": 1,
    "collate": True,
    "last_page_first": False,
    "color": "color",  # color | bw
    "quality": "standard",  # draft | standard | high
    "duplex_method": "manual",  # manual | auto
    "rotate_backs": False,  # calibration: back sides come out upside down
    "reverse_backs": False,  # calibration: printer feeds the flipped stack in reverse
    "files_on_new_sheet": True,  # with several files, never share a sheet between files
}


class LayoutError(ValueError):
    """A problem the user can fix (bad page range, etc.). Message is user-facing."""


def normalize_settings(raw: dict | None) -> dict:
    """Merge user settings over defaults and coerce types defensively."""
    s = dict(DEFAULT_SETTINGS)
    for k, v in (raw or {}).items():
        if k in s:
            s[k] = v

    def pick(key, allowed):
        if s[key] not in allowed:
            s[key] = DEFAULT_SETTINGS[key]

    pick("paper", PAPER_SIZES)
    pick("paper_type", {"plain", "photo_glossy", "photo_matte", "envelope"})
    pick("orientation", {"auto", "portrait", "landscape"})
    pick("page_order", {"across", "down"})
    pick("scaling", {"fit", "shrink", "actual", "custom"})
    pick("sides", {"one", "long", "short"})
    pick("page_subset", {"all", "odd", "even"})
    pick("color", {"color", "bw"})
    pick("quality", {"draft", "standard", "high"})
    pick("duplex_method", {"manual", "auto"})
    try:
        s["pages_per_side"] = int(s["pages_per_side"])
    except (TypeError, ValueError):
        s["pages_per_side"] = 1
    if s["pages_per_side"] not in GRIDS:
        s["pages_per_side"] = 1
    for key, lo, hi, default in (
        ("copies", 1, 999, 1),
        ("scale_percent", 10, 400, 100),
    ):
        try:
            s[key] = max(lo, min(hi, int(s[key])))
        except (TypeError, ValueError):
            s[key] = default
    try:
        s["margin_mm"] = max(0.0, min(40.0, float(s["margin_mm"])))
    except (TypeError, ValueError):
        s["margin_mm"] = DEFAULT_SETTINGS["margin_mm"]
    for key in (
        "borders",
        "booklet",
        "collate",
        "last_page_first",
        "rotate_backs",
        "reverse_backs",
        "files_on_new_sheet",
    ):
        s[key] = bool(s[key])
    s["page_range"] = str(s["page_range"] or "").strip()
    if s["booklet"]:
        s["pages_per_side"] = 2
        s["sides"] = "short"
        s["page_subset"] = "all"
    return s


def parse_page_range(text: str, page_count: int) -> list[int]:
    """'1-3, 5, 8-' -> zero-based indices. Empty text means all pages."""
    text = (text or "").strip()
    if not text:
        return list(range(page_count))
    result: list[int] = []
    for part in re.split(r"[,;\s]+", text):
        if not part:
            continue
        m = re.fullmatch(r"(\d*)\s*[-–]\s*(\d*)", part)
        if m:
            start = int(m.group(1)) if m.group(1) else 1
            end = int(m.group(2)) if m.group(2) else page_count
        elif part.isdigit():
            start = end = int(part)
        else:
            raise LayoutError(
                f'"{part}" is not a page number. Use numbers and dashes, like 1-3, 5, 8-10.'
            )
        if start < 1 or end < 1:
            raise LayoutError("Page numbers start at 1.")
        if start > page_count:
            raise LayoutError(
                f"Page {start} doesn't exist - the document has {page_count} page"
                f"{'s' if page_count != 1 else ''}."
            )
        end = min(end, page_count)
        step = 1 if end >= start else -1
        result.extend(range(start - 1, end - 1 + step, step))
    if not result:
        raise LayoutError("The page range doesn't include any pages.")
    return result


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------


@dataclass
class Placement:
    src: int  # index into source pages
    matrix: tuple[float, float, float, float, float, float]
    cell: tuple[float, float, float, float]  # x, y, w, h in logical coords


@dataclass
class Side:
    landscape: bool
    placements: list[Placement] = field(default_factory=list)
    numbers: list[int] = field(default_factory=list)  # 1-based source page numbers shown


@dataclass
class Sheet:
    front: Side
    back: Side | None = None


@dataclass
class Plan:
    paper_w: float
    paper_h: float
    sheets: list[Sheet]  # one copy, in reading order
    settings: dict
    source_pages: int
    selected_pages: int
    borders: bool

    @property
    def two_sided(self) -> bool:
        return self.settings["sides"] != "one"

    def physical_sheet_count(self) -> int:
        return len(self.sheets) * self.settings["copies"]


# ---------------------------------------------------------------------------
# Planning
# ---------------------------------------------------------------------------


def _page_box(page) -> tuple[float, float, float, float]:
    box = page.cropbox
    x0, y0 = float(min(box.left, box.right)), float(min(box.bottom, box.top))
    return x0, y0, abs(float(box.width)), abs(float(box.height))


def _choose_grid(n, page_w, page_h, paper_w, paper_h, orientation, margin, gap):
    """Pick sheet orientation and grid arrangement that makes pages biggest."""
    cols, rows = GRIDS[n]
    arrangements = {(cols, rows), (rows, cols)}
    if orientation == "portrait":
        orients = [False]
    elif orientation == "landscape":
        orients = [True]
    else:
        orients = [False, True]
    best = None
    for land in orients:
        sw, sh = (paper_h, paper_w) if land else (paper_w, paper_h)
        for c, r in sorted(arrangements):
            cw = (sw - 2 * margin - gap * (c - 1)) / c
            ch = (sh - 2 * margin - gap * (r - 1)) / r
            if cw <= 0 or ch <= 0:
                continue
            scale = min(cw / page_w, ch / page_h)
            # prefer the classic choice on ties (2-up on landscape, etc.)
            key = (round(scale, 4), land == (n in (2, 6)))
            if best is None or key > best[0]:
                best = (key, land, c, r)
    if best is None:
        raise LayoutError("The margins are too big for this paper size.")
    return best[1], best[2], best[3]


def make_plan(reader: PdfReader, settings: dict, file_starts: list[int] | None = None) -> Plan:
    """Work out which source page goes where.

    file_starts: index of the first page of each opened file (after the first),
    used to start every file on a fresh sheet.
    """
    s = settings
    pages = reader.pages
    total = len(pages)
    if total == 0:
        raise LayoutError("The document has no pages.")
    indices = parse_page_range(s["page_range"], total)
    if s["page_subset"] in ("odd", "even"):
        want = 0 if s["page_subset"] == "odd" else 1
        indices = [p for p in indices if p % 2 == want]
        if not indices:
            raise LayoutError(f"There are no {s['page_subset']} pages in that range.")

    _, paper_w, paper_h = PAPER_SIZES[s["paper"]]
    n = s["pages_per_side"]
    margin = s["margin_mm"] * MM
    gap = (4 * MM) if n > 1 else 0.0
    boxes = {i: _page_box(pages[i]) for i in set(indices)}

    slots: list[int | None] = list(indices)
    if s["booklet"]:
        while len(slots) % 4:
            slots.append(None)
        m = len(slots)
        ordered: list[int | None] = []
        for i in range(m // 4):
            ordered += [slots[m - 1 - 2 * i], slots[2 * i], slots[2 * i + 1], slots[m - 2 - 2 * i]]
        slots = ordered
    elif s["files_on_new_sheet"] and file_starts:
        unit = n * (1 if s["sides"] == "one" else 2)
        starts = set(file_starts)
        padded: list[int | None] = []
        for src in slots:
            if src in starts and padded:
                while len(padded) % unit:
                    padded.append(None)
            padded.append(src)
        slots = padded

    groups = [slots[i : i + n] for i in range(0, len(slots), n)]

    # sheet geometry (shared by all sides unless 1-up auto, which adapts per page)
    first = boxes[indices[0]]
    fixed = _choose_grid(n, first[2], first[3], paper_w, paper_h, s["orientation"], margin, gap)

    def build_side(group: list[int | None]) -> Side:
        if n == 1 and s["orientation"] == "auto" and group[0] is not None:
            _, _, w, h = boxes[group[0]]
            land, c, r = (w > h), 1, 1
        else:
            land, c, r = fixed
        sw, sh = (paper_h, paper_w) if land else (paper_w, paper_h)
        cw = (sw - 2 * margin - gap * (c - 1)) / c
        ch = (sh - 2 * margin - gap * (r - 1)) / r
        side = Side(landscape=land)
        for pos, src in enumerate(group):
            if src is None:
                continue
            if s["page_order"] == "down":
                col, row = pos // r, pos % r
            else:
                col, row = pos % c, pos // c
            cx = margin + col * (cw + gap)
            cy = sh - margin - (row + 1) * ch - row * gap
            x0, y0, w, h = boxes[src]
            fit = min(cw / w, ch / h)
            if s["scaling"] == "fit":
                scale = fit
            elif s["scaling"] == "shrink":
                scale = min(1.0, fit)
            elif s["scaling"] == "actual":
                scale = 1.0
            else:
                scale = s["scale_percent"] / 100.0
            if n == 1 and s["scaling"] in ("actual", "custom"):
                # centre on the whole sheet, ignoring margins
                cx, cy, cw_, ch_ = 0.0, 0.0, sw, sh
            else:
                cw_, ch_ = cw, ch
            tx = cx + (cw_ - w * scale) / 2 - x0 * scale
            ty = cy + (ch_ - h * scale) / 2 - y0 * scale
            side.placements.append(Placement(src, (scale, 0, 0, scale, tx, ty), (cx, cy, cw_, ch_)))
            side.numbers.append(src + 1)
        return side

    sheets: list[Sheet] = []
    if s["sides"] == "one":
        sheets = [Sheet(build_side(g)) for g in groups]
    else:
        for i in range(0, len(groups), 2):
            front = build_side(groups[i])
            back = build_side(groups[i + 1]) if i + 1 < len(groups) else None
            if back is not None and not back.placements:
                back = None
            sheets.append(Sheet(front, back))

    return Plan(paper_w, paper_h, sheets, s, total, len(indices), s["borders"] and n > 1)


# ---------------------------------------------------------------------------
# Rendering plans into PDFs
# ---------------------------------------------------------------------------


def _border_ops(side: Side) -> bytes:
    ops = [b"q 0.6 w 0.45 G"]
    for p in side.placements:
        x, y, w, h = p.cell
        ops.append(f"{x:.2f} {y:.2f} {w:.2f} {h:.2f} re S".encode())
    ops.append(b"Q")
    return b"\n".join(ops)


def _append_ops(writer: PdfWriter, page, ops: bytes) -> None:
    contents = page.get_contents()
    data = contents.get_data() if contents is not None else b""
    stream = DecodedStreamObject()
    stream.set_data(data + b"\n" + ops + b"\n")
    page.replace_contents(stream)


def render_side(
    writer: PdfWriter,
    reader: PdfReader,
    plan: Plan,
    side: Side | None,
    physical: bool,
    rotate180: bool = False,
):
    """Append one side to *writer*.

    physical=False: page keeps its logical orientation (used for previews and
    "Save as PDF"). physical=True: page is always portrait, landscape content
    is turned 90° anticlockwise, and optionally everything is turned 180°
    (for manual two-sided printing).
    """
    pw, ph = plan.paper_w, plan.paper_h
    landscape = side.landscape if side else False
    if physical or not landscape:
        page = writer.add_blank_page(pw, ph)
    else:
        page = writer.add_blank_page(ph, pw)
    if side is None:
        return page

    def outer() -> Transformation:
        t = Transformation()
        if physical and landscape:
            # logical (x, y) on a ph×pw sheet -> portrait: rotate 90° CCW, shift right
            t = t.rotate(90).translate(pw, 0)
        if physical and rotate180:
            t = t.rotate(180).translate(pw, ph)
        return t

    for p in side.placements:
        a, b, c, d, e, f = p.matrix
        t = Transformation((a, b, c, d, e, f))
        o = outer()
        t = Transformation(_compose(t.ctm, o.ctm))
        page.merge_transformed_page(reader.pages[p.src], t, over=True, expand=False)

    if plan.borders:
        ops = _border_ops(side)
        o = outer().ctm
        if o != (1, 0, 0, 1, 0, 0):
            ops = f"q {' '.join(f'{v:.4f}' for v in o)} cm\n".encode() + ops + b"\nQ"
        _append_ops(writer, page, ops)
    return page


def _compose(m1, m2):
    """Matrix for applying m1 then m2 (PDF row-vector convention)."""
    a1, b1, c1, d1, e1, f1 = m1
    a2, b2, c2, d2, e2, f2 = m2
    return (
        a1 * a2 + b1 * c2,
        a1 * b2 + b1 * d2,
        c1 * a2 + d1 * c2,
        c1 * b2 + d1 * d2,
        e1 * a2 + f1 * c2 + e2,
        e1 * b2 + f1 * d2 + f2,
    )


def _to_bytes(writer: PdfWriter) -> bytes:
    buf = io.BytesIO()
    writer.write(buf)
    return buf.getvalue()


def preview_pdf(reader: PdfReader, plan: Plan, limit: int | None = None) -> tuple[bytes, list[tuple[int, int | None]]]:
    """Logical sides of one copy: returns PDF bytes and [(front_idx, back_idx)]."""
    w = PdfWriter()
    index: list[tuple[int, int | None]] = []
    for sheet in plan.sheets[:limit]:
        render_side(w, reader, plan, sheet.front, physical=False)
        fi = len(w.pages) - 1
        bi = None
        if plan.two_sided and sheet.back is not None:
            render_side(w, reader, plan, sheet.back, physical=False)
            bi = len(w.pages) - 1
        index.append((fi, bi))
    return _to_bytes(w), index


@dataclass
class Job:
    """One spool job to send to the printer."""

    label: str  # e.g. "Front sides"
    pdf: bytes
    pages: int
    sides: str = "one"  # one | long | short (only != one for automatic duplex)


@dataclass
class JobSet:
    jobs: list[Job]
    manual_duplex: bool
    remove_top_sheet: bool  # last sheet has no back: take it off before flipping
    sheets: int


def _sheet_sequence(plan: Plan) -> list[Sheet]:
    copies = plan.settings["copies"]
    if plan.settings["collate"]:
        return plan.sheets * copies
    return [sh for sh in plan.sheets for _ in range(copies)]


def build_jobs(reader: PdfReader, plan: Plan) -> JobSet:
    s = plan.settings
    seq = _sheet_sequence(plan)

    if not plan.two_sided:
        if s["last_page_first"]:
            seq = list(reversed(seq))
        w = PdfWriter()
        for sh in seq:
            render_side(w, reader, plan, sh.front, physical=True)
        return JobSet([Job("All pages", _to_bytes(w), len(seq))], False, False, len(seq))

    if s["duplex_method"] == "auto":
        w = PdfWriter()
        for sh in seq:
            render_side(w, reader, plan, sh.front, physical=True)
            render_side(w, reader, plan, sh.back, physical=True)  # blank if None
        return JobSet([Job("Both sides", _to_bytes(w), len(seq) * 2, s["sides"])], False, False, len(seq))

    # Manual (guided) two-sided printing.
    # The user always flips the stack the same way (like turning a book page),
    # so short-edge binding needs the backs turned 180°.
    rotate = (s["sides"] == "short") != s["rotate_backs"]
    fronts = PdfWriter()
    for sh in seq:
        render_side(fronts, reader, plan, sh.front, physical=True)

    backs_seq: list[Side | None] = [sh.back for sh in seq]
    remove_top = False
    if not s["reverse_backs"] and backs_seq and backs_seq[-1] is None:
        # Nothing on the very last back: drop it and ask the user to take that
        # sheet off the top of the stack before flipping.
        backs_seq.pop()
        remove_top = True
    if s["reverse_backs"]:
        backs_seq.reverse()
    backs = PdfWriter()
    for side in backs_seq:
        render_side(backs, reader, plan, side, physical=True, rotate180=rotate)

    jobs = [Job("Front sides", _to_bytes(fronts), len(seq))]
    if backs_seq:
        jobs.append(Job("Back sides", _to_bytes(backs), len(backs_seq)))
    return JobSet(jobs, True, remove_top, len(seq))


def describe(plan: Plan) -> dict:
    """Facts for the summary panel and screen readers."""
    s = plan.settings
    sheets = plan.physical_sheet_count()
    paper_label = PAPER_SIZES[s["paper"]][0].split(" (")[0]
    sides_text = {
        "one": "one side",
        "long": "both sides, flip on long edge",
        "short": "both sides, flip on short edge",
    }[s["sides"]]
    parts = [
        f"{sheets} sheet{'s' if sheets != 1 else ''} of {paper_label}",
        sides_text,
        "black and white" if s["color"] == "bw" else "colour",
        f"{s['quality']} quality",
    ]
    if s["booklet"]:
        parts.insert(1, "booklet")
    elif s["pages_per_side"] > 1:
        parts.insert(1, f"{s['pages_per_side']} pages per side")
    if s["copies"] > 1:
        parts.append(f"{s['copies']} copies")
    return {
        "sheets": sheets,
        "sheets_per_copy": len(plan.sheets),
        "source_pages": plan.source_pages,
        "selected_pages": plan.selected_pages,
        "sentence": ", ".join(parts) + ".",
        "manual_duplex": plan.two_sided and s["duplex_method"] == "manual",
    }


def calibration_pdf(paper: str = "A4") -> tuple[bytes, bytes]:
    """Two sheets: fronts show 1 and 3, backs show 2 and 4 (printed long-edge)."""
    from fpdf import FPDF

    _, w, h = PAPER_SIZES.get(paper, PAPER_SIZES["A4"])

    def make(nums, front):
        pdf = FPDF(unit="pt", format=(w, h))
        pdf.set_auto_page_break(False)
        for n in nums:
            pdf.add_page()
            pdf.set_line_width(3)
            pdf.rect(20, 20, w - 40, h - 40)
            pdf.set_font("Helvetica", "B", 220)
            pdf.set_xy(0, h * 0.28)
            pdf.cell(w, 220, str(n), align="C")
            pdf.set_font("Helvetica", "B", 28)
            pdf.set_xy(0, 60)
            pdf.cell(w, 40, "TOP OF PAGE", align="C")
            # arrow pointing to the top
            pdf.polygon([(w / 2, 110), (w / 2 - 40, 170), (w / 2 + 40, 170)], style="F")
            pdf.set_font("Helvetica", "", 18)
            pdf.set_xy(40, h * 0.72)
            label = "FRONT side" if front else "BACK side"
            pdf.multi_cell(
                w - 80,
                26,
                f"Test page {n} - {label}.\nWhen you turn this sheet over like a book page, "
                f"page {n + 1 if front else n - 1} should be on the other side, the right way up.",
                align="C",
            )
        return bytes(pdf.output())

    return make([1, 3], True), make([2, 4], False)
