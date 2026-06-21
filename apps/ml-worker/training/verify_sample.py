"""
CodeLens — Interactive sample verification script (Issue #3).

Loads labeled samples via label_mapper.filter_and_label_dataset(),
draws 500 random items (seeded), and walks the user through them one
at a time, asking them to confirm or reject the assigned labels.

On quit or completion, writes:
    training/data/verification_report.json
    training/data/verification_report.md

Run from the repo root:
    python apps/ml-worker/training/verify_sample.py
"""
from __future__ import annotations

import json
import random
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

# Local import — label_mapper.py lives next to this file.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from label_mapper import (  # noqa: E402
    CATEGORIES,
    filter_and_label_dataset,
    map_comment_to_labels,
)


# --- Config -----------------------------------------------------------------
TARGET_SAMPLE_SIZE = 500
RANDOM_SEED = 42
DIFF_SNIPPET_LINES = 8
REPO_ROOT = Path(__file__).resolve().parents[3]   # .../codelens
RAW_DIR = REPO_ROOT / "apps" / "ml-worker" / "training" / "data" / "raw"
REPORT_DIR = REPO_ROOT / "apps" / "ml-worker" / "training" / "data"
REPORT_JSON = REPORT_DIR / "verification_report.json"
REPORT_MD = REPORT_DIR / "verification_report.md"


# --- Helpers ----------------------------------------------------------------
def _label_names(labels: list[int]) -> list[str]:
    return [CATEGORIES[i] for i, v in enumerate(labels) if v]


def _truncate(text: str, max_lines: int) -> str:
    lines = text.splitlines()
    if len(lines) <= max_lines:
        return text
    return "\n".join(lines[:max_lines]) + f"\n... ({len(lines) - max_lines} more lines)"


def _prompt() -> str:
    """Read a single-char response, lowercase. Returns 'q' on EOF."""
    try:
        return input("Labels correct? [y/n/s to skip/q to quit]: ").strip().lower()
    except EOFError:
        return "q"


# --- Main verify loop -------------------------------------------------------
def verify(samples: list[dict]) -> dict:
    """
    Walk through `samples`, collect approve/reject/skip decisions.
    Returns a stats dict (also written to disk by the caller).
    """
    approved = 0
    rejected = 0
    skipped = 0
    rejected_category_counter: Counter[str] = Counter()
    total = len(samples)

    print(f"\nVerifying {total} samples. y = approve, n = reject, s = skip, q = quit.\n")

    for idx, sample in enumerate(samples, 1):
        comment = sample.get("comment", "")
        diff = sample.get("diff", "")
        labels = sample.get("labels") or map_comment_to_labels(comment)
        active = _label_names(labels) or ["(none)"]

        print("=" * 72)
        print(f"Sample {idx} of {total}")
        print(f"Labels assigned: {active}")
        print(f"Labels vector:   {labels}")
        print("-" * 72)
        print(f"Comment:\n{comment}")
        print("-" * 72)
        print(f"Diff (first {DIFF_SNIPPET_LINES} lines):")
        print(_truncate(diff, DIFF_SNIPPET_LINES))
        print("=" * 72)

        while True:
            choice = _prompt()
            if choice in ("y", "n", "s", "q"):
                break
            print("Please enter y, n, s, or q.")

        if choice == "y":
            approved += 1
        elif choice == "n":
            rejected += 1
            for cat in active:
                if cat != "(none)":
                    rejected_category_counter[cat] += 1
        elif choice == "s":
            skipped += 1
        else:  # q
            print("\nQuitting early. Saving partial report...")
            break

    judged = approved + rejected
    agreement_rate = (approved / judged * 100.0) if judged else 0.0

    return {
        "total_checked": approved + rejected + skipped,
        "approved": approved,
        "rejected": rejected,
        "skipped": skipped,
        "agreement_rate": f"{agreement_rate:.1f}%",
        "agreement_rate_value": agreement_rate,
        "common_rejections": [],   # per spec: empty list in JSON
        "rejected_category_counts": dict(rejected_category_counter),
        "quitted_early": choice == "q" if samples else False,
        "completed_at": datetime.now(timezone.utc).isoformat(),
    }


