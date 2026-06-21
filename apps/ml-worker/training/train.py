# Run this on Google Colab with T4 GPU. Install requirements-train.txt first.
#
# !pip install -r requirements-train.txt
# from google.colab import drive
# drive.mount('/content/drive')
#
# Then either:
#   - copy the repo to Colab and run this script from the repo root, or
#   - run this from a Colab cell whose CWD is the repo root.
#
# Colab Pro T4 fits BATCH_SIZE=16 with MAX_SEQ_LENGTH=512.

"""
CodeLens — CodeBERT fine-tuning script (Issue #5).

Trains microsoft/codebert-base on the multi-label CodeReviewer dataset
produced by split.py, with 6 binary heads (one per category).

Loss: BCEWithLogitsLoss (one per label, applied independently).
Optimizer: AdamW (HF Trainer default) with linear warmup.
Best model: selected by val macro-F1.
Push: best model + tokenizer are pushed to HF_REPO at the end.
"""
from __future__ import annotations

import json
import os
import random
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
from sklearn.metrics import f1_score, precision_score, recall_score
from torch.utils.data import Dataset
from transformers import (
    AutoModelForSequenceClassification,
    AutoTokenizer,
    Trainer,
    TrainingArguments,
)


# ----------------------------------------------------------------------
# Constants — change here, never buried in code (per plan spec).
# ----------------------------------------------------------------------
MODEL_NAME = "microsoft/codebert-base"
NUM_LABELS = 6
LABEL_NAMES = [
    "SECURITY",
    "PERFORMANCE",
    "ARCHITECTURE",
    "RELIABILITY",
    "READABILITY",
    "MAINTAINABILITY",
]
MAX_SEQ_LENGTH = 512
LEARNING_RATE = 2e-5
BATCH_SIZE = 16
NUM_EPOCHS = 5
WEIGHT_DECAY = 0.01
WARMUP_RATIO = 0.1
THRESHOLD = 0.5
HF_REPO = "tanmay-alpha/codelens-codebert"
OUTPUT_DIR = "./codelens-model"
DATA_DIR = "./training/data"
TRAIN_FILE = "train.json"
VAL_FILE = "val.json"
SEED = 42


# ----------------------------------------------------------------------
# Utilities
# ----------------------------------------------------------------------
def set_seed(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def _load_split(path: Path) -> list[dict]:
    """Load a train/val JSON file.

    The file may be either a flat list of records or a wrapper of the
    shape {"data": [...]} (as written by split.py for test.json; train
    and val are written as flat lists but we accept both shapes).
    """
    with path.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    if isinstance(payload, dict) and "data" in payload and isinstance(payload["data"], list):
        payload = payload["data"]
    if not isinstance(payload, list):
        raise ValueError(f"{path}: expected a JSON list, got {type(payload).__name__}")
    return payload


# ----------------------------------------------------------------------
# Dataset
# ----------------------------------------------------------------------
@dataclass
class TokenizedRecord:
    input_ids: list[int]
    attention_mask: list[int]
    labels: list[float]


class CodeReviewDataset(Dataset):
    """Torch Dataset wrapping pre-tokenized CodeReviewer samples."""

    def __init__(self, records: list[dict], tokenizer, max_length: int = MAX_SEQ_LENGTH):
        self.records = records
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, idx: int) -> dict[str, torch.Tensor]:
        rec = self.records[idx]
        # Use diff + comment as the model input — diff carries the code
        # context, comment carries the review signal. Separator mimics
        # CodeBERT's pretraining format.
        text = (rec.get("diff") or "") + "\n" + (rec.get("comment") or "")
        encoded = self.tokenizer(
            text,
            max_length=self.max_length,
            truncation=True,
            padding="max_length",
            return_tensors=None,  # return lists; collator will batch
        )
        labels = [float(x) for x in rec["labels"]]
        return {
            "input_ids": torch.tensor(encoded["input_ids"], dtype=torch.long),
            "attention_mask": torch.tensor(encoded["attention_mask"], dtype=torch.long),
            "labels": torch.tensor(labels, dtype=torch.float),
        }


# ----------------------------------------------------------------------
# Metrics
# ----------------------------------------------------------------------
def compute_metrics(eval_pred) -> dict[str, float]:
    """
    Compute per-label precision/recall/F1 plus macro-F1.

    Trainer calls this with `eval_pred` being an EvalPrediction
    (predictions, label_ids). Predictions are raw logits; we apply
    sigmoid + threshold.
    """
    logits, labels = eval_pred
    if isinstance(logits, tuple):
        logits = logits[0]
    sigmoid = 1.0 / (1.0 + np.exp(-logits))
    preds = (sigmoid >= THRESHOLD).astype(np.int32)
    labels = labels.astype(np.int32)

    macro_f1 = float(
        f1_score(labels, preds, average="macro", zero_division=0)
    )
    per_label = {
        f"f1_{name}": float(
            f1_score(labels[:, i], preds[:, i], zero_division=0)
        )
        for i, name in enumerate(LABEL_NAMES)
    }
    return {"f1_macro": macro_f1, **per_label}


