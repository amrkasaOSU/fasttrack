#!/usr/bin/env python3
"""Print the JaCoCo coverage summary that RESULTS.md quotes.

Usage (from the repository root, after `make verify`):
    python3 scripts/coverage_summary.py
"""
import os
import sys
import xml.etree.ElementTree as ET

REPORT = os.path.join(os.path.dirname(__file__), "..", "backend", "target", "site", "jacoco", "jacoco.xml")


def main() -> int:
    if not os.path.exists(REPORT):
        print("No JaCoCo report found. Run `make verify` first.", file=sys.stderr)
        return 1

    root = ET.parse(REPORT).getroot()
    print(f"{'counter':12s} {'covered':>8s} {'total':>8s} {'ratio':>8s}")
    for counter in root.findall("counter"):
        covered = int(counter.get("covered"))
        missed = int(counter.get("missed"))
        total = covered + missed
        ratio = covered / total * 100 if total else 0.0
        print(f"{counter.get('type'):12s} {covered:8d} {total:8d} {ratio:7.1f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
