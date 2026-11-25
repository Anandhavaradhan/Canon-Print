"""Turn whatever the user opens (PDF, photos, text, Office files) into PDF."""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import tempfile
from pathlib import Path

from pypdf import PdfReader, PdfWriter

PDF_EXT = {".pdf"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tif", ".tiff", ".webp", ".heic", ".heif"}
TEXT_EXT = {".txt", ".text", ".md", ".csv", ".log", ".json", ".xml", ".ini", ".py", ".js", ".html", ".htm"}
OFFICE_EXT = {
    ".doc", ".docx", ".odt", ".rtf", ".xls", ".xlsx", ".ods",
    ".ppt", ".pptx", ".odp", ".pages", ".wpd",
}
SUPPORTED = PDF_EXT | IMAGE_EXT | TEXT_EXT | OFFICE_EXT


class ConvertError(Exception):
    """User-facing conversion problem."""


class PasswordNeeded(ConvertError):
    pass


def describe_supported() -> str:
    return "PDF, photos (JPG, PNG, HEIC…), text files, and Word / Excel / PowerPoint files"


def to_pdf(src: Path, out_dir: Path, password: str = "") -> Path:
    """Convert *src* into a normal, unencrypted PDF inside *out_dir*."""
    ext = src.suffix.lower()
    out = out_dir / (src.stem + ".pdf")
    i = 1
    while out.exists():
        out = out_dir / f"{src.stem}-{i}.pdf"
        i += 1

    if ext in PDF_EXT:
        _normalize_pdf(src, out, password)
    elif ext in IMAGE_EXT:
        _images_to_pdf(src, out)
    elif ext in OFFICE_EXT or ext in {".html", ".htm"}:
        produced = _office_to_pdf(src, out_dir)
        if produced is None:
            if ext in {".html", ".htm"}:
                _text_to_pdf(src, out)
                return out
            raise ConvertError(
                f"To print {ext} files, install the free LibreOffice "
                "(libreoffice.org)" + (" or Microsoft Office" if os.name == "nt" else "")
                + ", or save the file as PDF first."
            )
        _normalize_pdf(produced, out, "")
        if produced != out:
            produced.unlink(missing_ok=True)
    elif ext in TEXT_EXT:
        _text_to_pdf(src, out)
    else:
        raise ConvertError(
            f'"{src.name}" is a {ext or "unknown"} file. You can open {describe_supported()}.'
        )
    return out


def _normalize_pdf(src: Path, out: Path, password: str) -> None:
    try:
        reader = PdfReader(str(src))
    except Exception as exc:  # noqa: BLE001
        raise ConvertError(f'"{src.name}" could not be read as a PDF ({exc}).') from exc
    if reader.is_encrypted:
        ok = False
        for pw in (password, ""):
            try:
                if reader.decrypt(pw):
                    ok = True
                    break
            except Exception:  # noqa: BLE001
                pass
        if not ok:
            raise PasswordNeeded(f'"{src.name}" is protected with a password.')
    writer = PdfWriter()
    try:
        for page in reader.pages:
            writer.add_page(page)
        for page in writer.pages:
            if page.rotation:
                page.transfer_rotation_to_content()
    except Exception as exc:  # noqa: BLE001
        raise ConvertError(f'"{src.name}" is damaged and could not be read ({exc}).') from exc
    if len(writer.pages) == 0:
        raise ConvertError(f'"{src.name}" has no pages.')
    with open(out, "wb") as fh:
        writer.write(fh)


def _images_to_pdf(src: Path, out: Path) -> None:
    from PIL import Image, ImageOps, ImageSequence

    try:
        try:
            import pillow_heif  # type: ignore

            pillow_heif.register_heif_opener()
        except ImportError:
            pass
        img = Image.open(src)
    except Exception as exc:  # noqa: BLE001
        hint = " Install pillow-heif to print iPhone photos." if src.suffix.lower() in {".heic", ".heif"} else ""
        raise ConvertError(f'"{src.name}" could not be opened as a picture.{hint}') from exc

    frames = []
    for frame in ImageSequence.Iterator(img):
        f = ImageOps.exif_transpose(frame.copy())
        if f.mode in ("RGBA", "LA", "P"):
            f = f.convert("RGBA")
            bg = Image.new("RGB", f.size, "white")
            bg.paste(f, mask=f.split()[-1])
            f = bg
        elif f.mode != "RGB":
            f = f.convert("RGB")
        frames.append(f)
        if src.suffix.lower() == ".gif":
            break  # animated GIF: first frame only
    dpi = img.info.get("dpi", (150, 150))
    try:
        res = float(dpi[0]) if dpi and float(dpi[0]) >= 50 else 150.0
    except (TypeError, ValueError):
        res = 150.0
    frames[0].save(out, "PDF", resolution=res, save_all=True, append_images=frames[1:], quality=95)


def _find_font() -> str | None:
    candidates = [
        r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\arial.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "/Library/Fonts/Arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
    ]
    return next((c for c in candidates if os.path.exists(c)), None)


def _text_to_pdf(src: Path, out: Path) -> None:
    from fpdf import FPDF

    raw = src.read_bytes()
    for enc in ("utf-8-sig", "utf-16", "cp1252", "latin-1"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if src.suffix.lower() in {".html", ".htm"}:
        import html
        import re

        text = re.sub(r"(?is)<(script|style).*?</\1>", "", text)
        text = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</h\d>|</li>|</tr>", "\n", text)
        text = html.unescape(re.sub(r"<[^>]+>", "", text))
        text = re.sub(r"\n{3,}", "\n\n", text)

    pdf = FPDF(unit="mm", format="A4")
    pdf.set_margins(20, 20, 20)
    pdf.set_auto_page_break(True, 20)
    font = _find_font()
    if font:
        pdf.add_font("Body", "", font)
        pdf.set_font("Body", size=11)
    else:
        pdf.set_font("Helvetica", size=11)
        text = text.encode("latin-1", "replace").decode("latin-1")
    pdf.add_page()
    text = text.replace("\t", "    ").replace("\r\n", "\n").replace("\r", "\n")
    pdf.multi_cell(0, 5.5, text or " ")
    pdf.output(str(out))


def _find_soffice() -> str | None:
    for name in ("soffice", "libreoffice"):
        p = shutil.which(name)
        if p:
            return p
    for c in (
        r"C:\Program Files\LibreOffice\program\soffice.exe",
        r"C:\Program Files (x86)\LibreOffice\program\soffice.exe",
        "/Applications/LibreOffice.app/Contents/MacOS/soffice",
    ):
        if os.path.exists(c):
            return c
    return None


def _office_to_pdf(src: Path, out_dir: Path) -> Path | None:
    soffice = _find_soffice()
    if soffice:
        with tempfile.TemporaryDirectory() as profile:
            cmd = [
                soffice,
                f"-env:UserInstallation={Path(profile).as_uri()}",
                "--headless",
                "--convert-to",
                "pdf",
                "--outdir",
                str(out_dir),
                str(src),
            ]
            try:
                subprocess.run(cmd, capture_output=True, timeout=180, check=False)
            except (OSError, subprocess.TimeoutExpired):
                pass
        produced = out_dir / (src.stem + ".pdf")
        if produced.exists():
            return produced
    if platform.system() == "Windows":
        return _ms_office_to_pdf(src, out_dir)
    return None


def _ms_office_to_pdf(src: Path, out_dir: Path) -> Path | None:
    try:
        import pythoncom  # type: ignore
        import win32com.client  # type: ignore
    except ImportError:
        return None
    ext = src.suffix.lower()
    target = out_dir / (src.stem + ".office.pdf")
    pythoncom.CoInitialize()
    try:
        if ext in {".doc", ".docx", ".odt", ".rtf", ".wpd", ".html", ".htm"}:
            app = win32com.client.DispatchEx("Word.Application")
            app.Visible = False
            try:
                doc = app.Documents.Open(str(src.resolve()), ReadOnly=True)
                doc.SaveAs2(str(target), FileFormat=17)
                doc.Close(False)
            finally:
                app.Quit()
        elif ext in {".xls", ".xlsx", ".ods"}:
            app = win32com.client.DispatchEx("Excel.Application")
            app.Visible = False
            app.DisplayAlerts = False
            try:
                wb = app.Workbooks.Open(str(src.resolve()), ReadOnly=True)
                wb.ExportAsFixedFormat(0, str(target))
                wb.Close(False)
            finally:
                app.Quit()
        elif ext in {".ppt", ".pptx", ".odp"}:
            app = win32com.client.DispatchEx("PowerPoint.Application")
            try:
                pres = app.Presentations.Open(str(src.resolve()), ReadOnly=True, WithWindow=False)
                pres.SaveAs(str(target), 32)
                pres.Close()
            finally:
                app.Quit()
        else:
            return None
    except Exception:  # noqa: BLE001
        return None
    finally:
        pythoncom.CoUninitialize()
    return target if target.exists() else None
