"""Talking to printers on Windows (GDI) and macOS / Linux (CUPS).

The jobs we send are already laid out page-by-page by :mod:`layout`, so the
printer only ever needs to print one portrait page per sheet. We still ask the
driver for colour mode, quality, paper size and paper type.
"""

from __future__ import annotations

import io
import os
import platform
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from .layout import PAPER_SIZES

SAVE_AS_PDF = "__save_as_pdf__"
SYSTEM = platform.system()

# Render resolution used on Windows (and for "Save as PDF" in black and white)
QUALITY_DPI = {"draft": 200, "standard": 300, "high": 600}


class PrintError(Exception):
    """User-facing printing problem."""


def looks_like_canon_g(name: str) -> bool:
    n = name.lower().replace(" ", "")
    return "canon" in n and bool(re.search(r"g3\d{3}|g30\d0|g3000", n))


def output_folder() -> Path:
    home = Path.home()
    for cand in (home / "Documents", home / "Downloads", home):
        if cand.is_dir():
            folder = cand / "Canon Print"
            folder.mkdir(exist_ok=True)
            return folder
    return Path(tempfile.gettempdir())


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def list_printers() -> list[dict]:
    try:
        if SYSTEM == "Windows":
            printers = _win_list()
        else:
            printers = _cups_list()
    except Exception:  # noqa: BLE001
        printers = []
    printers.sort(key=lambda p: (not p["canon"], not p["default"], p["name"].lower()))
    printers.append(
        {
            "name": SAVE_AS_PDF,
            "label": "Save as PDF file (no printer)",
            "default": False,
            "canon": False,
            "virtual": True,
        }
    )
    return printers


def printer_status(name: str) -> dict:
    if name == SAVE_AS_PDF:
        return {"state": "ready", "message": "Saves a PDF file instead of printing.", "jobs": 0}
    try:
        if SYSTEM == "Windows":
            return _win_status(name)
        return _cups_status(name)
    except Exception as exc:  # noqa: BLE001
        return {"state": "unknown", "message": "Status not available.", "jobs": 0, "detail": str(exc)}


def cancel_all(name: str) -> str:
    if name == SAVE_AS_PDF:
        return "Nothing to cancel."
    if SYSTEM == "Windows":
        return _win_cancel(name)
    return _cups_cancel(name)


def print_pdf(name: str, pdf: bytes, title: str, settings: dict, sides: str = "one") -> dict:
    """Send *pdf* (already laid out, portrait pages) to printer *name*."""
    if name == SAVE_AS_PDF:
        return _save_pdf(pdf, title, settings)
    if SYSTEM == "Windows":
        return _win_print(name, pdf, title, settings, sides)
    return _cups_print(name, pdf, title, settings, sides)


def driver_dialog_supported() -> bool:
    return SYSTEM == "Windows"


def open_driver_dialog(name: str) -> bool:
    if SYSTEM != "Windows":
        return False
    return _win_driver_dialog(name)


# ---------------------------------------------------------------------------
# Save as PDF
# ---------------------------------------------------------------------------


def _safe_name(title: str) -> str:
    base = re.sub(r"[^\w\- .()]+", "_", title).strip(" ._") or "printout"
    return base[:80]


def _save_pdf(pdf: bytes, title: str, settings: dict) -> dict:
    if settings.get("color") == "bw":
        pdf = grayscale_pdf(pdf, QUALITY_DPI["standard"] if settings.get("quality") != "draft" else 150)
    folder = output_folder()
    stamp = time.strftime("%Y-%m-%d %H.%M.%S")
    path = folder / f"{_safe_name(title)} - {stamp}.pdf"
    path.write_bytes(pdf)
    return {"ok": True, "saved_to": str(path), "message": f"Saved to {path}"}


def grayscale_pdf(pdf: bytes, dpi: int) -> bytes:
    """Rasterise to a greyscale PDF (used for 'Save as PDF' in black and white)."""
    import pypdfium2 as pdfium
    from PIL import Image

    doc = pdfium.PdfDocument(pdf)
    images: list[Image.Image] = []
    sizes = []
    for i in range(len(doc)):
        page = doc[i]
        w, h = page.get_size()
        sizes.append((w, h))
        img = page.render(scale=dpi / 72, grayscale=True, draw_annots=True).to_pil().convert("L")
        images.append(img)
    out = io.BytesIO()
    images[0].save(out, "PDF", resolution=dpi, save_all=True, append_images=images[1:])
    return out.getvalue()