# --- Report writers ---------------------------------------------------------
def write_reports(stats: dict) -> None:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)

    # --- JSON (exact spec shape, common_rejections is a list) ---
    json_payload = {
        "total_checked": stats["total_checked"],
        "approved": stats["approved"],
        "rejected": stats["rejected"],
        "skipped": stats["skipped"],
        "agreement_rate": stats["agreement_rate"],
        "common_rejections": stats["common_rejections"],
    }
    REPORT_JSON.write_text(json.dumps(json_payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {REPORT_JSON}")

    # --- Markdown (human-readable summary) ---
    cat_counts: Counter[str] = Counter(stats["rejected_category_counts"])
    most_wrong = cat_counts.most_common(3)
    rate_value = stats["agreement_rate_value"]
    proceed = "PROCEED" if rate_value >= 90.0 else "REVISE KEYWORDS"

    md_lines: list[str] = [
        "# Verification Report",
        "",
        f"_Generated: {stats['completed_at']}_",
        "",
        "## Summary",
        "",
        f"- Total checked: **{stats['total_checked']}**",
        f"- Approved: **{stats['approved']}**",
        f"- Rejected: **{stats['rejected']}**",
        f"- Skipped: **{stats['skipped']}**",
        f"- Agreement rate: **{stats['agreement_rate']}**",
        "",
    ]
    if stats.get("quitted_early"):
        md_lines.append("_Quit early — partial results._\n")

    md_lines += [
        "## Most commonly wrong categories",
        "",
    ]
    if most_wrong:
        for cat, n in most_wrong:
            md_lines.append(f"- **{cat}**: {n} rejection(s)")
    else:
        md_lines.append("- _(none — no rejections recorded)_")

    md_lines += [
        "",
        "## Recommendation",
        "",
        f"**{proceed}**",
        "",
    ]
    if proceed == "REVISE KEYWORDS":
        md_lines += [
            "Agreement is below the 90% target. Suggested actions:",
            "1. Review the rejection list above for systematic misses.",
            "2. Update `KEYWORD_MAP` in `label_mapper.py`.",
            "3. Re-run `verify_sample.py` to confirm improvements.",
        ]
    else:
        md_lines += [
            "Agreement meets the 90% target. The taxonomy is ready for use.",
            "Proceed to Issue #4 (PR-level train/val/test split).",
        ]

    md_lines.append("")
    REPORT_MD.write_text("\n".join(md_lines), encoding="utf-8")
    print(f"Wrote {REPORT_MD}")


# --- Entry point ------------------------------------------------------------
def main() -> int:
    print(f"Loading + labeling samples from: {RAW_DIR}")
    labeled = filter_and_label_dataset(str(RAW_DIR))
    raw_stats = getattr(labeled, "stats", {})
    print(
        f"Loaded {raw_stats.get('raw', '?')} raw, "
        f"kept {len(labeled)} after filtering."
    )

    if not labeled:
        print("No labeled samples available — nothing to verify.")
        print("Run scripts/download-dataset.sh first.")
        return 1

    random.seed(RANDOM_SEED)
    n = min(TARGET_SAMPLE_SIZE, len(labeled))
    sample = random.sample(labeled, n)
    if n < TARGET_SAMPLE_SIZE:
        print(
            f"WARNING: only {len(labeled)} labeled samples available, "
            f"verifying {n} instead of {TARGET_SAMPLE_SIZE}."
        )

    stats = verify(sample)
    write_reports(stats)

    # Console summary
    print("\n=== VERIFICATION COMPLETE ===")
    print(f"  Approved:  {stats['approved']}")
    print(f"  Rejected:  {stats['rejected']}")
    print(f"  Skipped:   {stats['skipped']}")
    print(f"  Agreement: {stats['agreement_rate']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
