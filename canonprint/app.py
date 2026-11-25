"""Local web server: the UI runs in your browser, everything stays on this computer."""

from __future__ import annotations

import io
import json
import mimetypes
import os
import platform
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import uuid
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

from pypdf import PdfReader, PdfWriter

from . import APP_NAME, __version__, convert, layout, printers, storage

STATIC = Path(__file__).with_name("static")
MAX_UPLOAD = 300 * 1024 * 1024
PREVIEW_SHEET_LIMIT = 300


class UserError(Exception):
    def __init__(self, message: str, code: str = "error", status: int = 400):
        super().__init__(message)
        self.code = code
        self.status = status


# ---------------------------------------------------------------------------
# Workspace: the files currently open
# ---------------------------------------------------------------------------


class Workspace:
    def __init__(self) -> None:
        self.dir = Path(tempfile.mkdtemp(prefix="canon-print-"))
        self.files: list[dict] = []  # {id, name, pages, path}
        self.rev = 0
        self.lock = threading.RLock()
        self._merged: tuple[int, Path, list[int]] | None = None
        self._previews: dict[str, dict] = {}
        self.pending: dict[str, dict] = {}  # print jobs waiting for "continue"

    # files ------------------------------------------------------------
    def add(self, name: str, data: bytes, password: str = "") -> dict:
        safe = Path(name).name or "document"
        up = self.dir / f"upload-{uuid.uuid4().hex[:8]}{Path(safe).suffix.lower()}"
        up.write_bytes(data)
        try:
            conv_dir = self.dir / uuid.uuid4().hex[:8]
            conv_dir.mkdir()
            src = conv_dir / safe
            up.replace(src)
            pdf = convert.to_pdf(src, conv_dir, password)
        except convert.PasswordNeeded as exc:
            raise UserError(str(exc), "password") from exc
        except convert.ConvertError as exc:
            raise UserError(str(exc)) from exc
        pages = len(PdfReader(str(pdf)).pages)
        with self.lock:
            entry = {"id": uuid.uuid4().hex[:10], "name": safe, "pages": pages, "path": str(pdf)}
            self.files.append(entry)
            self._touch()
        return entry

    def add_pdf_path(self, name: str, path: Path) -> None:
        pages = len(PdfReader(str(path)).pages)
        with self.lock:
            self.files.append({"id": uuid.uuid4().hex[:10], "name": name, "pages": pages, "path": str(path)})
            self._touch()

    def remove(self, file_id: str) -> None:
        with self.lock:
            self.files = [f for f in self.files if f["id"] != file_id]
            self._touch()

    def reorder(self, ids: list[str]) -> None:
        with self.lock:
            by_id = {f["id"]: f for f in self.files}
            new = [by_id[i] for i in ids if i in by_id]
            new += [f for f in self.files if f["id"] not in ids]
            self.files = new
            self._touch()

    def clear(self) -> None:
        with self.lock:
            self.files = []
            self._touch()

    def public_files(self) -> list[dict]:
        return [{k: f[k] for k in ("id", "name", "pages")} for f in self.files]

    def _touch(self) -> None:
        self.rev += 1
        self._merged = None
        self._previews.clear()

    # merged source ------------------------------------------------------
    def merged(self) -> tuple[Path, list[int]]:
        with self.lock:
            if not self.files:
                raise UserError("Open a document first.", "no_files")
            if self._merged and self._merged[0] == self.rev:
                return self._merged[1], self._merged[2]
            if len(self.files) == 1:
                path, starts = Path(self.files[0]["path"]), []
            else:
                w = PdfWriter()
                starts, count = [], 0
                for f in self.files:
                    if count:
                        starts.append(count)
                    w.append(f["path"])
                    count += f["pages"]
                path = self.dir / f"merged-{self.rev}.pdf"
                with open(path, "wb") as fh:
                    w.write(fh)
            self._merged = (self.rev, path, starts)
            return path, starts

    def title(self) -> str:
        if not self.files:
            return "Printout"
        if len(self.files) == 1:
            return Path(self.files[0]["name"]).stem
        return f"{Path(self.files[0]['name']).stem} + {len(self.files) - 1} more"

    # preview ----------------------------------------------------------------
    def preview(self, settings: dict) -> dict:
        path, starts = self.merged()
        reader = PdfReader(str(path))
        try:
            plan = layout.make_plan(reader, settings, starts)
        except layout.LayoutError as exc:
            raise UserError(str(exc), "layout") from exc
        pdf, index = layout.preview_pdf(reader, plan, PREVIEW_SHEET_LIMIT)
        key = uuid.uuid4().hex[:12]
        import pypdfium2 as pdfium

        with self.lock:
            # keep only a few previews alive
            while len(self._previews) > 4:
                self._previews.pop(next(iter(self._previews)))
            self._previews[key] = {
                "doc": pdfium.PdfDocument(pdf),
                "bw": settings["color"] == "bw",
                "lock": threading.Lock(),
            }
        sheets = []
        for i, (fi, bi) in enumerate(index):
            sh = plan.sheets[i]
            sheets.append(
                {
                    "front": {"img": f"/api/preview/{key}/{fi}", "pages": sh.front.numbers,
                              "landscape": sh.front.landscape},
                    "back": (
                        {"img": f"/api/preview/{key}/{bi}", "pages": sh.back.numbers if sh.back else [],
                         "landscape": sh.back.landscape if sh.back else sh.front.landscape}
                        if bi is not None
                        else None
                    ),
                }
            )
        info = layout.describe(plan)
        info.update({"sheets_list": sheets, "truncated": len(plan.sheets) > PREVIEW_SHEET_LIMIT,
                     "two_sided": plan.two_sided, "paper_ratio": plan.paper_h / plan.paper_w})
        return info

    def preview_image(self, key: str, index: int, width: int) -> bytes:
        entry = self._previews.get(key)
        if not entry:
            raise UserError("Preview expired.", "expired", 404)
        with entry["lock"]:
            doc = entry["doc"]
            if not 0 <= index < len(doc):
                raise UserError("No such page.", "missing", 404)
            page = doc[index]
            w, _ = page.get_size()
            scale = max(0.1, min(4.0, width / w))
            img = page.render(scale=scale, grayscale=entry["bw"], draw_annots=True).to_pil()
            buf = io.BytesIO()
            img.convert("L" if entry["bw"] else "RGB").save(buf, "PNG", optimize=False)
            return buf.getvalue()


