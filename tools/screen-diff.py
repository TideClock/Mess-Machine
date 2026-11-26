#!/usr/bin/env python3
"""Compare the big test's screenshots with a reference set (the Compose Multiplatform move, docs/CMP-PLAN.md).

Usage: tools/screen-diff.py REF_DIR NEW_DIR OUT_DIR

For every PNG in REF_DIR, count the pixels in NEW_DIR's PNG of the same name whose colour moved by more than TOL
(any channel, 0 to 255). A screen fails when more than LIMIT of its pixels moved, or its size changed, or it is gone.
Screens only in NEW_DIR are listed, never failed, and so are the screens in tools/screen-diff-flaky.txt. Writes OUT_DIR/report.md and, for each changed screen, a picture
with the moved pixels in red over a faded copy of the new screen. Exit 1 when any screen failed.
TOL and LIMIT come from MM_DIFF_TOL (default 24) and MM_DIFF_LIMIT (default 0.001, a tenth of a percent).
"""
import os
import sys

import numpy as np
from PIL import Image


def load(p):
    return np.asarray(Image.open(p).convert("RGB"), dtype=np.int16)


def main():
    ref, new, out = sys.argv[1:4]
    tol = int(os.environ.get("MM_DIFF_TOL", "24"))
    limit = float(os.environ.get("MM_DIFF_LIMIT", "0.001"))
    os.makedirs(out, exist_ok=True)
    flaky = set()
    fl = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screen-diff-flaky.txt")
    if os.path.exists(fl):
        flaky = {l.split()[0] for l in open(fl) if l.strip() and not l.startswith("#")}
    refs = sorted(f for f in os.listdir(ref) if f.endswith(".png"))
    news = set(f for f in os.listdir(new) if f.endswith(".png"))
    rows, failed, same = [], 0, 0
    for f in refs:
        if f not in news:
            rows.append((f, "gone", "")); failed += 1; continue
        a, b = load(os.path.join(ref, f)), load(os.path.join(new, f))
        if a.shape != b.shape:
            rows.append((f, "size changed", f"{a.shape[1]}x{a.shape[0]} to {b.shape[1]}x{b.shape[0]}")); failed += 1
            continue
        moved = (np.abs(a - b).max(axis=2) > tol)
        frac = float(moved.mean())
        if frac == 0:
            same += 1; continue
        bad = frac > limit and f not in flaky
        failed += bad
        label = "CHANGED" if bad else ("varies run to run (screen-diff-flaky.txt)" if frac > limit else "noise")
        rows.append((f, label, f"{frac * 100:.3f}% of pixels"))
        pic = (b * 0.35 + 165).astype(np.uint8)
        pic[moved] = (230, 30, 30)
        Image.fromarray(pic).save(os.path.join(out, "diff-" + f))
    added = sorted(news - set(refs))
    lines = [f"# Screens against the reference: {len(refs)} compared, {same} identical, {failed} failed", "",
             f"Moved pixel: any channel off by more than {tol}. A screen fails over {limit * 100:g}% moved.", ""]
    if rows:
        lines += ["| Screen | Result | Detail |", "| --- | --- | --- |"] + [f"| {a} | {b} | {c} |" for a, b, c in rows]
    if added:
        lines += ["", "New screens (no reference yet): " + ", ".join(added)]
    text = "\n".join(lines) + "\n"
    open(os.path.join(out, "report.md"), "w").write(text)
    print(text)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
