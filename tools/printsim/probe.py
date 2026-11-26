"""Machine-readable test pages.

Every page has a solid colour band across its top (colour = page number) and a
square of the same colour in its bottom-left corner. The pair is chiral, so a reader can tell
upright / upside-down / mirrored / sideways apart, and which page it is.
"""
import io
import numpy as np

PALETTE = [  # page n -> PALETTE[n-1]; all far from white, black, grey and cyan
    (230, 25, 75), (60, 180, 75), (255, 140, 0), (0, 70, 200), (145, 30, 180),
    (240, 50, 230), (128, 128, 0), (170, 110, 40), (128, 0, 0), (0, 0, 128),
    (250, 190, 212), (170, 255, 195), (255, 215, 180), (200, 200, 0), (255, 0, 0),
    (0, 128, 0), (190, 0, 120), (90, 40, 0),
]
MARKER = (0, 230, 230)


def make_pdf(path, n_pages, landscape=(), rotated=()):
    """rotated pages are stored sideways with /Rotate 90 so viewers show them upright."""
    from fpdf import FPDF
    from pypdf import PdfReader, PdfWriter, Transformation

    pdf = FPDF(unit="pt", format="A4")
    pdf.set_auto_page_break(False)
    for n in range(1, n_pages + 1):
        land = n in landscape
        pdf.add_page(orientation="L" if land else "P")
        w, h = pdf.w, pdf.h
        pdf.set_fill_color(*PALETTE[n - 1])
        pdf.rect(0, 0, w, h * 0.3, "F")
        pdf.rect(30, h - 130, 100, 100, "F")  # same colour: band + square identify the page and its orientation
        pdf.set_text_color(60, 60, 60)
        pdf.set_font("Helvetica", "B", 160)
        pdf.set_xy(0, h * 0.42)
        pdf.cell(w, 160, str(n), align="C")
    reader = PdfReader(io.BytesIO(bytes(pdf.output())))
    writer = PdfWriter()
    for i, page in enumerate(reader.pages, start=1):
        if i in rotated:
            # store content turned 90° anticlockwise on a landscape media box, then /Rotate 90
            w, h = float(page.mediabox.width), float(page.mediabox.height)
            new = writer.add_blank_page(h, w)
            new.merge_transformed_page(page, Transformation().rotate(90).translate(h, 0))
            new.rotation = 90
        else:
            writer.add_page(page)
    with open(path, "wb") as fh:
        writer.write(fh)


def classify(img):
    """Find pages on one viewed side. Returns [(page, orientation, cx, cy)].

    orientation: 'up', 'down' (180°), 'mirror', 'sideways'
    """
    from scipy import ndimage

    a = img.astype(np.int32)
    found = []
    for idx, col in enumerate(PALETTE, start=1):
        m = (np.abs(a - col).sum(axis=2) < 45)
        lab, n = ndimage.label(m)
        if n == 0:
            continue
        sizes = np.array(ndimage.sum(m, lab, range(1, n + 1)))
        comps = [k + 1 for k in np.argsort(-sizes) if sizes[k] >= 8]
        big = sizes.max() if len(sizes) else 0
        bands = [k for k in comps if sizes[k - 1] >= max(150, 0.4 * big)]
        squares = [k for k in comps if k not in bands]
        # several copies of a page can share a side (e.g. uncollated n-up): pair each band with its nearest square
        for band in bands:
            ys, xs = np.nonzero(lab == band)
            cy, cx = ys.mean(), xs.mean()
            bw, bh = np.ptp(xs) + 1, np.ptp(ys) + 1
            if not squares:
                found.append((idx, "no-marker", cx, cy)); continue
            cents = [np.argwhere(lab == q).mean(axis=0) for q in squares]
            my, mx = min(cents, key=lambda c: (c[0] - cy) ** 2 + (c[1] - cx) ** 2)
            dx, dy = mx - cx, my - cy
            if bh > bw:
                o = "sideways"
            elif dy > 0 and dx < 0:
                o = "up"
            elif dy < 0 and dx > 0:
                o = "down"
            else:
                o = "mirror"
            found.append((idx, o, cx, cy))
    # reading order: rows (by y) then x
    found.sort(key=lambda f: f[3])
    rows, cur = [], []
    for f in found:
        if cur and abs(f[3] - cur[0][3]) > img.shape[0] * 0.2:
            rows.append(cur); cur = []
        cur.append(f)
    if cur:
        rows.append(cur)
    return [f for row in rows for f in sorted(row, key=lambda f: f[2])]