WS = Workspace()


# ---------------------------------------------------------------------------
# Printing flow
# ---------------------------------------------------------------------------


def start_print(printer: str, raw_settings: dict) -> dict:
    settings = layout.normalize_settings(raw_settings)
    path, starts = WS.merged()
    reader = PdfReader(str(path))
    try:
        plan = layout.make_plan(reader, settings, starts)
    except layout.LayoutError as exc:
        raise UserError(str(exc), "layout") from exc
    jobset = layout.build_jobs(reader, plan)
    title = WS.title()
    job_id = uuid.uuid4().hex[:10]
    WS.pending[job_id] = {"printer": printer, "settings": settings, "jobs": jobset.jobs, "next": 0,
                          "title": title}
    try:
        storage.add_history(title, WS.files, path, settings, printer, jobset.sheets, starts)
    except OSError:
        pass
    storage.update_prefs({"printer": printer, "settings": settings})
    result = _send_next(job_id)
    result.update(
        {
            "manual_duplex": jobset.manual_duplex,
            "remove_top_sheet": jobset.remove_top_sheet,
            "sheets": jobset.sheets,
            "sides": settings["sides"],
            "steps": [j.label for j in jobset.jobs],
        }
    )
    return result


def _send_next(job_id: str) -> dict:
    p = WS.pending.get(job_id)
    if not p:
        raise UserError("This print job has finished or was cancelled.", "expired", 404)
    job = p["jobs"][p["next"]]
    suffix = f" ({job.label.lower()})" if len(p["jobs"]) > 1 else ""
    try:
        res = printers.print_pdf(p["printer"], job.pdf, p["title"] + suffix, p["settings"], job.sides)
    except printers.PrintError as exc:
        raise UserError(str(exc), "print") from exc
    p["next"] += 1
    done = p["next"] >= len(p["jobs"])
    if done:
        WS.pending.pop(job_id, None)
    res.update({"job_id": job_id, "step": p["next"], "total_steps": len(p["jobs"]), "done": done,
                "label": job.label, "pages": job.pages})
    return res


