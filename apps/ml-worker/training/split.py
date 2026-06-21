"""
CodeLens — PR-level train/val/test split script (Issue #4).

Splits the labeled CodeReviewer dataset into train/val/test by PR ID
(NOT by individual sample) to prevent the same PR leaking into both
train and evaluation sets.

The CodeReviewer raw files are organized by commit hash, where each
hash corresponds to one PR. We use that hash as the PR ID when
available. If a sample cannot be traced back to a commit hash, we
fall back to the source filename as a coarse PR-group key.

Outputs:
    training/data/train.json
    training/data/val.json
    training/data/test.json   (with a top-level _warning field — see below)

Run from the repo root:
    python apps/ml-worker/training/split.py

NOTE on test.json:
    The plan requires test.json to be committed to the repo but never
    opened until final evaluation. We add a top-level "_warning" key
    to make this impossible to miss; any downstream loader must ignore
    that field.
"""
from __future__ import annotations

import json
import random
import sys
from collections import defaultdict
from pathlib import Path

# Local import — label_mapper.py lives next to this file.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from label_mapper import CATEGORIES  # noqa: E402

# `filter_and_label_dataset` is also imported for convenience, but the
# split is performed on the raw (pre-filter) data with PR provenance
# preserved, so we re-load the raw files directly. See _load_with_pr_keys.

RANDOM_SEED = 42
TRAIN_RATIO = 0.80
VAL_RATIO = 0.10
# TEST_RATIO = 0.10 (implicit, the remainder)
TEST_WARNING = (
    "DO NOT EVALUATE AGAINST THIS FILE UNTIL FINAL EVALUATION. "
    "This is the held-out test set. It exists in the repo so the team "
    "can verify it is real, but it must not be opened, read, sampled, "
    "or used for any model-selection decisions."
)

REPO_ROOT = Path(__file__).resolve().parents[3]
RAW_DIR = REPO_ROOT / "apps" / "ml-worker" / "training" / "data" / "raw"
DATA_DIR = REPO_ROOT / "apps" / "ml-worker" / "training" / "data"

TRAIN_OUT = DATA_DIR / "train.json"
VAL_OUT = DATA_DIR / "val.json"
TEST_OUT = DATA_DIR / "test.json"

DIFF_SNIPPET_LINES = 5  # for any console logging


# --- PR-keyed loading -------------------------------------------------------
def _load_with_pr_keys(raw_dir: Path) -> list[tuple[str, dict]]:
    """
    Return list of (pr_id, sample_dict) tuples.

    CodeReviewer raw format (per file) is typically:
        {
            "<commit_hash>": [
                {"diff": ..., "comment": ..., "label": ...},
                ...
            ],
            "<commit_hash_2>": [...],
            ...
        }
    Some files may be a flat list; in that case the file basename is
    used as the PR-group key (coarser, but still group-level).
    """
    if not raw_dir.exists():
        raise FileNotFoundError(
            f"Raw data directory does not exist: {raw_dir}\n"
            "Run scripts/download-dataset.sh first."
        )

    json_files = sorted(raw_dir.rglob("*.json"))
    if not json_files:
        raise FileNotFoundError(f"No .json files found under {raw_dir}")

    out: list[tuple[str, dict]] = []
    for fp in json_files:
        try:
            with fp.open("r", encoding="utf-8") as f:
                data = json.load(f)
        except json.JSONDecodeError as e:
            print(f"[WARN] Skipping {fp.name}: invalid JSON ({e})", file=sys.stderr)
            continue

        if isinstance(data, dict):
            # CodeReviewer dict-of-commits shape.
            for commit_hash, comments in data.items():
                if not isinstance(comments, list):
                    continue
                for sample in comments:
                    if isinstance(sample, dict):
                        out.append((str(commit_hash), sample))
        elif isinstance(data, list):
            # Flat list — group by file.
            for sample in data:
                if isinstance(sample, dict):
                    out.append((fp.stem, sample))
        else:
            print(
                f"[WARN] Skipping {fp.name}: unrecognized top-level type",
                file=sys.stderr,
            )
    return out


# --- Grouping + splitting ---------------------------------------------------
def _group_by_pr(rows: list[tuple[str, dict]]) -> dict[str, list[dict]]:
    grouped: dict[str, list[dict]] = defaultdict(list)
    for pr_id, sample in rows:
        grouped[pr_id].append(sample)
    return dict(grouped)


def _split_pr_ids(pr_ids: list[str]) -> tuple[list[str], list[str], list[str]]:
    rng = random.Random(RANDOM_SEED)
    ids = list(pr_ids)
    rng.shuffle(ids)
    n = len(ids)
    n_train = int(n * TRAIN_RATIO)
    n_val = int(n * VAL_RATIO)
    train_ids = ids[:n_train]
    val_ids = ids[n_train:n_train + n_val]
    test_ids = ids[n_train + n_val:]
    return train_ids, val_ids, test_ids


