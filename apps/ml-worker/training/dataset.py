"""
CodeLens — Dataset loader and stats script (Issue #1).

Loads JSON files from apps/ml-worker/training/data/raw/, counts total samples,
prints 5 random examples (diff snippet + review comment), and prints basic stats.

Expected raw file format (from microsoft/CodeBERT CodeReviewer dataset):
  Each JSON file contains a list of objects with at least:
    - "diff":  unified diff string
    - "comment": review comment string

Run:
  python apps/ml-worker/training/dataset.py
"""
from __future__ import annotations

import json
import random
import sys
from pathlib import Path
from statistics import mean, median


# --- Paths ------------------------------------------------------------------
SCRIPT_DIR = Path(__file__).resolve().parent
RAW_DIR = SCRIPT_DIR / "data" / "raw"
NUM_RANDOM_EXAMPLES = 5
DIFF_SNIPPET_LINES = 5  # how many lines of diff to show per example
RANDOM_SEED = 42


def load_samples(raw_dir: Path) -> list[dict]:
    """Load every *.json file under raw_dir into a flat list of sample dicts."""
    if not raw_dir.exists():
        raise FileNotFoundError(
            f"Raw data directory does not exist: {raw_dir}\n"
            "Run scripts/download-dataset.sh first."
        )

    json_files = sorted(raw_dir.rglob("*.json"))
    if not json_files:
        raise FileNotFoundError(f"No .json files found under {raw_dir}")

    samples: list[dict] = []
    for fp in json_files:
        with fp.open("r", encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError as e:
                print(f"[WARN] Skipping {fp.name}: invalid JSON ({e})", file=sys.stderr)
                continue

        # Some CodeReviewer files wrap the list in {"data": [...]}; handle both.
        if isinstance(data, dict) and "data" in data and isinstance(data["data"], list):
            data = data["data"]
        if not isinstance(data, list):
            print(f"[WARN] Skipping {fp.name}: top-level is not a list", file=sys.stderr)
            continue

        samples.extend(data)

    return samples


def _truncate(text: str, max_lines: int) -> str:
    lines = text.splitlines()
    if len(lines) <= max_lines:
        return text
    return "\n".join(lines[:max_lines]) + f"\n... ({len(lines) - max_lines} more lines)"


def print_random_examples(samples: list[dict], k: int) -> None:
    rng = random.Random(RANDOM_SEED)
    sample_size = min(k, len(samples))
    chosen = rng.sample(samples, sample_size)

    print(f"\n=== {sample_size} RANDOM EXAMPLES ===")
    for i, s in enumerate(chosen, 1):
        diff = s.get("diff", "") or ""
        comment = s.get("comment", "") or ""
        label = s.get("label", "n/a")

        print(f"\n--- Example {i} ---")
        print(f"label: {label}")
        print(f"comment ({len(comment)} chars): {comment}")
        print(f"diff ({len(diff)} chars, first {DIFF_SNIPPET_LINES} lines):")
        print(_truncate(diff, DIFF_SNIPPET_LINES))


def print_stats(samples: list[dict]) -> None:
    total = len(samples)

    comment_lengths: list[int] = [len(s.get("comment", "") or "") for s in samples]
    diff_lengths: list[int] = [len(s.get("diff", "") or "") for s in samples]

    empty_comments = sum(1 for c in comment_lengths if c == 0)
    empty_diffs = sum(1 for d in diff_lengths if d == 0)

    # Label distribution (the dataset uses 0/1 to mean non-actionable/actionable)
    label_counts: dict = {}
    for s in samples:
        lbl = s.get("label", None)
        label_counts[lbl] = label_counts.get(lbl, 0) + 1

    print("\n=== DATASET STATS ===")
    print(f"Total samples:                 {total}")
    print(f"Files loaded:                  (see raw/ directory)")
    print(f"Avg comment length (chars):    {mean(comment_lengths):.1f}")
    print(f"Median comment length (chars): {median(comment_lengths):.1f}")
    print(f"Avg diff length (chars):       {mean(diff_lengths):.1f}")
    print(f"Median diff length (chars):    {median(diff_lengths):.1f}")
    print(f"Empty comments:                {empty_comments}")
    print(f"Empty diffs:                   {empty_diffs}")
    print(f"Label distribution:            {dict(sorted(label_counts.items(), key=lambda x: str(x[0])))}")


def main() -> int:
    print(f"Loading samples from: {RAW_DIR}")
    samples = load_samples(RAW_DIR)
    print(f"Loaded {len(samples)} samples.")

    print_stats(samples)
    print_random_examples(samples, NUM_RANDOM_EXAMPLES)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
