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
automated-code-review-tool — CodeBERT fine-tuning script (Issue #5).

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
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import f1_score
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
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
_TAXONOMY_PATH = _REPO_ROOT / "taxonomy" / "anti_patterns.yaml"

try:
    from app.taxonomy import load_taxonomy

    _TAXONOMY = load_taxonomy(_TAXONOMY_PATH)
    NUM_LABELS = len(_TAXONOMY.entries)
    LABEL_NAMES = [ap.id for ap in _TAXONOMY.entries]
except Exception:  # noqa: BLE001
    NUM_LABELS = 10
    LABEL_NAMES = [
        "SECURITY_HARDCODED_SECRET",
        "SECURITY_SQL_INJECTION",
        "SECURITY_WEAK_CRYPTO",
        "PERFORMANCE_N_PLUS_ONE",
        "PERFORMANCE_QUADRATIC_LOOP",
        "RELIABILITY_BROAD_EXCEPTION",
        "RELIABILITY_MISSING_TIMEOUT",
        "READABILITY_MAGIC_NUMBER",
        "READABILITY_LONG_METHOD",
        "MAINTAINABILITY_DUPLICATE_CODE",
    ]

MODEL_NAME = "microsoft/codebert-base"
MAX_SEQ_LENGTH = 512
LEARNING_RATE = 2e-5
BATCH_SIZE = 16
NUM_EPOCHS = 5
WEIGHT_DECAY = 0.01
WARMUP_RATIO = 0.1
THRESHOLD = 0.5
HF_REPO = "tanmay-alpha/automated-code-review-tool-codebert"
OUTPUT_DIR = "./automated-code-review-tool-model"
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
        return payload["data"]
    if isinstance(payload, list):
        return payload
    raise ValueError(f"Unexpected split format in {path}: type is {type(payload).__name__}")


# ----------------------------------------------------------------------
# Dataset definition
# ----------------------------------------------------------------------
class CodeReviewDataset(Dataset):
    """PyTorch Dataset for multi-label CodeBERT fine-tuning.

    Input to tokenizer: diff text ONLY.
    Target: float multi-hot tensor of shape (NUM_LABELS,).
    """

    def __init__(self, records: list[dict], tokenizer: AutoTokenizer, max_length: int = MAX_SEQ_LENGTH) -> None:
        self.records = records
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, idx: int) -> dict[str, torch.Tensor]:
        rec = self.records[idx]
        diff_text = rec.get("diff", "")

        enc = self.tokenizer(
            diff_text,
            truncation=True,
            max_length=self.max_length,
            padding="max_length",
            return_tensors="pt",
        )

        labels: np.ndarray = np.zeros(NUM_LABELS, dtype=np.float32)
        for label_name in rec.get("labels", []):
            if label_name in LABEL_NAMES:
                labels[LABEL_NAMES.index(label_name)] = 1.0

        item = {key: val.squeeze(0) for key, val in enc.items()}
        item["labels"] = torch.tensor(labels, dtype=torch.float32)
        return item


# ----------------------------------------------------------------------
# Metrics computation for HF Trainer
# ----------------------------------------------------------------------
def compute_metrics(eval_pred: tuple[np.ndarray, np.ndarray]) -> dict[str, float]:
    """Compute per-label F1 and macro F1 using THRESHOLD on sigmoid logits."""
    logits, labels = eval_pred
    probs = 1.0 / (1.0 + np.exp(-logits))
    preds = (probs >= THRESHOLD).astype(int)

    per_label_f1 = f1_score(labels, preds, average=None, zero_division=0)
    macro_f1 = f1_score(labels, preds, average="macro", zero_division=0)

    metrics = {"macro_f1": float(macro_f1)}
    for name, score in zip(LABEL_NAMES, per_label_f1):
        metrics[f"f1_{name}"] = float(score)

    return metrics


# ----------------------------------------------------------------------
# Custom Trainer to override loss to BCEWithLogitsLoss
# ----------------------------------------------------------------------
class MultilabelTrainer(Trainer):
    """Overrides compute_loss to use BCEWithLogitsLoss for multi-label."""

    def compute_loss(
        self,
        model: torch.nn.Module,
        inputs: dict[str, torch.Tensor],
        return_outputs: bool = False,
        **kwargs,
    ) -> torch.Tensor | tuple[torch.Tensor, torch.Tensor]:
        labels = inputs.pop("labels")
        outputs = model(**inputs)
        logits = outputs.logits
        loss_fct = torch.nn.BCEWithLogitsLoss()
        loss = loss_fct(logits, labels)

        return (loss, outputs) if return_outputs else loss


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------
def main() -> None:
    set_seed(SEED)

    train_path = Path(DATA_DIR) / TRAIN_FILE
    val_path = Path(DATA_DIR) / VAL_FILE

    if not train_path.exists() or not val_path.exists():
        print(
            f"Error: dataset files not found at {train_path} or {val_path}.\n"
            "Run apps/ml-worker/training/split.py first.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"Loading train split from {train_path}...")
    train_records = _load_split(train_path)
    print(f"Loaded {len(train_records)} train records.")

    print(f"Loading val split from {val_path}...")
    val_records = _load_split(val_path)
    print(f"Loaded {len(val_records)} val records.")

    print(f"Initializing tokenizer: {MODEL_NAME}...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)

    train_dataset = CodeReviewDataset(train_records, tokenizer)
    val_dataset = CodeReviewDataset(val_records, tokenizer)

    print(f"Initializing model: {MODEL_NAME} with {NUM_LABELS} binary heads...")
    model = AutoModelForSequenceClassification.from_pretrained(
        MODEL_NAME,
        num_labels=NUM_LABELS,
        problem_type="multi_label_classification",
        id2label={i: name for i, name in enumerate(LABEL_NAMES)},
        label2id={name: i for i, name in enumerate(LABEL_NAMES)},
    )

    training_args = TrainingArguments(
        output_dir=OUTPUT_DIR,
        eval_strategy="epoch",
        save_strategy="epoch",
        learning_rate=LEARNING_RATE,
        per_device_train_batch_size=BATCH_SIZE,
        per_device_eval_batch_size=BATCH_SIZE,
        num_train_epochs=NUM_EPOCHS,
        weight_decay=WEIGHT_DECAY,
        warmup_ratio=WARMUP_RATIO,
        load_best_model_at_end=True,
        metric_for_best_model="macro_f1",
        greater_is_better=True,
        logging_steps=50,
        save_total_limit=2,
        seed=SEED,
        report_to="none",
    )

    trainer = MultilabelTrainer(
        model=model,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=val_dataset,
        processing_class=tokenizer,
        compute_metrics=compute_metrics,
    )

    print("Starting training...")
    trainer.train()

    print("Evaluating best model on validation set...")
    eval_metrics = trainer.evaluate()
    print("Final validation metrics:")
    for k, v in eval_metrics.items():
        print(f"  {k}: {v:.4f}" if isinstance(v, float) else f"  {k}: {v}")

    print(f"Saving best model locally to {OUTPUT_DIR}...")
    trainer.save_model(OUTPUT_DIR)
    tokenizer.save_pretrained(OUTPUT_DIR)

    token = os.environ.get("HF_TOKEN")
    if token:
        print(f"Pushing model and tokenizer to HuggingFace Hub: {HF_REPO}...")
        model.push_to_hub(HF_REPO, token=token)
        tokenizer.push_to_hub(HF_REPO, token=token)
        print("Successfully pushed to HuggingFace Hub!")
    else:
        print("HF_TOKEN environment variable not set; skipping Hub push.")


if __name__ == "__main__":
    main()
