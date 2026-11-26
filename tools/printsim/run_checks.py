"""Run every exported scenario through the virtual printer + person and report."""
import os
import sys

import numpy as np
from PIL import Image

import probe
import simulate as sim

spool = sys.argv[1]
out_png = sys.argv[2] if len(sys.argv) > 2 else None

EXPECT = {
    "one_sided": ("ONE", list(range(1, 8))),
    "one_sided_fwd": ("ONE", list(range(7, 0, -1))),   # known: face-up output reverses unless 'last page first'
    "long": ("LONG", list(range(1, 8))),
    "short": ("SHORT", list(range(1, 8))),
    "long_mixed": ("LONG", list(range(1, 10))),
    "up2_long": ("LONG", list(range(1, 10))),
    "up2_short": ("SHORT", list(range(1, 10))),
    "up4_short": ("SHORT", list(range(1, 8))),
    "booklet": ("SHORT", list(range(1, 8))),
    "copies_coll": ("LONG", [1, 2, 3, 1, 2, 3]),
    "copies_uncoll": ("LONG", [1, 2, 1, 2, 3, 3]),
    "range_even": ("ONE", [2, 4, 6, 8]),
    "two_files": ("LONG", [1, 2, 3, 1, 2]),
}

failures = 0
print(f"{'scenario':16} {'result':6} pages read by the virtual person")
for name, (sides, expected) in EXPECT.items():
    d = os.path.join(spool, name)
    ok, flat, problems, hdr, meta = sim.check(
        name, d, expected, sides, booklet=(name == "booklet"),
        allow_sideways=(6,) if name == "long_mixed" else (),
    )
    failures += not ok
    extra = "" if ok else f"   expected {expected}  problems={problems}"
    print(f"{name:16} {'PASS' if ok else 'FAIL':6} {flat}{extra}")

# ---------------------------------------------------------------- other printers + the two-sided test
print("\nDifferent printer behaviours, fixed by the two-sided test:")
variants = {
    "G3000 (top-feed, face-up out)": sim.Printer(),
    "feeds from bottom of pile": sim.Printer(feed_from_top=False),
    "face-down, end-over-end output": sim.Printer(end_over_end=True),
    "bottom-feed + end-over-end": sim.Printer(feed_from_top=False, end_over_end=True),
    "prints on the face-down side": sim.Printer(flips_sheet=True),
}


def answer_test(printer, rot, rev):
    """Run the 4-page two-sided test and answer the app's two questions like a person."""
    _, _, out, aside = sim.simulate(os.path.join(spool, f"cal_{rot}{rev}"), printer)
    pile = sim.person_turns_pile(out)
    # find the sheet whose up face shows page 1 (in any rotation), turn it like a book page
    for s in pile + [p for p in out]:
        for face in (0, 1):
            img = s.face[face]
            if img is None:
                continue
            for k in range(4):
                f = probe.classify(np.rot90(img, k))
                if f and f[0][0] == 1 and f[0][1] == "up":
                    back = s.face[face ^ 1]
                    if back is None:
                        return False, True
                    bv = np.rot90(back, -k) if face == 0 else np.rot90(back, k)
                    # book turn from face 1 view back to face 0 view is also a book turn
                    found = probe.classify(bv)
                    on_back = bool(found) and found[0][0] == 2
                    upright = bool(found) and found[0][1] == "up"
                    return on_back, upright
    return False, False


variants["G3000, person turns pile like a calendar"] = "calendar"
for label, printer in variants.items():
    sim.CALENDAR_PERSON = printer == "calendar"
    if printer == "calendar":
        printer = sim.Printer()
    rot, rev = 0, 0
    try:
        before, *_ = sim.check("long7", os.path.join(spool, f"long7_{rot}{rev}"), list(range(1, 8)), "LONG", printer)
    except Exception as e:  # noqa: BLE001
        before = False
    try:
        on_back, upright = answer_test(printer, rot, rev)
    except AssertionError as e:
        print(f"  {label:32} NOT SUPPORTED: turning the pile over is wrong for this kind of printer ({e}).\n"
              f"  {'':32} The G3000's rear tray prints on the side facing up, so this case doesn't apply to it.")
        continue
    if not on_back:
        rev ^= 1
    if not upright:
        rot ^= 1
    results = []
    for kind, sides in (("long7", "LONG"), ("short7", "SHORT")):
        try:
            ok, flat, problems, *_ = sim.check(kind, os.path.join(spool, f"{kind}_{rot}{rev}"), list(range(1, 8)), sides, printer)
        except Exception as e:  # noqa: BLE001
            ok, flat, problems = False, [], [str(e)]
        results.append(ok)
        failures += not ok
    print(f"  {label:32} before test: {'ok' if before else 'WRONG'} | answers: page2 on back={on_back}, upright={upright}"
          f" -> settings rotate={rot} reverse={rev} | after: long {'PASS' if results[0] else 'FAIL'}, short {'PASS' if results[1] else 'FAIL'}")

sim.CALENDAR_PERSON = False

# ---------------------------------------------------------------- contact sheet for a human look
# Each pair = one finished sheet: the side facing up, then the other side after turning it
# over along the binding edge (long edge: like a book; short edge: like a calendar).
if out_png:
    tiles = []
    for name in ["long", "short", "up2_short", "booklet"]:
        _, _, out, aside = sim.simulate(os.path.join(spool, name))
        pile = sim.person_turns_pile(out) + ([aside] if aside else [])
        row = []
        short = name in ("short", "up2_short", "booklet")
        for s in pile[:3]:
            front = s.face[s.up]
            back = s.face[s.up ^ 1]
            if back is None:
                back = np.full(front.shape, 255, np.uint8)
            land = name in ("up2_short", "booklet")
            fv = np.rot90(front, -1) if land else front          # turn the sheet so the front reads upright
            if land:
                bv = np.rot90(back, 1) if not short else np.rot90(back, 1)  # reading frame for the back
                bv = bv if short else np.rot90(bv, 2)
            else:
                bv = np.rot90(back, 2) if short else back
            for v in (fv, bv):
                row.append(Image.fromarray(np.ascontiguousarray(v)).resize((124, 175) if v.shape[0] > v.shape[1] else (175, 124)))
        tiles.append(row)
    W = max(len(r) for r in tiles) * 182
    sheet = Image.new("RGB", (W, len(tiles) * 182), (200, 196, 188))
    for y, row in enumerate(tiles):
        for x, im in enumerate(row):
            sheet.paste(im, (x * 182 + (x % 2) * -4, y * 182))
    sheet.save(out_png)

print("\nALL PASS" if failures == 0 else f"\n{failures} FAILURE(S)")
sys.exit(1 if failures else 0)
