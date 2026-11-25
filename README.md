# Canon Print

A friendly, accessible printing app for **Canon PIXMA G3000-series** printers (G3000, G3010, G3020, G3060, G3070 …), and any other printer.

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