# ----------------------------------------------------------------------
# Custom Trainer — explicit BCEWithLogitsLoss (per plan spec)
# ----------------------------------------------------------------------
class MultiLabelTrainer(Trainer):
    """Trainer that computes BCEWithLogitsLoss explicitly.

    The HF model already uses BCEWithLogitsLoss when
    problem_type="multi_label_classification" is set, but per the plan
    we make the loss function visible in code (not buried in HF defaults)
    so a reviewer can confirm the choice at a glance.
    """

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        labels = inputs.pop("labels")
        outputs = model(**inputs)
        logits = outputs.logits
        loss_fct = torch.nn.BCEWithLogitsLoss()
        loss = loss_fct(logits, labels)
        return (loss, outputs) if return_outputs else loss


# ----------------------------------------------------------------------
# Data loading
# ----------------------------------------------------------------------
def load_datasets(tokenizer) -> tuple[CodeReviewDataset, CodeReviewDataset]:
    data_dir = Path(DATA_DIR)
    train_path = data_dir / TRAIN_FILE
    val_path = data_dir / VAL_FILE
    if not train_path.exists():
        raise FileNotFoundError(
            f"Train file not found: {train_path}\n"
            "Run apps/ml-worker/training/split.py first."
        )
    if not val_path.exists():
        raise FileNotFoundError(
            f"Val file not found: {val_path}\n"
            "Run apps/ml-worker/training/split.py first."
        )
    train_records = _load_split(train_path)
    val_records = _load_split(val_path)
    print(f"Loaded {len(train_records)} train, {len(val_records)} val records.")
    return (
        CodeReviewDataset(train_records, tokenizer),
        CodeReviewDataset(val_records, tokenizer),
    )


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------
def main() -> int:
    set_seed(SEED)

    # ---- Tokenizer ----
    print(f"Loading tokenizer from {MODEL_NAME} ...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)

    # ---- Data ----
    train_ds, val_ds = load_datasets(tokenizer)

    # ---- Model ----
    print(f"Loading model {MODEL_NAME} with num_labels={NUM_LABELS} "
          f"problem_type='multi_label_classification' ...")
    model = AutoModelForSequenceClassification.from_pretrained(
        MODEL_NAME,
        num_labels=NUM_LABELS,
        problem_type="multi_label_classification",
    )

    # ---- TrainingArguments ----
    args = TrainingArguments(
        output_dir=OUTPUT_DIR,
        num_train_epochs=NUM_EPOCHS,
        per_device_train_batch_size=BATCH_SIZE,
        per_device_eval_batch_size=BATCH_SIZE,
        learning_rate=LEARNING_RATE,
        weight_decay=WEIGHT_DECAY,
        warmup_ratio=WARMUP_RATIO,
        evaluation_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="f1_macro",
        greater_is_better=True,
        logging_strategy="epoch",
        report_to=[],  # no W&B / TensorBoard by default; enable manually
        seed=SEED,
        fp16=torch.cuda.is_available(),  # speed up on T4
    )

    # ---- Trainer ----
    trainer = MultiLabelTrainer(
        model=model,
        args=args,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        tokenizer=tokenizer,
        compute_metrics=compute_metrics,
    )

    # ---- Train ----
    print("Starting training ...")
    train_result = trainer.train()
    trainer.save_model(OUTPUT_DIR)
    tokenizer.save_pretrained(OUTPUT_DIR)
    print("Training complete. Best checkpoint saved to", OUTPUT_DIR)

    # ---- Final eval on val (best model) ----
    print("\nRunning final evaluation on val (best model) ...")
    eval_metrics = trainer.evaluate()
    val_macro_f1 = float(eval_metrics.get("eval_f1_macro", 0.0))
    print(f"\nFinal val macro-F1: {val_macro_f1:.4f}")

    # ---- Push to HuggingFace Hub ----
    hf_token = os.environ.get("HF_TOKEN")
    if hf_token:
        print(f"\nPushing best model + tokenizer to {HF_REPO} ...")
        try:
            trainer.model.push_to_hub(
                HF_REPO, use_auth_token=hf_token, commit_message="CodeLens fine-tuned CodeBERT (Issue #5)"
            )
            tokenizer.push_to_hub(
                HF_REPO, use_auth_token=hf_token, commit_message="CodeLens tokenizer"
            )
            print(f"Pushed to https://huggingface.co/{HF_REPO}")
        except Exception as e:
            print(f"[WARN] push_to_hub failed: {e}", file=sys.stderr)
    else:
        print(
            "\n[INFO] HF_TOKEN not set — skipping push to HuggingFace Hub.\n"
            f"       Best model is saved locally at: {OUTPUT_DIR}\n"
            f"       Set HF_TOKEN and re-run, or call trainer.model.push_to_hub('{HF_REPO}') manually."
        )

    # ---- Summary metrics file ----
    summary = {
        "model_name": MODEL_NAME,
        "hf_repo": HF_REPO,
        "output_dir": OUTPUT_DIR,
        "num_train_epochs": NUM_EPOCHS,
        "batch_size": BATCH_SIZE,
        "learning_rate": LEARNING_RATE,
        "val_macro_f1": val_macro_f1,
        "val_metrics": {k: float(v) for k, v in eval_metrics.items()},
        "train_metrics": {
            "train_runtime": float(getattr(train_result, "train_runtime", 0.0)),
            "train_loss": float(getattr(train_result, "training_loss", 0.0) or 0.0),
        },
    }
    summary_path = Path(OUTPUT_DIR) / "training_summary.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