# ---------------------------------------------------------------------------
# CUPS (macOS, Linux)
# ---------------------------------------------------------------------------


def _run(cmd: list[str], timeout: int = 15) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)


def _cups_list() -> list[dict]:
    if not shutil.which("lpstat"):
        return []
    default = ""
    d = _run(["lpstat", "-d"])
    m = re.search(r":\s*(\S+)\s*$", d.stdout.strip())
    if m:
        default = m.group(1)
    names = [line.strip() for line in _run(["lpstat", "-e"]).stdout.splitlines() if line.strip()]
    if not names:
        for line in _run(["lpstat", "-p"]).stdout.splitlines():
            m = re.match(r"printer\s+(\S+)", line)
            if m:
                names.append(m.group(1))
    return [
        {
            "name": n,
            "label": n.replace("_", " "),
            "default": n == default,
            "canon": looks_like_canon_g(n.replace("_", " ")),
            "virtual": False,
        }
        for n in names
    ]


def _cups_status(name: str) -> dict:
    p = _run(["lpstat", "-p", name])
    text = (p.stdout + p.stderr).strip()
    jobs = len([line for line in _run(["lpstat", "-o", name]).stdout.splitlines() if line.strip()])
    low = text.lower()
    if "disabled" in low or "paused" in low:
        state, msg = "error", "The printer is paused or turned off."
    elif "printing" in low or "now printing" in low:
        state, msg = "busy", "Printing…"
    elif "idle" in low:
        state, msg = "ready", "Ready."
    else:
        state, msg = "unknown", text.splitlines()[0] if text else "Status unknown."
    # surface reasons such as "media-empty" or "offline"
    reason = re.search(r"\n\s+(.+)$", text)
    if reason and state != "ready":
        msg += " " + reason.group(1).strip()
    return {"state": state, "message": msg, "jobs": jobs}


def _cups_cancel(name: str) -> str:
    r = _run(["cancel", "-a", name])
    if r.returncode != 0:
        raise PrintError(r.stderr.strip() or "Couldn't cancel the print jobs.")
    return "All waiting print jobs were cancelled."


def cups_options(settings: dict, sides: str) -> list[str]:
    _, w, h = PAPER_SIZES[settings["paper"]]
    media = {
        "A4": "A4",
        "Letter": "Letter",
        "Legal": "Legal",
        "A5": "A5",
        "B5": "B5",
        "4x6": "4x6",
        "5x7": "5x7",
        "8x10": "8x10",
        "EnvDL": "DL",
        "Env10": "Env10",
    }[settings["paper"]]
    bw = settings["color"] == "bw"
    quality = {"draft": ("3", "Draft"), "standard": ("4", "Normal"), "high": ("5", "High")}[settings["quality"]]
    media_type = {
        "plain": "stationery",
        "photo_glossy": "photographic-glossy",
        "photo_matte": "photographic-matte",
        "envelope": "envelope",
    }[settings["paper_type"]]
    opts = {
        "media": media,
        "media-type": media_type,
        "print-color-mode": "monochrome" if bw else "color",
        "ColorModel": "Gray" if bw else "RGB",
        "print-quality": quality[0],
        "cupsPrintQuality": quality[1],
        "sides": {"one": "one-sided", "long": "two-sided-long-edge", "short": "two-sided-short-edge"}[sides],
        "orientation-requested": "3",  # portrait: we already rotated everything
        "fit-to-page": "true",
        "number-up": "1",
    }
    out: list[str] = []
    for k, v in opts.items():
        out += ["-o", f"{k}={v}"]
    return out


def _cups_print(name: str, pdf: bytes, title: str, settings: dict, sides: str) -> dict:
    if not shutil.which("lp"):
        raise PrintError("The 'lp' printing command isn't available on this computer.")
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as fh:
        fh.write(pdf)
        path = fh.name
    try:
        cmd = ["lp", "-d", name, "-t", title[:120], *cups_options(settings, sides), path]
        r = _run(cmd, timeout=60)
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    if r.returncode != 0:
        raise PrintError(r.stderr.strip() or "The printer refused the job.")
    m = re.search(r"request id is (\S+)", r.stdout)
    return {"ok": True, "job": m.group(1) if m else None, "message": "Sent to the printer."}


