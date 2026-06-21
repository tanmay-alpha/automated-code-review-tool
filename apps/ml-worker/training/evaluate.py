"""
CodeLens — Final evaluation script (Issue #6).

Runs ONCE after training is complete. Evaluates the fine-tuned model
on the held-out test set and compares against the keyword baseline
(from label_mapper.py). A placeholder row for the GPT-4o baseline is
included in the markdown report and must be filled in manually.

Run from the repo root:
    python apps/ml-worker/training/evaluate.py
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

import numpy as np
import torch
from sklearn.metrics import f1_score, precision_score, recall_score

# Local import — label_mapper.py lives next to this file.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from label_mapper import CATEGORIES, map_comment_to_labels  # noqa: E402


# ----------------------------------------------------------------------
# Constants — mirror train.py so this script is self-contained.
# ----------------------------------------------------------------------
MODEL_NAME = "microsoft/codebert-base"
NUM_LABELS = 6
LABEL_NAMES = CATEGORIES  # fixed order: SECURITY, PERFORMANCE, ARCHITECTURE, RELIABILITY, READABILITY, MAINTAINABILITY
MAX_SEQ_LENGTH = 512
THRESHOLD = 0.5
HF_REPO = "tanmay-alpha/codelens-codebert"
OUTPUT_DIR = "./codelens-model"
DATA_DIR = "./training/data"
TEST_FILE = "test.json"
REPO_ROOT = Path(__file__).resolve().parents[3]

# Sigmoid threshold for the model (must match train.py).
MODEL_THRESHOLD = 0.5

# Outputs (written next to split artifacts).
RESULTS_JSON = REPO_ROOT / "apps" / "ml-worker" / "training" / "data" / "evaluation_results.json"
RESULTS_MD = REPO_ROOT / "apps" / "ml-worker" / "training" / "data" / "evaluation_results.md"


# ----------------------------------------------------------------------
# Held-out test warning
# ----------------------------------------------------------------------
HELD_OUT_BANNER = """
######################################################################
#  RUNNING FINAL EVALUATION ON THE HELD-OUT TEST SET                  #
#  This file must not be opened during development.                   #
#  Do not iterate on this script — its numbers are the final report. #
######################################################################
"""


# ----------------------------------------------------------------------
# Load test.json
# ----------------------------------------------------------------------
def load_test_set(data_dir: Path) -> list[dict]:
    path = data_dir / TEST_FILE
    if not path.exists():
        raise FileNotFoundError(
            f"Test file not found: {path}\n"
            "Run apps/ml-worker/training/split.py first."
        )
    with path.open("r", encoding="utf-8") as f:
        payload = json.load(f)

    # split.py wraps test.json as {"_warning": "...", "data": [...]}.
    if isinstance(payload, dict):
        if "data" in payload and isinstance(payload["data"], list):
            payload = payload["data"]
        else:
            raise ValueError(f"{path}: unexpected dict shape (no 'data' list)")
    if not isinstance(payload, list):
        raise ValueError(f"{path}: expected a JSON list, got {type(payload).__name__}")
    return payload


# ----------------------------------------------------------------------
# Metrics
# ----------------------------------------------------------------------
def per_label_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, dict[str, float]]:
    out: dict[str, dict[str, float]] = {}
    for i, name in enumerate(LABEL_NAMES):
        out[name] = {
            "precision": float(precision_score(y_true[:, i], y_pred[:, i], zero_division=0)),
            "recall": float(recall_score(y_true[:, i], y_pred[:, i], zero_division=0)),
            "f1": float(f1_score(y_true[:, i], y_pred[:, i], zero_division=0)),
            "support": int(y_true[:, i].sum()),
        }
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
# Keyword baseline
# ----------------------------------------------------------------------
def keyword_baseline(records: list[dict]) -> np.ndarray:
    preds = np.zeros((len(records), NUM_LABELS), dtype=np.int32)
    for i, r in enumerate(records):
        comment = r.get("comment") or ""
        preds[i] = np.array(map_comment_to_labels(comment), dtype=np.int32)
    return preds


# ----------------------------------------------------------------------
# Fine-tuned model
# ----------------------------------------------------------------------
def _load_model_and_tokenizer():
    """
    Try HuggingFace Hub first; fall back to local OUTPUT_DIR.
    Returns (model, tokenizer, source_label).
    """
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    # Try HF hub first.
    if os.environ.get("HF_TOKEN") or True:  # try hub regardless; will use cached auth
        try:
            tokenizer = AutoTokenizer.from_pretrained(HF_REPO)
            model = AutoModelForSequenceClassification.from_pretrained(HF_REPO)
            model.eval()
            return model, tokenizer, HF_REPO
        except Exception as e:
            print(f"[INFO] Could not load {HF_REPO} from Hub ({e}); trying {OUTPUT_DIR} ...")

    # Fall back to local OUTPUT_DIR.
    tokenizer = AutoTokenizer.from_pretrained(OUTPUT_DIR)
    model = AutoModelForSequenceClassification.from_pretrained(OUTPUT_DIR)
    model.eval()
    return model, tokenizer, OUTPUT_DIR


def model_predict(records: list[dict]) -> np.ndarray:
    model, tokenizer, source = _load_model_and_tokenizer()
    print(f"[INFO] Loaded model from: {source}")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)

    preds = np.zeros((len(records), NUM_LABELS), dtype=np.int32)

    batch_size = 8
    with torch.no_grad():
        for start in range(0, len(records), batch_size):
            chunk = records[start:start + batch_size]
            texts = [(r.get("diff") or "") + "\n" + (r.get("comment") or "") for r in chunk]
            enc = tokenizer(
                texts,
                max_length=MAX_SEQ_LENGTH,
                truncation=True,
                padding=True,
                return_tensors="pt",
            )
            enc = {k: v.to(device) for k, v in enc.items()}
            logits = model(**enc).logits
            sigmoid = torch.sigmoid(logits).cpu().numpy()
            preds[start:start + batch_size] = (sigmoid >= MODEL_THRESHOLD).astype(np.int32)
    return preds


# ----------------------------------------------------------------------
# Report writers
# ----------------------------------------------------------------------
def _format_md_table(model_per_label: dict, model_summary: dict,
                     kw_per_label: dict, kw_summary: dict) -> str:
    """Return a markdown comparison table.

    Columns: Model | Per-label P/R/F1 | Macro-F1
    Rows:     fine-tuned, keyword, gpt-4o (placeholder).
    """
    lines: list[str] = []
    lines.append("# CodeLens — Final Evaluation Results")
    lines.append("")
    lines.append("Source: `apps/ml-worker/training/evaluate.py` on the held-out test set.")
    lines.append("")
    lines.append("## Per-label comparison (fine-tuned model vs keyword baseline)")
    lines.append("")
    lines.append("| Category | Model P | Model R | Model F1 | Keyword P | Keyword R | Keyword F1 |")
    lines.append("|---|---|---|---|---|---|---|")
    for name in LABEL_NAMES:
        m = model_per_label[name]
        k = kw_per_label[name]
        lines.append(
            f"| {name} "
            f"| {m['precision']:.3f} | {m['recall']:.3f} | {m['f1']:.3f} "
            f"| {k['precision']:.3f} | {k['recall']:.3f} | {k['f1']:.3f} |"
        )
    lines.append("")
    lines.append("## Macro-averaged metrics")
    lines.append("")
    lines.append("| System | Macro P | Macro R | Macro F1 | Micro F1 |")
    lines.append("|---|---|---|---|---|")
    lines.append(
        f"| Fine-tuned CodeBERT | {model_summary['precision_macro']:.3f} "
        f"| {model_summary['recall_macro']:.3f} | {model_summary['f1_macro']:.3f} "
        f"| {model_summary['f1_micro']:.3f} |"
    )
    lines.append(
        f"| Keyword baseline | {kw_summary['precision_macro']:.3f} "
        f"| {kw_summary['recall_macro']:.3f} | {kw_summary['f1_macro']:.3f} "
        f"| {kw_summary['f1_micro']:.3f} |"
    )
    lines.append("| GPT-4o zero-shot | _(fill in manually)_ | _(fill in manually)_ | _(fill in manually)_ | _(fill in manually)_ |")
    lines.append("")
    lines.append("## Notes")
    lines.append("")
    lines.append("- GPT-4o baseline row is a placeholder. Run GPT-4o zero-shot on a 200-sample subset of this test set manually and fill in the values.")
    lines.append("- Acceptance per the plan: fine-tuned model macro-F1 must beat both the keyword baseline and the GPT-4o baseline before shipping.")
    lines.append("")
    return "\n".join(lines)


def write_results(
    model_per_label: dict,
    model_summary: dict,
    kw_per_label: dict,
    kw_summary: dict,
    n_samples: int,
    model_source: str,
) -> None:
    payload = {
        "n_test_samples": n_samples,
        "model_source": model_source,
        "fine_tuned_model": {
            "per_label": model_per_label,
            "summary": model_summary,
        },
        "keyword_baseline": {
            "per_label": kw_per_label,
            "summary": kw_summary,
        },
        "gpt4o_baseline": {
            "note": "Fill in manually — run GPT-4o zero-shot on a 200-sample subset and add the metrics here.",
        },
    }
    RESULTS_JSON.parent.mkdir(parents=True, exist_ok=True)
    RESULTS_JSON.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {RESULTS_JSON}")

    md = _format_md_table(model_per_label, model_summary, kw_per_label, kw_summary)
    RESULTS_MD.write_text(md, encoding="utf-8")
    print(f"Wrote {RESULTS_MD}")


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------
def main() -> int:
    print(HELD_OUT_BANNER)

    data_dir = Path(DATA_DIR)
    if not data_dir.is_absolute():
        data_dir = REPO_ROOT / "apps" / "ml-worker" / "training" / "data"
    test_records = load_test_set(data_dir)
    print(f"Loaded {len(test_records)} held-out test samples.")

    y_true = np.array([r["labels"] for r in test_records], dtype=np.int32)

    # ---- Fine-tuned model ----
    print("\n[1/2] Running fine-tuned model on test set ...")
    y_pred_model = model_predict(test_records)
    model_per_label = per_label_metrics(y_true, y_pred_model)
    model_summary = summary_metrics(y_true, y_pred_model)

    # ---- Keyword baseline ----
    print("\n[2/2] Running keyword baseline on test set ...")
    y_pred_kw = keyword_baseline(test_records)
    kw_per_label = per_label_metrics(y_true, y_pred_kw)
    kw_summary = summary_metrics(y_true, y_pred_kw)

    # ---- Console summary ----
    print("\n=== FINAL RESULTS ===")
    print(f"Fine-tuned macro-F1: {model_summary['f1_macro']:.4f}")
    print(f"Keyword     macro-F1: {kw_summary['f1_macro']:.4f}")
    if model_summary["f1_macro"] > kw_summary["f1_macro"]:
        print("✅ Fine-tuned model beats keyword baseline.")
    else:
        print("⚠️  Fine-tuned model does NOT beat keyword baseline. Investigate.", file=sys.stderr)

    # ---- Write reports ----
    model_source = HF_REPO  # may have been overridden to local OUTPUT_DIR inside model_predict
    write_results(
        model_per_label=model_per_label,
        model_summary=model_summary,
        kw_per_label=kw_per_label,
        kw_summary=kw_summary,
        n_samples=len(test_records),
        model_source=model_source,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
