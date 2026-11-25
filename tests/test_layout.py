"""Run with:  python -m pytest tests"""
import io
import sys
from pathlib import Path

import pytest
from fpdf import FPDF
from pypdf import PdfReader

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from canonprint import layout  # noqa: E402


def doc(n, landscape=()):
    pdf = FPDF(unit="pt", format="A4")
    for i in range(1, n + 1):
        pdf.add_page(orientation="L" if i in landscape else "P")
        pdf.set_font("Helvetica", size=40)
        pdf.text(100, 100, str(i))
    return PdfReader(io.BytesIO(bytes(pdf.output())))


def plan(reader, **kw):
    return layout.make_plan(reader, layout.normalize_settings(kw))


def test_page_range():
    assert layout.parse_page_range("", 4) == [0, 1, 2, 3]
    assert layout.parse_page_range("1-2, 4", 5) == [0, 1, 3]
    assert layout.parse_page_range("3-", 5) == [2, 3, 4]
    with pytest.raises(layout.LayoutError):
        layout.parse_page_range("9", 5)
    with pytest.raises(layout.LayoutError):
        layout.parse_page_range("abc", 5)


def test_two_per_side_one_sided():
    p = plan(doc(5), pages_per_side=2)
    assert [s.front.numbers for s in p.sheets] == [[1, 2], [3, 4], [5]]
    assert all(s.front.landscape for s in p.sheets)


def test_manual_duplex_long_edge_odd_pages():
    r = doc(5)
    p = plan(r, sides="long")
    assert [(s.front.numbers, s.back.numbers if s.back else None) for s in p.sheets] == [
        ([1], [2]), ([3], [4]), ([5], None)]
    js = layout.build_jobs(r, p)
    assert js.manual_duplex and js.remove_top_sheet
    assert [j.pages for j in js.jobs] == [3, 2]
    backs = PdfReader(io.BytesIO(js.jobs[1].pdf))
    assert "2" in backs.pages[0].extract_text()


def test_booklet_order():
    p = plan(doc(8), booklet=True)
    assert [(s.front.numbers, s.back.numbers) for s in p.sheets] == [([8, 1], [2, 7]), ([6, 3], [4, 5])]
    assert p.settings["sides"] == "short"


def test_copies_uncollated_and_auto_duplex():
    r = doc(3)
    p = plan(r, sides="long", duplex_method="auto", copies=2, collate=False)
    js = layout.build_jobs(r, p)
    assert len(js.jobs) == 1 and js.jobs[0].pages == 8 and js.jobs[0].sides == "long"


def test_files_start_on_new_sheet():
    r = doc(6)
    p = layout.make_plan(r, layout.normalize_settings({"sides": "long"}), file_starts=[3])
    assert [(s.front.numbers, s.back.numbers if s.back else None) for s in p.sheets] == [
        ([1], [2]), ([3], None), ([4], [5]), ([6], None)]


def test_physical_pages_are_portrait():
    r = doc(4, landscape=(2,))
    for kw in ({}, {"pages_per_side": 2}, {"booklet": True}):
        js = layout.build_jobs(r, plan(r, **kw))
        for job in js.jobs:
            for page in PdfReader(io.BytesIO(job.pdf)).pages:
                assert page.mediabox.width < page.mediabox.height


def test_odd_even_subset():
    p = plan(doc(6), page_subset="even")
    assert [s.front.numbers for s in p.sheets] == [[2], [4], [6]]
