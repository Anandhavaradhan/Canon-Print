"""Turn Canon IVEC job files (as received by fake_g3000.py) into a scenario folder for simulate.py."""
import json
import os
import re
import sys


def pwg_pages(job_bytes):
    pages, p = [], 0
    while p < len(job_bytes):
        e = job_bytes.index(b"</cmd>", p) + 6
        m = re.search(rb"<ivec:datasize>(\d+)</ivec:datasize>", job_bytes[p:e])
        p = e
        if m:
            n = int(m.group(1)); pages.append(job_bytes[p:p + n]); p += n
    return pages


def to_scenario(job_files, out_dir, meta):
    os.makedirs(out_dir, exist_ok=True)
    for i, jf in enumerate(job_files):
        pages = pwg_pages(open(jf, "rb").read())
        with open(os.path.join(out_dir, f"pass{i}.pwg"), "wb") as fh:
            for k, pg in enumerate(pages):
                fh.write(pg if k == 0 else pg[4:])  # one RaS2 sync for the whole stream
    json.dump({"passes": len(job_files), **meta}, open(os.path.join(out_dir, "meta.json"), "w"))


if __name__ == "__main__":
    out, meta = sys.argv[1], json.loads(sys.argv[2])
    to_scenario(sys.argv[3:], out, meta)