# ---------------------------------------------------------------------------
# Windows
# ---------------------------------------------------------------------------

_win_devmodes: dict = {}  # printer name -> DEVMODE chosen in the driver's own dialog


def _win_list() -> list[dict]:
    import win32print  # type: ignore

    flags = win32print.PRINTER_ENUM_LOCAL | win32print.PRINTER_ENUM_CONNECTIONS
    try:
        default = win32print.GetDefaultPrinter()
    except Exception:  # noqa: BLE001
        default = ""
    skip = ("microsoft xps", "onenote", "fax", "microsoft print to pdf")
    result = []
    for info in win32print.EnumPrinters(flags, None, 1):
        name = info[2]
        if any(s in name.lower() for s in skip):
            continue
        result.append(
            {
                "name": name,
                "label": name,
                "default": name == default,
                "canon": looks_like_canon_g(name),
                "virtual": False,
            }
        )
    return result


_WIN_STATUS = [
    (0x00000008, "error", "The printer needs attention (paper jam?)."),
    (0x00000010, "error", "The printer is out of paper. Load paper in the rear tray."),
    (0x00000040, "error", "There's a paper problem."),
    (0x00000080, "error", "The printer is offline. Check it's turned on and connected."),
    (0x00400000, "error", "The printer is offline. Check it's turned on and connected."),
    (0x00000002, "error", "The printer has an error."),
    (0x00000001, "error", "Printing is paused."),
    (0x00000400, "busy", "Printing…"),
    (0x00004000, "busy", "Printing…"),
    (0x00000200, "busy", "Busy…"),
    (0x00010000, "busy", "Warming up…"),
    (0x00040000, "warning", "Ink may be running low."),
    (0x00100000, "error", "The printer needs you to do something - check its screen."),
    (0x00000004, "busy", "Cancelling…"),
]


def _win_status(name: str) -> dict:
    import win32print  # type: ignore

    h = win32print.OpenPrinter(name)
    try:
        info = win32print.GetPrinter(h, 2)
    finally:
        win32print.ClosePrinter(h)
    status = info["Status"]
    for bit, state, msg in _WIN_STATUS:
        if status & bit:
            return {"state": state, "message": msg, "jobs": info["cJobs"]}
    attrs = info.get("Attributes", 0)
    if attrs & 0x00000400:  # PRINTER_ATTRIBUTE_WORK_OFFLINE
        return {"state": "error", "message": "The printer is set to 'Use printer offline'.", "jobs": info["cJobs"]}
    return {"state": "ready", "message": "Ready.", "jobs": info["cJobs"]}


def _win_cancel(name: str) -> str:
    import win32print  # type: ignore

    h = win32print.OpenPrinter(name)
    try:
        jobs = win32print.EnumJobs(h, 0, 999, 1)
        n = 0
        for job in jobs:
            try:
                win32print.SetJob(h, job["JobId"], 0, None, win32print.JOB_CONTROL_DELETE)
                n += 1
            except Exception:  # noqa: BLE001
                pass
    finally:
        win32print.ClosePrinter(h)
    return f"Cancelled {n} print job{'s' if n != 1 else ''}." if n else "There were no print jobs waiting."


def _win_paper_code(name: str, port: str, paper: str) -> int | None:
    """Find the driver's paper code whose size matches ours (within 2 mm)."""
    import win32print  # type: ignore

    _, w, h = PAPER_SIZES[paper]
    want = (round(w / 72 * 254), round(h / 72 * 254))  # tenths of a millimetre
    try:
        codes = win32print.DeviceCapabilities(name, port, 2)  # DC_PAPERS
        sizes = win32print.DeviceCapabilities(name, port, 3)  # DC_PAPERSIZE
    except Exception:  # noqa: BLE001
        return None
    for code, size in zip(codes, sizes):
        x, y = (size["x"], size["y"]) if isinstance(size, dict) else size
        if abs(x - want[0]) <= 20 and abs(y - want[1]) <= 20:
            return code
    return None


