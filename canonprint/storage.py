"""Remembered settings, saved presets and print history (plain JSON files)."""

from __future__ import annotations

import json
import os
import shutil
import threading
import time
import uuid
from pathlib import Path

HISTORY_LIMIT = 20
_lock = threading.Lock()


def data_dir() -> Path:
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
        d = base / "Canon Print"
    else:
        d = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "canon-print"
    d.mkdir(parents=True, exist_ok=True)
    (d / "history").mkdir(exist_ok=True)
    return d


def _path(name: str) -> Path:
    return data_dir() / f"{name}.json"


def load(name: str, default):
    try:
        return json.loads(_path(name).read_text("utf-8"))
    except (OSError, ValueError):
        return default


def save(name: str, value) -> None:
    with _lock:
        tmp = _path(name).with_suffix(".tmp")
        tmp.write_text(json.dumps(value, indent=2), "utf-8")
        tmp.replace(_path(name))


# ---- preferences (last settings, printer, calibration, look & feel) --------


def get_prefs() -> dict:
    return load("prefs", {})


def update_prefs(changes: dict) -> dict:
    prefs = get_prefs()
    prefs.update(changes)
    save("prefs", prefs)
    return prefs


# ---- user presets ----------------------------------------------------------


def get_presets() -> list[dict]:
    return load("presets", [])


def save_preset(name: str, settings: dict) -> list[dict]:
    presets = [p for p in get_presets() if p["name"].lower() != name.lower()]
    presets.append({"id": uuid.uuid4().hex[:10], "name": name, "settings": settings})
    save("presets", presets)
    return presets


def delete_preset(preset_id: str) -> list[dict]:
    presets = [p for p in get_presets() if p["id"] != preset_id]
    save("presets", presets)
    return presets


# ---- history ---------------------------------------------------------------


def get_history() -> list[dict]:
    items = load("history", [])
    return [i for i in items if (data_dir() / "history" / f"{i['id']}.pdf").exists()]


def add_history(title: str, files: list[dict], source_pdf: Path, settings: dict, printer: str, sheets: int,
                file_starts: list[int]) -> dict:
    entry = {
        "id": uuid.uuid4().hex[:12],
        "time": time.time(),
        "title": title,
        "files": [{"name": f["name"], "pages": f["pages"]} for f in files],
        "settings": settings,
        "printer": printer,
        "sheets": sheets,
        "file_starts": file_starts,
    }
    shutil.copyfile(source_pdf, data_dir() / "history" / f"{entry['id']}.pdf")
    items = [entry] + load("history", [])
    for old in items[HISTORY_LIMIT:]:
        (data_dir() / "history" / f"{old['id']}.pdf").unlink(missing_ok=True)
    save("history", items[:HISTORY_LIMIT])
    return entry


def history_pdf(entry_id: str) -> Path | None:
    p = data_dir() / "history" / f"{entry_id}.pdf"
    return p if p.exists() else None


def clear_history() -> None:
    for i in load("history", []):
        (data_dir() / "history" / f"{i['id']}.pdf").unlink(missing_ok=True)
    save("history", [])