# --- Output -----------------------------------------------------------------
def _attach_labels(samples: list[dict]) -> list[dict]:
    """Convert raw samples to {diff, comment, labels} using the label_mapper."""
    # Late import to avoid a cycle on module load.
    from label_mapper import map_comment_to_labels
    out: list[dict] = []
    for s in samples:
        comment = (s.get("comment") or "").strip()
        diff = s.get("diff") or ""
        if not comment or len(comment) < 20:
            continue
        labels = map_comment_to_labels(comment)
        if sum(labels) == 0:
            continue
        out.append({"diff": diff, "comment": comment, "labels": labels})
    return out


def _write_json(path: Path, payload, warning: str | None = None) -> None:
    if warning is not None:
        # Wrap so the warning lives at the top of the file as a top-level key.
        path.write_text(
            json.dumps({"_warning": warning, "data": payload}, indent=2) + "\n",
            encoding="utf-8",
        )
    else:
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


# --- Stats ------------------------------------------------------------------
def _per_category_counts(records: list[dict]) -> dict[str, int]:
    counts = {c: 0 for c in CATEGORIES}
    for r in records:
        for i, v in enumerate(r["labels"]):
            if v:
                counts[CATEGORIES[i]] += 1
    return counts


def _print_split_stats(name: str, records: list[dict]) -> None:
    print(f"\n--- {name} ---")
    print(f"  samples: {len(records)}")
    counts = _per_category_counts(records)
    if records:
        for cat in CATEGORIES:
            c = counts[cat]
            pct = 100.0 * c / len(records)
            print(f"  {cat:<16} {c:>6}  ({pct:5.1f}%)")


def _check_no_overlap(*splits: list[dict]) -> bool:
    """Each record is {diff, comment, labels, _pr_id} after _attach_pr_id."""
    seen: dict[str, str] = {}
    ok = True
    for split_name, records in splits:
        for r in records:
            pr_id = r.get("_pr_id")
            if pr_id is None:
                continue
            if pr_id in seen and seen[pr_id] != split_name:
                print(
                    f"  [LEAK] PR {pr_id!r} appears in BOTH {seen[pr_id]} and {split_name}",
                    file=sys.stderr,
                )
                ok = False
            seen[pr_id] = split_name
    return ok


# --- Main -------------------------------------------------------------------
def main() -> int:
    print(f"Loading raw data with PR provenance from: {RAW_DIR}")
    rows = _load_with_pr_keys(RAW_DIR)
    if not rows:
        print("No samples found. Run scripts/download-dataset.sh first.")
        return 1
    print(f"Loaded {len(rows)} raw samples (with PR IDs).")

    # Group by PR and split at PR level.
    grouped = _group_by_pr(rows)
    pr_ids = sorted(grouped.keys())
    print(f"Unique PR IDs: {len(pr_ids)}")
    train_ids, val_ids, test_ids = _split_pr_ids(pr_ids)

    def _flatten(pr_id_list: list[str], split_name: str) -> list[dict]:
        flat: list[dict] = []
        for pid in pr_id_list:
            for s in grouped[pid]:
                flat.append({"_pr_id": pid, "_split": split_name, **s})
        return flat

    raw_train = _flatten(train_ids, "train")
    raw_val = _flatten(val_ids, "val")
    raw_test = _flatten(test_ids, "test")

    # Filter + label per split.
    train_records = _attach_labels(raw_train)
    val_records = _attach_labels(raw_val)
    test_records = _attach_labels(raw_test)

    # Leak check on PR IDs (must be disjoint at the PR level).
    print("\n--- Overlap check (PR-level) ---")
    if _check_no_overlap(
        ("train", raw_train),
        ("val", raw_val),
        ("test", raw_test),
    ):
        print("  OK — no PR ID appears in more than one split.")
    else:
        print("  WARNING: PR overlap detected. Investigate before training.", file=sys.stderr)
        return 2

    # Write outputs.
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    _write_json(TRAIN_OUT, train_records, warning=None)
    _write_json(VAL_OUT, val_records, warning=None)
    _write_json(TEST_OUT, test_records, warning=TEST_WARNING)
    print(f"\nWrote {TRAIN_OUT}  ({len(train_records)} samples)")
    print(f"Wrote {VAL_OUT}    ({len(val_records)} samples)")
    print(f"Wrote {TEST_OUT}   ({len(test_records)} samples) — held out")

    # Stats.
    _print_split_stats("train", train_records)
    _print_split_stats("val", val_records)
    _print_split_stats("test", test_records)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
