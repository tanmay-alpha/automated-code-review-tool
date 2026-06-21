"""
CodeLens — Taxonomy label mapper (Issue #2).

Maps a code-review comment to a 6-dimensional multi-label binary vector
using keyword matching against the locked taxonomy in
ENGINEERING_PLAN.md (Section 6, Step 3).

Categories (fixed order in the label vector):
    0: SECURITY
    1: PERFORMANCE
    2: ARCHITECTURE
    3: RELIABILITY
    4: READABILITY
    5: MAINTAINABILITY

Also exposes filter_and_label_dataset() which:
    - loads every *.json under a raw data directory
    - drops samples with comment length < 20 chars
    - drops pure style-nits
    - drops samples that match zero categories
    - returns list[dict] of {diff, comment, labels}

Run from the repo root:
    python apps/ml-worker/training/label_mapper.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from statistics import mean
from typing import Iterable


# ----------------------------------------------------------------------
# Taxonomy
# ----------------------------------------------------------------------
# Order is fixed. The index of each category in CATEGORIES is the index
# in the label vector.
# ----------------------------------------------------------------------
CATEGORIES: list[str] = [
    "SECURITY",
    "PERFORMANCE",
    "ARCHITECTURE",
    "RELIABILITY",
    "READABILITY",
    "MAINTAINABILITY",
]

KEYWORD_MAP: dict[str, list[str]] = {
    "SECURITY": [
        "password", "secret", "api_key", "token", "credential",
        "sql injection", "eval(", "hardcoded", "plaintext",
        "private key", "auth", "encrypt", "hash", "vulnerability",
        "injection", "xss", "csrf",
        # NOTE: "eval(" here is a literal substring used to match the
        # text "eval(" appearing inside source code comments (a common
        # review signal). It is NEVER executed — Python's `in` operator
        # does plain string matching, not evaluation.
    ],
    "PERFORMANCE": [
        "n+1", "loop", "query inside", "redundant", "unnecessary iteration",
        "nested loop", "repeated call", "O(n", "slow", "bottleneck",
        "cache", "lazy load", "eager load", "index", "optimize",
    ],
    "ARCHITECTURE": [
        "god class", "too many responsibilities", "single responsibility",
        "circular", "feature envy", "coupling", "cohesion", "dependency",
        "separation", "abstraction", "interface", "module", "service",
        "layer",
    ],
    "RELIABILITY": [
        "bare except", "except:", "null check", "none check", "not handled",
        "resource leak", "unclosed", "exception", "error handling",
        "finally", "rollback", "timeout", "retry", "validation",
    ],
    "READABILITY": [
        "magic number", "unclear", "misleading", "confusing name",
        "abbreviation", "comment", "document", "naming", "readable",
        "self-explanatory",
    ],
    "MAINTAINABILITY": [
        "too long", "deep nesting", "duplicate", "copy-paste",
        "dead code", "unused", "complex", "refactor", "hardcoded",
        "configuration", "constant",
    ],
}

# Words that, on their own, indicate a pure style nit. A comment is
# treated as a pure style-nit iff (a) its total length is below
# MAX_STYLE_NIT_LENGTH AND (b) every alphabetic token is in this set.
STYLE_NIT_WORDS: set[str] = {
    "nit", "typo", "rename", "spacing", "whitespace", "format",
    "indent", "semicolon",
}
MAX_STYLE_NIT_LENGTH: int = 80

# Threshold for "comment length < 20 chars" filter (Issue #2 spec).
MIN_COMMENT_LENGTH: int = 20


# ----------------------------------------------------------------------
# Tokenization helpers
# ----------------------------------------------------------------------
_TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*")


def _tokens(text: str) -> set[str]:
    """Lowercased set of word-like tokens in `text`."""
    return {t.lower() for t in _TOKEN_RE.findall(text)}


def _is_pure_style_nit(comment: str) -> bool:
    """True iff the comment is essentially a style nit (no substance)."""
    if len(comment) >= MAX_STYLE_NIT_LENGTH:
        return False
    toks = _tokens(comment)
    if not toks:
        return False
    return toks.issubset(STYLE_NIT_WORDS)


# ----------------------------------------------------------------------
# Public API
# ----------------------------------------------------------------------
def map_comment_to_labels(comment: str) -> list[int]:
    """
    Map a review comment to a 6-dim binary vector.

    Each position corresponds to CATEGORIES[i]. A position is 1 if any
    keyword for that category appears in the comment (case-insensitive),
    else 0. Returns all-zeros if no category matches.
    """
    if not isinstance(comment, str):
        return [0] * len(CATEGORIES)
    text = comment.lower()
    return [
        1 if any(kw.lower() in text for kw in KEYWORD_MAP[cat]) else 0
        for cat in CATEGORIES
    ]


def _load_raw_samples(raw_data_dir: str | Path) -> list[dict]:
    """Load every *.json under raw_data_dir, flattening lists of samples."""
    raw_path = Path(raw_data_dir)
    if not raw_path.exists():
        raise FileNotFoundError(
            f"Raw data directory does not exist: {raw_path}\n"
            "Run scripts/download-dataset.sh first."
        )
    json_files = sorted(raw_path.rglob("*.json"))
    if not json_files:
        raise FileNotFoundError(f"No .json files found under {raw_path}")

    samples: list[dict] = []
    for fp in json_files:
        try:
            with fp.open("r", encoding="utf-8") as f:
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


def filter_and_label_dataset(raw_data_dir: str) -> list[dict]:
    """
    Load raw data, filter, label, and return list[dict] of:
        {"diff": str, "comment": str, "labels": list[int]}

    Drops:
        - samples with comment length < 20 chars
        - pure style-nits (entire comment is short and only uses style words)
        - samples whose comment matches zero categories (all-zero labels)
    """
    raw_samples = _load_raw_samples(raw_data_dir)

    filtered: list[dict] = []
    drop_short = 0
    drop_nit = 0
    drop_unlabeled = 0

    for s in raw_samples:
        comment = (s.get("comment") or "").strip()
        diff = s.get("diff") or ""

        if len(comment) < MIN_COMMENT_LENGTH:
            drop_short += 1
            continue
        if _is_pure_style_nit(comment):
            drop_nit += 1
            continue

        labels = map_comment_to_labels(comment)
        if sum(labels) == 0:
            drop_unlabeled += 1
            continue

        filtered.append({"diff": diff, "comment": comment, "labels": labels})

    # Stats attached for the CLI; harmless if not consumed.
    filtered_stats = {
        "raw": len(raw_samples),
        "dropped_short_comment": drop_short,
        "dropped_style_nit": drop_nit,
        "dropped_unlabeled": drop_unlabeled,
        "kept": len(filtered),
    }
    # Stash on the returned list object (idiomatic; not typed).
    setattr(filtered, "stats", filtered_stats)  # type: ignore[attr-defined]
    return filtered


# ----------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------
def _per_category_counts(records: Iterable[dict]) -> list[int]:
    counts = [0] * len(CATEGORIES)
    for r in records:
        for i, v in enumerate(r["labels"]):
            if v:
                counts[i] += 1
    return counts


def _print_random_examples(records: list[dict], k: int) -> None:
    import random
    rng = random.Random(42)
    if not records:
        print("\n(no records survived filtering — nothing to show)")
        return
    sample = rng.sample(records, min(k, len(records)))
    print(f"\n=== {len(sample)} EXAMPLE LABELED SAMPLES ===")
    for i, r in enumerate(sample, 1):
        active = [CATEGORIES[j] for j, v in enumerate(r["labels"]) if v]
        print(f"\n--- Example {i} ---")
        print(f"labels: {r['labels']}  ({active or 'NONE'})")
        print(f"comment: {r['comment']}")
        diff = r["diff"]
        snippet = "\n".join(diff.splitlines()[:5])
        if len(diff.splitlines()) > 5:
            snippet += f"\n... ({len(diff.splitlines()) - 5} more lines)"
        print(f"diff (first 5 lines):\n{snippet}")


def main() -> int:
    repo_root = Path(__file__).resolve().parents[3]  # apps/ml-worker/training -> repo root
    raw_dir = repo_root / "apps" / "ml-worker" / "training" / "data" / "raw"
    print(f"Loading + filtering from: {raw_dir}")

    labeled = filter_and_label_dataset(str(raw_dir))
    stats = getattr(labeled, "stats", {})

    raw = stats.get("raw", 0)
    kept = len(labeled)
    print("\n=== FILTERING STATS ===")
    print(f"Total raw samples loaded:        {raw}")
    print(f"  - dropped (comment < 20):      {stats.get('dropped_short_comment', 0)}")
    print(f"  - dropped (style nit):         {stats.get('dropped_style_nit', 0)}")
    print(f"  - dropped (unlabeled):         {stats.get('dropped_unlabeled', 0)}")
    print(f"Total samples after filtering:   {kept}")

    print("\n=== PER-CATEGORY COUNTS ===")
    counts = _per_category_counts(labeled)
    if kept > 0:
        for cat, c in zip(CATEGORIES, counts):
            pct = 100.0 * c / kept
            print(f"  {cat:<16} {c:>7}  ({pct:5.1f}%)")
        avg_labels = mean(sum(r["labels"]) for r in labeled)
        print(f"\nAvg labels per sample: {avg_labels:.2f}")
    else:
        print("  (no labeled samples — nothing to count)")

    _print_random_examples(labeled, k=3)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
