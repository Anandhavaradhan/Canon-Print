# Easy Print: printing for Canon G3000-series printers

An Android app (plus a desktop version) that makes everyday printing on **Canon PIXMA G3000-series** printers (G3000, G3010, G3020, G3060, G3070 …) simple and accessible.

## Android app

**Install:** download `EasyPrint.apk` from this repository’s *Actions → Build Android app → latest run → Artifacts*. Open it on your phone and allow “Install unknown apps” when Android asks.

**How it prints:** the original G3000 has no AirPrint/IPP, which is why Android's built-in printing and Mopria can't find it. Easy Print talks to it **the same way Canon's own software does**, with no Android print settings or plug-ins:

- **Finds it** on your Wi-Fi with SNMP (Canon's discovery method), shows *Ready*, *Out of paper*, *Paper jam* etc.
- **Sends jobs** straight to the printer's port 9100 in Canon's own job format (IVEC commands + 600 dpi page images covering the printer's printable area), the same bytes Canon's Linux driver produces for the G3000.

Newer printers that do speak IPP (G3010, G3020…) also work over IPP automatically.

| You want to… | In Easy Print |
|---|---|
| Print on both sides, turning like a book | **Sides → Flip on long edge** |
| Print on both sides, flipping up like a notepad | **Sides → Flip on short edge** |
| Fit 2 pages on each side of the paper | **Pages per side → 2 pages** (also 4, 6, 9, 16) |
| Make a folded booklet | **Pages per side → Booklet** |
| Black & white or colour | **Colour & quality** |
| Draft (saves ink), Standard or High quality | **Colour & quality → Quality** |
| Only some pages · odd/even only | **Which pages** (`1-3, 5, 8-`) · **More options → Print only** |
| Copies, kept in sets or not | **Copies** |
| Paper size & type, orientation, fit / actual / custom %, margins, borders | **More options** |
| Print something again | **Recent** (clock icon) |
| One-tap favourite settings | **Quick picks**, and **Save current** to add your own |

**Two-sided printing on the G3000 series.** These printers can’t turn paper over by themselves. Easy Print prints all the fronts (it knows when they’ve finished), then shows an illustrated, optionally spoken guide for putting the pile back. The move is always the same: turn it over like a book page. Then it prints the backs in the right order and the right way up, including odd page counts and copies. If backs ever come out wrong, run **Printer → Two-sided test** once and answer two yes/no questions to fix it.

**Opening things:** tap *Choose document*, or use **Share → Easy Print** from any app (Files, Gmail, Photos, Chrome, WhatsApp…). PDFs, photos and text work. For Word or Excel files, use that app’s Share → Print or Save as PDF first.

**Accessibility:** works with TalkBack, Switch Access and the phone’s font size. It also has its own **Display** settings (four text sizes; Light, Dark and High-contrast themes; reduce animation; read instructions aloud), 48 dp+ touch targets, and plain language throughout.

**If the printer isn’t found:** make sure the phone and printer are on the same Wi-Fi and the printer's Wi-Fi lamp is on, then tap *Search again*, or add it by IP address (Printer → Add a printer by IP address). Your Wi-Fi router's list of connected devices shows the address (look for a name starting with "Canon").

**Print quality on the G3000:** Canon's driver for this model only offers 600 dpi and sends no quality setting. Easy Print passes Draft/High in the page header, but the G3000 may print at its normal quality either way.

### How it was checked (no G3000 was available)

`tools/printsim/` holds the test rig:

- **Reference comparison:** the app's Canon job stream is byte-for-byte identical (apart from the timestamp) to the output of an independent open-source Canon MegaTank driver that is confirmed printing on real hardware.
- **Fake G3000** (`fake_g3000.py`): answers SNMP like a Canon printer and strictly validates every received job (XML, block order, sizes, page geometry decoded with CUPS's libcups).
- **Virtual printer + person** (`simulate.py`, `run_checks.py`): feeds the printed pages through a simulated G3000 and follows the app's on-screen two-sided instructions, then checks every page is present, in order and upright (long edge, short edge, 2/4 per side, booklet, copies, ranges).
- The real app (`AppFlowTest`) was driven end to end against the fake G3000: share a PDF → add printer → print both sides → follow the guide, plus an accessibility audit.

### Building the Android app

```
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease
# APK: android/app/build/outputs/apk/release/app-release.apk
```

CI signs with a throwaway key unless you add repository secrets `EASYPRINT_KEYSTORE_B64` (base64 of a .jks), `EASYPRINT_KEYSTORE_PASSWORD`, `EASYPRINT_KEY_ALIAS` and `EASYPRINT_KEY_PASSWORD`. With a fixed key, new versions install over old ones without uninstalling.

Code map: `core/` is pure Kotlin (layout engine, PWG raster encoder, Canon IVEC job writer + SNMP client in `CanonG3000.kt`/`Snmp.kt`, IPP client; unit-tested on the JVM), `android/` is rendering (PdfRenderer, in bands so 600 dpi never needs a huge bitmap), Wi-Fi discovery, the two print paths and storage, and `ui/` is Jetpack Compose.

---

## Desktop version (Windows / macOS / Linux)

It runs on your computer and opens in your web browser. Nothing is uploaded anywhere.

## What it does

| You want to… | In Canon Print |
|---|---|
| Print on both sides, turning like a book | **Sides → Flip on long edge** |
| Print on both sides, flipping up like a notepad / calendar | **Sides → Flip on short edge** |
| Fit 2 pages on each side of the paper | **Pages per side → 2 pages** (also 4, 6, 9, 16) |
| Make a folded booklet | **Pages per side → Booklet** |
| Black & white or colour | **Colour & quality** |
| Draft (saves ink), Standard or High quality | **Colour & quality → Quality** |
| Only some pages | **Which pages**: `1-3, 5, 8-` · plus *odd / even only* under More options |
| Several copies | **Copies** (kept in sets or not) |
| Paper size & type, orientation, fit / actual size / custom %, margins, borders | **More options** |
| Print it again later | **Recent** (top bar) |
| One-click favourite settings | **Quick picks**, and **Save current** to add your own |

**Two-sided printing on the G3000 series.** These printers can’t turn the paper over by themselves. Canon Print prints all the fronts, then shows a short illustrated guide for putting the pile back (always the same move: turn it over like a book page). Then it prints the backs, already in the right order and the right way up. It also handles odd page counts and copies. If your backs ever come out upside down or on the wrong sheets, run **Printer → Two-sided test** once and answer two yes/no questions; it fixes itself. If you have a printer with automatic two-sided printing, choose **More options → Two-sided printing → Automatic**.

**Opens:** PDF, photos (JPG, PNG, GIF, BMP, TIFF, WebP; HEIC with `pillow-heif`), text files, and Word / Excel / PowerPoint files (needs free LibreOffice, or Microsoft Office on Windows). Drop several files at once; reorder them; each can start on its own sheet.

**Also:** live preview of every sheet, front and back (click the sheet to turn it over), printer status (ready / printing / out of paper / offline), cancel stuck jobs, **Save as PDF** to check a layout without paper, and on Windows the Canon driver’s own settings window.

## Accessibility

- Fully usable with the keyboard and screen readers (NVDA, JAWS, VoiceOver, Narrator): real form controls, labelled groups, live announcements of what will print.
- **Display** menu: four text sizes, Light / Dark / **High contrast** themes, reduce animation.
- The two-sided instructions can be **read aloud**.
- Big click targets, plain language, nothing communicated by colour alone.
- Shortcuts: `Ctrl+O` open · `Ctrl+P` print · `←` `→` sheets · `F` turn sheet over · `?` help.

## Start it

**Windows (easiest):** download `CanonPrint.exe` from this repository’s *Actions → Build Windows app → Artifacts*, and double-click it.

**Windows from source:** install [Python 3.10+](https://www.python.org/downloads/) (tick *Add python.exe to PATH*), then double-click **`Start Canon Print (Windows).bat`**. The first start installs what it needs.

**macOS / Linux:** `./start.sh` (uses CUPS, which is already on your computer).

A small window stays open while the app runs; your browser opens Canon Print automatically. Use **Printer → Quit Canon Print** or close that window to stop.

## Paper tips for the G3000 series

- Load paper in the **rear tray**, print side facing up, and slide the paper guides against it.
- **Draft + Black & white** uses the least ink. **High** quality plus the matching photo paper type is best for photos.

## For developers

```
pip install -r requirements.txt pytest
python run.py            # http://127.0.0.1:8765
python -m pytest tests
```

- `canonprint/layout.py`: page layout engine (n-up, booklet, ranges, copies, manual duplex). Every output page is portrait, so two-sided results don’t depend on the driver.
- `canonprint/printers.py`: Windows (GDI via pywin32 + pdfium rendering, with colour/quality/paper set through DEVMODE) and CUPS (`lp` with IPP options).
- `canonprint/convert.py`: files to PDF.
- `canonprint/app.py`: local server (127.0.0.1 only, per-session token).
- `canonprint/static/`: the UI (plain HTML/CSS/JS, no build step).