def _win_devmode(name: str, settings: dict, sides: str):
    import win32con  # type: ignore
    import win32print  # type: ignore

    h = win32print.OpenPrinter(name)
    try:
        info = win32print.GetPrinter(h, 2)
        dm = _win_devmodes.get(name) or info["pDevMode"]
        port = info["pPortName"]
        dm.Fields |= win32con.DM_COLOR | win32con.DM_PRINTQUALITY | win32con.DM_ORIENTATION | win32con.DM_COPIES
        dm.Color = 1 if settings["color"] == "bw" else 2  # DMCOLOR_MONOCHROME / DMCOLOR_COLOR
        dm.PrintQuality = {"draft": -1, "standard": -3, "high": -4}[settings["quality"]]
        dm.Orientation = 1  # portrait - we rotate pages ourselves
        dm.Copies = 1  # copies are laid out by the app
        code = _win_paper_code(name, port, settings["paper"])
        if code:
            dm.Fields |= win32con.DM_PAPERSIZE
            dm.PaperSize = code
        dm.Fields |= win32con.DM_DUPLEX
        dm.Duplex = {"one": 1, "long": 2, "short": 3}[sides]  # SIMPLEX / VERTICAL / HORIZONTAL
        try:
            win32print.DocumentProperties(
                0, h, name, dm, dm, win32con.DM_IN_BUFFER | win32con.DM_OUT_BUFFER
            )
        except Exception:  # noqa: BLE001
            pass
        return dm
    finally:
        win32print.ClosePrinter(h)


def _win_driver_dialog(name: str) -> bool:
    import win32con  # type: ignore
    import win32print  # type: ignore

    h = win32print.OpenPrinter(name)
    try:
        dm = _win_devmodes.get(name) or win32print.GetPrinter(h, 2)["pDevMode"]
        r = win32print.DocumentProperties(
            0, h, name, dm, dm, win32con.DM_IN_PROMPT | win32con.DM_IN_BUFFER | win32con.DM_OUT_BUFFER
        )
        if r == 1:  # IDOK
            _win_devmodes[name] = dm
            return True
        return False
    finally:
        win32print.ClosePrinter(h)


def _win_print(name: str, pdf: bytes, title: str, settings: dict, sides: str) -> dict:
    import pypdfium2 as pdfium
    import win32con  # type: ignore
    import win32gui  # type: ignore
    import win32ui  # type: ignore
    from PIL import ImageWin

    dm = _win_devmode(name, settings, sides)
    hdc = win32gui.CreateDC("WINSPOOL", name, dm)
    if not hdc:
        raise PrintError("Couldn't connect to the printer.")
    dc = win32ui.CreateDCFromHandle(hdc)
    try:
        phys_w = dc.GetDeviceCaps(win32con.PHYSICALWIDTH)
        phys_h = dc.GetDeviceCaps(win32con.PHYSICALHEIGHT)
        off_x = dc.GetDeviceCaps(win32con.PHYSICALOFFSETX)
        off_y = dc.GetDeviceCaps(win32con.PHYSICALOFFSETY)
        dev_dpi = dc.GetDeviceCaps(win32con.LOGPIXELSX) or 300
        dpi = min(dev_dpi, QUALITY_DPI[settings["quality"]])
        bw = settings["color"] == "bw"

        doc = pdfium.PdfDocument(pdf)
        dc.StartDoc(title[:120])
        try:
            for i in range(len(doc)):
                page = doc[i]
                w_pt, h_pt = page.get_size()
                img = page.render(
                    scale=dpi / 72,
                    grayscale=bw,
                    draw_annots=True,
                    may_draw_forms=True,
                    optimize_mode="print",
                ).to_pil()
                img = img.convert("L" if bw else "RGB")
                dc.StartPage()
                # Map the whole PDF page onto the whole physical sheet; the
                # printable area starts at (off_x, off_y).
                target_w = round(w_pt / 72 * dev_dpi)
                target_h = round(h_pt / 72 * dev_dpi)
                x0 = (phys_w - target_w) // 2 - off_x
                y0 = (phys_h - target_h) // 2 - off_y
                ImageWin.Dib(img).draw(dc.GetHandleOutput(), (x0, y0, x0 + target_w, y0 + target_h))
                dc.EndPage()
                page.close()
        except Exception:
            dc.AbortDoc()
            raise
        dc.EndDoc()
    finally:
        dc.DeleteDC()
    return {"ok": True, "job": None, "message": "Sent to the printer."}
