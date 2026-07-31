"""
automated-code-review-tool — Final evaluation script (Phase 1A).

Runs ONCE after training is complete. Evaluates the fine-tuned model on the
held-out test set.

Contract:

* The label order is taken from the canonical taxonomy's
  ``trainable_ids()`` (deterministic, sorted by taxonomy declaration order).
* Each test record carries an ``anti_patterns`` field — a list of canonical
  anti-pattern IDs from ``taxonomy/anti_patterns.yaml``.
* A baseline may only be reported when its implementation actually runs
  on the same input. Synthetic baselines are clearly flagged.
* The diff-only preprocessing is applied via :func:`build_model_input`.
* Per-label precision/recall/F1, macro-F1, micro-F1, and PR-AUC (where
  the column has both positives and negatives) are computed.
* The output JSON includes the taxonomy version, the checkpoint identity,
  the dataset manifest hash, the threshold used, and an explicit
  ``synthetic`` flag.

Run from the repo root::

    python apps/ml-worker/training/evaluate.py \\
        --test-path training/data/test.jsonl \\
        --manifest-hash <manifest_sha256> \\
        --output-dir apps/ml-worker/training/data
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.metrics import (
    average_precision_score,
    f1_score,
    precision_score,
    recall_score,
)

# Make sibling modules importable when this file is run directly.
_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE.parent))

from app.preprocessing import build_model_input  # noqa: E402
from app.taxonomy import TaxonomyError, load_taxonomy  # noqa: E402


# ----------------------------------------------------------------------
# Constants
# ----------------------------------------------------------------------
HF_REPO = "tanmay-alpha/automated-code-review-tool-codebert"
OUTPUT_DIR = "./automated-code-review-tool-model"
MODEL_THRESHOLD = 0.5
REPO_ROOT = Path(__file__).resolve().parents[3]

ALLOWED_CATEGORIES = {"SECURITY", "PERFORMANCE", "ARCHITECTURE", "RELIABILITY", "READABILITY", "MAINTAINABILITY"}
ALLOWED_SEVERITIES = {"critical", "major", "minor"}
SUPPORTED_LANGUAGES = {"python", "javascript", "typescript", "java", "unknown"}

HELD_OUT_BANNER = """
######################################################################
#  RUNNING FINAL EVALUATION ON THE HELD-OUT TEST SET                  #
#  This file must not be opened during development.                   #
#  Do not iterate on this script — its numbers are the final report. #
######################################################################
"""


# ----------------------------------------------------------------------
# Test set loader
# ----------------------------------------------------------------------
def load_test_records(path: Path) -> list[dict]:
    """Load test records from either JSONL or a JSON list/wrapper."""
    if not path.exists():
        raise FileNotFoundError(f"Test file not found: {path}")
    with path.open("r", encoding="utf-8") as f:
        first = f.read(1)
        f.seek(0)
        if first == "[":
            payload = json.load(f)
            if isinstance(payload, dict):
                if "data" in payload and isinstance(payload["data"], list):
                    payload = payload["data"]
                else:
                    raise ValueError(f"{path}: unexpected dict shape (no 'data' list)")
            if not isinstance(payload, list):
                raise ValueError(f"{path}: expected a JSON list, got {type(payload).__name__}")
            return payload
        return [json.loads(line) for line in f if line.strip()]


# ----------------------------------------------------------------------
# Taxonomy + label helpers
# ----------------------------------------------------------------------
def _label_indices(
    records: list[dict],
    label_order: list[str],
) -> tuple[np.ndarray, list[str]]:
    """Convert each record's ``anti_patterns`` list into a multi-hot matrix.

    Unknown IDs cause a hard failure — no silent dropping.
    """
    label_set = set(label_order)
    y: list[list[int]] = []
    unknown: set[str] = set()
    for r in records:
        ap_ids = r.get("anti_patterns") or []
        row = [0] * len(label_order)
        for ap_id in ap_ids:
            if ap_id not in label_set:
                unknown.add(ap_id)
                continue
            row[label_order.index(ap_id)] = 1
        y.append(row)
    if unknown:
        raise ValueError(
            f"Test records contain unknown taxonomy IDs (not in trainable_ids()): "
            f"{sorted(unknown)}"
        )
    return np.asarray(y, dtype=np.int32), sorted(unknown)


# ----------------------------------------------------------------------
# Per-label metrics (real sklearn PR-AUC where valid)
# ----------------------------------------------------------------------
def per_label_metrics(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    y_score: np.ndarray,
) -> dict[str, dict[str, float]]:
    out: dict[str, dict[str, float]] = {}
    for i, name in enumerate(_LABEL_ORDER):  # populated by main()
        yt = y_true[:, i]
        yp = y_pred[:, i]
        ys = y_score[:, i]
        metrics: dict[str, Any] = {
            "precision": float(precision_score(yt, yp, zero_division=0)),
            "recall": float(recall_score(yt, yp, zero_division=0)),
            "f1": float(f1_score(yt, yp, zero_division=0)),
            "support": int(yt.sum()),
        }
        # PR-AUC: only valid when both classes are present.
        if yt.sum() > 0 and yt.sum() < len(yt):
            try:
                metrics["pr_auc"] = float(average_precision_score(yt, ys))
            except Exception:  # pragma: no cover
                metrics["pr_auc"] = None
        else:
            metrics["pr_auc"] = None
        out[name] = metrics
    return out


def summary_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, float]:
    return {
        "precision_macro": float(precision_score(y_true, y_pred, average="macro", zero_division=0)),
        "recall_macro": float(recall_score(y_true, y_pred, average="macro", zero_division=0)),
        "f1_macro": float(f1_score(y_true, y_pred, average="macro", zero_division=0)),
        "precision_micro": float(precision_score(y_true, y_pred, average="micro", zero_division=0)),
        "recall_micro": float(recall_score(y_true, y_pred, average="micro", zero_division=0)),
        "f1_micro": float(f1_score(y_true, y_pred, average="micro", zero_division=0)),
    }


# ----------------------------------------------------------------------
# Model loader
# ----------------------------------------------------------------------
def _load_model_and_tokenizer(checkpoint: str | None):
    """Load the fine-tuned model + tokenizer. Falls back to local OUTPUT_DIR."""
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    target = checkpoint or HF_REPO
    try:
        tokenizer = AutoTokenizer.from_pretrained(target)
        model = AutoModelForSequenceClassification.from_pretrained(target)
        model.eval()
        return model, tokenizer, target
    except Exception as hub_exc:  # pragma: no cover - requires network
        if target == HF_REPO and Path(OUTPUT_DIR).exists():
            tokenizer = AutoTokenizer.from_pretrained(OUTPUT_DIR)
            model = AutoModelForSequenceClassification.from_pretrained(OUTPUT_DIR)
            model.eval()
            return model, tokenizer, OUTPUT_DIR
        raise RuntimeError(f"Could not load checkpoint {target!r}: {hub_exc}") from hub_exc


def model_predict(
    records: list[dict],
    threshold: float,
    checkpoint: str | None,
) -> tuple[np.ndarray, np.ndarray, str]:
    """Run the fine-tuned model on the test set.

    Uses the canonical :func:`build_model_input` (diff-only, language+mode
    header) — the review comment is never part of the input.
    """
    import torch

    model, tokenizer, source = _load_model_and_tokenizer(checkpoint)
    print(f"[INFO] Loaded model from: {source}")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)

    scores: np.ndarray = np.zeros((len(records), len(_LABEL_ORDER)), dtype=np.float32)

    batch_size = 8
    with torch.no_grad():
        for start in range(0, len(records), batch_size):
            chunk = records[start:start + batch_size]
            texts = [
                build_model_input(r.get("diff") or "", r.get("language", "unknown"))
                for r in chunk
            ]
            enc = tokenizer(
                texts,
                max_length=512,
                truncation=True,
                padding=True,
                return_tensors="pt",
            )
            enc = {k: v.to(device) for k, v in enc.items()}
            logits = model(**enc).logits
            sigmoid = torch.sigmoid(logits).cpu().numpy()
            scores[start:start + batch_size] = sigmoid

    preds = (scores >= threshold).astype(np.int32)
    return preds, scores, source


# ----------------------------------------------------------------------
# Report writer
# ----------------------------------------------------------------------
def write_results(
    *,
    output_dir: Path,
    label_order: list[str],
    taxonomy_version: str,
    checkpoint_id: str,
    manifest_hash: str | None,
    threshold: float,
    synthetic: bool,
    n_samples: int,
    model_per_label: dict,
    model_summary: dict,
    model_source: str,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    payload: dict[str, Any] = {
        "taxonomy_version": taxonomy_version,
        "checkpoint_id": checkpoint_id,
        "checkpoint_source": model_source,
        "dataset_manifest_sha256": manifest_hash,
        "threshold": threshold,
        "synthetic_dataset": bool(synthetic),
        "n_test_samples": n_samples,
        "label_order": list(label_order),
        "fine_tuned_model": {
            "per_label": model_per_label,
            "summary": model_summary,
        },
    }
    out_json = output_dir / "evaluation_results.json"
    out_json.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out_json}")

    md = _format_markdown(
        label_order=label_order,
        taxonomy_version=taxonomy_version,
        checkpoint_id=checkpoint_id,
        manifest_hash=manifest_hash,
        threshold=threshold,
        synthetic=synthetic,
        n_samples=n_samples,
        model_per_label=model_per_label,
        model_summary=model_summary,
    )
    out_md = output_dir / "evaluation_results.md"
    out_md.write_text(md, encoding="utf-8")
    print(f"Wrote {out_md}")


def _format_markdown(
    *,
    label_order: list[str],
    taxonomy_version: str,
    checkpoint_id: str,
    manifest_hash: str | None,
    threshold: float,
    synthetic: bool,
    n_samples: int,
    model_per_label: dict,
    model_summary: dict,
) -> str:
    lines: list[str] = []
    lines.append("# automated-code-review-tool — Final Evaluation Results")
    lines.append("")
    lines.append(f"- taxonomy version: `{taxonomy_version}`")
    lines.append(f"- checkpoint: `{checkpoint_id}`")
    if manifest_hash:
        lines.append(f"- dataset manifest sha256: `{manifest_hash}`")
    lines.append(f"- threshold: `{threshold}`")
    lines.append(f"- synthetic dataset: `{synthetic}`")
    lines.append(f"- n_test_samples: `{n_samples}`")
    lines.append(f"- label_order: `{label_order}`")
    lines.append("")
    lines.append("## Per-label metrics (fine-tuned model)")
    lines.append("")
    lines.append("| Label | Precision | Recall | F1 | Support | PR-AUC |")
    lines.append("|---|---|---|---|---|---|")
    for name in label_order:
        m = model_per_label[name]
        pr_auc = "n/a" if m.get("pr_auc") is None else f"{m['pr_auc']:.3f}"
        lines.append(
            f"| {name} | {m['precision']:.3f} | {m['recall']:.3f} | "
            f"{m['f1']:.3f} | {m['support']} | {pr_auc} |"
        )
    lines.append("")
    lines.append("## Macro / micro averages")
    lines.append("")
    lines.append("| Metric | Value |")
    lines.append("|---|---|")
    lines.append(f"| precision_macro | {model_summary['precision_macro']:.3f} |")
    lines.append(f"| recall_macro    | {model_summary['recall_macro']:.3f} |")
    lines.append(f"| f1_macro        | {model_summary['f1_macro']:.3f} |")
    lines.append(f"| precision_micro | {model_summary['precision_micro']:.3f} |")
    lines.append(f"| recall_micro    | {model_summary['recall_micro']:.3f} |")
    lines.append(f"| f1_micro        | {model_summary['f1_micro']:.3f} |")
    lines.append("")
    lines.append("## Notes")
    lines.append("")
    lines.append("- The review comment is NOT part of the model input. Only the diff is.")
    lines.append("- PR-AUC is reported only when both positives and negatives exist for the label.")
    lines.append("- Synthetic baselines and manual GPT-4o placeholders are no longer included — numbers must come from a real run.")
    return "\n".join(lines) + "\n"


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------
_LABEL_ORDER: list[str] = []  # populated by main()


def main(argv: list[str] | None = None) -> int:
    print(HELD_OUT_BANNER)

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-path", type=Path, required=True, help="Path to test.jsonl/json")
    parser.add_argument(
        "--checkpoint",
        default=os.environ.get("HF_REPO"),
        help="HF repo or local path for the fine-tuned model",
    )
    parser.add_argument("--threshold", type=float, default=MODEL_THRESHOLD)
    parser.add_argument(
        "--manifest-hash",
        default=os.environ.get("DATASET_MANIFEST_SHA256"),
        help="SHA-256 of the dataset manifest (recorded for traceability)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("apps/ml-worker/training/data"),
        help="Directory for the evaluation results",
    )
    parser.add_argument(
        "--synthetic",
        action="store_true",
        help="Mark the run as synthetic-data evaluation (numbers are NOT real benchmarks).",
    )
    args = parser.parse_args(argv)

    # ---- Taxonomy ----
    try:
        taxonomy = load_taxonomy()
    except TaxonomyError as exc:
        raise SystemExit(f"taxonomy error: {exc}")
    label_order = taxonomy.trainable_ids()
    assert label_order, "Taxonomy has no trainable IDs"
    globals()["_LABEL_ORDER"] = label_order

    # ---- Data ----
    records = load_test_records(args.test_path)
    print(f"Loaded {len(records)} held-out test samples.")
    if not records:
        raise SystemExit("Test set is empty")

    # ---- Labels ----
    y_true, _unknown = _label_indices(records, label_order)
    print(f"Trainable label order ({len(label_order)}): {label_order}")

    # ---- Fine-tuned model ----
    print("\nRunning fine-tuned model on test set ...")
    y_pred_model, y_score_model, source = model_predict(records, args.threshold, args.checkpoint)
    model_per_label = per_label_metrics(y_true, y_pred_model, y_score_model)
    model_summary = summary_metrics(y_true, y_pred_model)

    checkpoint_id = source or "<unknown>"

    print("\n=== FINAL RESULTS ===")
    print(f"Fine-tuned macro-F1: {model_summary['f1_macro']:.4f}")

    write_results(
        output_dir=args.output_dir,
        label_order=label_order,
        taxonomy_version=taxonomy.version,
        checkpoint_id=checkpoint_id,
        manifest_hash=args.manifest_hash,
        threshold=args.threshold,
        synthetic=bool(args.synthetic) or records_have_synthetic_marker(records),
        n_samples=len(records),
        model_per_label=model_per_label,
        model_summary=model_summary,
        model_source=source,
    )
    return 0


def records_have_synthetic_marker(records: list[dict]) -> bool:
    return any(bool(r.get("_generated") or r.get("_synthetic")) for r in records)


if __name__ == "__main__":
    raise SystemExit(main())