def start_calibration(printer: str, raw_settings: dict) -> dict:
    settings = layout.normalize_settings(raw_settings)
    settings.update({"sides": "long", "pages_per_side": 1, "booklet": False})
    fronts, backs = layout.calibration_pdf(settings["paper"])
    r = PdfReader(io.BytesIO(backs))
    w = PdfWriter()
    order = list(range(len(r.pages)))
    if settings["reverse_backs"]:
        order.reverse()
    for i in order:
        page = w.add_page(r.pages[i])
        if settings["rotate_backs"]:
            page.rotate(180)
    buf = io.BytesIO()
    w.write(buf)
    job_id = uuid.uuid4().hex[:10]
    WS.pending[job_id] = {
        "printer": printer,
        "settings": settings,
        "jobs": [layout.Job("Front sides", fronts, 2), layout.Job("Back sides", buf.getvalue(), 2)],
        "next": 0,
        "title": "Two-sided test",
    }
    res = _send_next(job_id)
    res.update({"manual_duplex": True, "remove_top_sheet": False, "sheets": 2, "sides": "long",
                "steps": ["Front sides", "Back sides"], "calibration": True})
    return res


def reprint(entry_id: str) -> dict:
    entry = next((h for h in storage.get_history() if h["id"] == entry_id), None)
    path = storage.history_pdf(entry_id)
    if not entry or not path:
        raise UserError("That print is no longer in the history.", "missing", 404)
    reader = PdfReader(str(path))
    WS.clear()
    start = 0
    for f in entry["files"]:
        w = PdfWriter()
        for i in range(start, min(start + f["pages"], len(reader.pages))):
            w.add_page(reader.pages[i])
        start += f["pages"]
        out = WS.dir / f"history-{uuid.uuid4().hex[:6]}.pdf"
        with open(out, "wb") as fh:
            w.write(fh)
        WS.add_pdf_path(f["name"], out)
    return {"files": WS.public_files(), "settings": entry["settings"], "printer": entry["printer"]}


def open_folder(path: str) -> None:
    folder = str(Path(path).parent if Path(path).is_file() else path)
    if platform.system() == "Windows":
        os.startfile(folder)  # type: ignore[attr-defined]  # noqa: S606
    elif platform.system() == "Darwin":
        subprocess.Popen(["open", folder])  # noqa: S603, S607
    else:
        subprocess.Popen(["xdg-open", folder])  # noqa: S603, S607


def app_state() -> dict:
    prefs = storage.get_prefs()
    plist = printers.list_printers()
    names = [p["name"] for p in plist]
    chosen = prefs.get("printer") if prefs.get("printer") in names else None
    if not chosen:
        chosen = next((p["name"] for p in plist if p["canon"]), None) or next(
            (p["name"] for p in plist if p["default"]), names[0]
        )
    return {
        "app": APP_NAME,
        "version": __version__,
        "platform": platform.system(),
        "printers": plist,
        "printer": chosen,
        "settings": layout.normalize_settings(prefs.get("settings")),
        "defaults": layout.DEFAULT_SETTINGS,
        "look": prefs.get("look", {}),
        "presets": storage.get_presets(),
        "history": storage.get_history(),
        "papers": [{"id": k, "label": v[0]} for k, v in layout.PAPER_SIZES.items()],
        "files": WS.public_files(),
        "driver_dialog": printers.driver_dialog_supported(),
        "output_folder": str(printers.output_folder()),
        "supported": sorted(convert.SUPPORTED),
    }


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

TOKEN = secrets.token_urlsafe(24)
LAST_SEEN = [time.time()]


class Handler(BaseHTTPRequestHandler):
    server_version = "CanonPrint"

    def log_message(self, fmt, *args):  # quieter console
        if os.environ.get("CANON_PRINT_DEBUG"):
            super().log_message(fmt, *args)

    # helpers -----------------------------------------------------------
    def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, status: int = 200):
        self._send(status, json.dumps(obj).encode(), "application/json; charset=utf-8")

    def _body(self) -> bytes:
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_UPLOAD:
            raise UserError("That file is too big (over 300 MB).")
        return self.rfile.read(n) if n else b""

    def _jbody(self) -> dict:
        raw = self._body()
        try:
            return json.loads(raw or b"{}")
        except ValueError as exc:
            raise UserError("Bad request.") from exc

    def _authorized(self) -> bool:
        host = (self.headers.get("Host") or "").split(":")[0]
        if host not in ("127.0.0.1", "localhost"):
            return False
        return secrets.compare_digest(self.headers.get("X-Token", ""), TOKEN) or secrets.compare_digest(
            parse_qs(urlparse(self.path).query).get("t", [""])[0], TOKEN
        )

    # routing -------------------------------------------------------------
    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def _dispatch(self, method: str):
        LAST_SEEN[0] = time.time()
        url = urlparse(self.path)
        path = url.path
        try:
            if method == "GET" and path in ("/", "/index.html"):
                return self._index()
            if method == "GET" and path.startswith("/static/"):
                return self._static(path[len("/static/"):])
            if path.startswith("/api/"):
                if not self._authorized():
                    return self._json({"error": "Not allowed.", "code": "forbidden"}, 403)
                return self._api(method, path[len("/api/"):], url)
            return self._json({"error": "Not found"}, 404)
        except UserError as exc:
            return self._json({"error": str(exc), "code": exc.code}, exc.status)
        except Exception as exc:  # noqa: BLE001
            traceback.print_exc()
            return self._json({"error": f"Something went wrong: {exc}", "code": "internal"}, 500)

    def _index(self):
        html = (STATIC / "index.html").read_text("utf-8").replace("__TOKEN__", TOKEN)
        self._send(200, html.encode(), "text/html; charset=utf-8",
                   {"Content-Security-Policy": "default-src 'self'; img-src 'self' blob: data:; "
                    "style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'"})

    def _static(self, rel: str):
        target = (STATIC / rel).resolve()
        if STATIC.resolve() not in target.parents or not target.is_file():
            return self._json({"error": "Not found"}, 404)
        ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype.endswith("javascript"):
            ctype += "; charset=utf-8"
        self._send(200, target.read_bytes(), ctype)

    def _api(self, method: str, route: str, url):
        q = parse_qs(url.query)
        parts = route.split("/")

        if method == "GET" and route == "state":
            return self._json(app_state())
        if method == "GET" and route == "printers":
            return self._json({"printers": printers.list_printers()})
        if method == "GET" and route == "printer-status":
            return self._json(printers.printer_status(q.get("name", [""])[0]))
        if method == "GET" and parts[0] == "preview" and len(parts) == 3:
            width = int(q.get("w", ["400"])[0])
            png = WS.preview_image(parts[1], int(parts[2]), max(60, min(1600, width)))
            return self._send(200, png, "image/png", {"Cache-Control": "private, max-age=600"})

        if method == "POST" and route == "files":
            name = unquote(self.headers.get("X-Filename", "document"))
            password = unquote(self.headers.get("X-Password", ""))
            entry = WS.add(name, self._body(), password)
            return self._json({"file": {k: entry[k] for k in ("id", "name", "pages")}, "files": WS.public_files()})
        if method == "POST" and route == "files/reorder":
            WS.reorder(self._jbody().get("order", []))
            return self._json({"files": WS.public_files()})
        if method == "POST" and route == "files/clear":
            WS.clear()
            return self._json({"files": WS.public_files()})
        if method == "DELETE" and parts[0] == "files" and len(parts) == 2:
            WS.remove(parts[1])
            return self._json({"files": WS.public_files()})

        if method == "POST" and route == "preview":
            settings = layout.normalize_settings(self._jbody().get("settings"))
            return self._json(WS.preview(settings))
        if method == "POST" and route == "print":
            b = self._jbody()
            return self._json(start_print(b.get("printer") or printers.SAVE_AS_PDF, b.get("settings") or {}))
        if method == "POST" and route == "print/continue":
            return self._json(_send_next(self._jbody().get("job_id", "")))
        if method == "POST" and route == "print/discard":
            WS.pending.pop(self._jbody().get("job_id", ""), None)
            return self._json({"ok": True})
        if method == "POST" and route == "calibrate":
            b = self._jbody()
            return self._json(start_calibration(b.get("printer") or printers.SAVE_AS_PDF, b.get("settings") or {}))
        if method == "POST" and route == "cancel-queue":
            try:
                msg = printers.cancel_all(self._jbody().get("printer", ""))
            except printers.PrintError as exc:
                raise UserError(str(exc)) from exc
            return self._json({"message": msg})
        if method == "POST" and route == "driver-dialog":
            ok = printers.open_driver_dialog(self._jbody().get("printer", ""))
            return self._json({"ok": ok})
        if method == "POST" and route == "open-folder":
            open_folder(self._jbody().get("path") or str(printers.output_folder()))
            return self._json({"ok": True})

        if method == "POST" and route == "prefs":
            b = self._jbody()
            allowed = {k: v for k, v in b.items() if k in ("look", "settings", "printer")}
            if "settings" in allowed:
                allowed["settings"] = layout.normalize_settings(allowed["settings"])
            storage.update_prefs(allowed)
            return self._json({"ok": True})
        if method == "POST" and route == "presets":
            b = self._jbody()
            name = str(b.get("name", "")).strip()[:40]
            if not name:
                raise UserError("Give the preset a name.")
            return self._json({"presets": storage.save_preset(name, layout.normalize_settings(b.get("settings")))})
        if method == "DELETE" and parts[0] == "presets" and len(parts) == 2:
            return self._json({"presets": storage.delete_preset(parts[1])})

        if method == "GET" and route == "history":
            return self._json({"history": storage.get_history()})
        if method == "POST" and parts[0] == "history" and len(parts) == 3 and parts[2] == "reprint":
            return self._json(reprint(parts[1]))
        if method == "POST" and route == "history/clear":
            storage.clear_history()
            return self._json({"history": []})

        if method == "POST" and route == "quit":
            self._json({"ok": True})
            threading.Thread(target=_shutdown, daemon=True).start()
            return None
        raise UserError("Unknown request.", "not_found", 404)


_server: ThreadingHTTPServer | None = None


def _shutdown():
    time.sleep(0.3)
    if _server:
        _server.shutdown()


def main(argv: list[str] | None = None) -> None:
    import argparse

    ap = argparse.ArgumentParser(description=f"{APP_NAME} - accessible printing for Canon G3000 series")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true", help="don't open the browser automatically")
    args = ap.parse_args(argv)

    global _server
    port = args.port
    for attempt in range(20):
        try:
            _server = ThreadingHTTPServer(("127.0.0.1", port + attempt), Handler)
            port = port + attempt
            break
        except OSError:
            continue
    if _server is None:
        sys.exit("Couldn't start: no free port.")
    _server.daemon_threads = True
    url = f"http://127.0.0.1:{port}/"
    print(f"{APP_NAME} {__version__} is running at {url}")
    print("Keep this window open while you print. Close it (or press Ctrl+C) to quit.")
    if not args.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        _server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        shutil.rmtree(WS.dir, ignore_errors=True)
        print("Goodbye.")
