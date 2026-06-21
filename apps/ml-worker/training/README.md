# CodeLens — Training Pipeline

> Step-by-step guide to: download the dataset, label it, verify the labels, split by PR, fine-tune CodeBERT on Colab, and evaluate on the held-out test set.
>
> Source plan: [ENGINEERING_PLAN.md.md](../../../ENGINEERING_PLAN.md.md) (Sections 6, 7).
> Issues: #1 → #6 (Milestone 1 of the 30-day plan).

---

## Overview

The pipeline is six scripts, run in this order:

```
download-dataset.sh → dataset.py
                    ↓
              label_mapper.py
                    ↓
              verify_sample.py   (interactive, 10–15 min)
                    ↓
              split.py
                    ↓
              train.py           (Google Colab, T4 GPU)
                    ↓
              evaluate.py        (held-out test, run ONCE)
```

Every script is run from the **repo root** with `python apps/ml-worker/training/<script>.py`, except `download-dataset.sh` which is run as `bash scripts/download-dataset.sh`.

---

## 1. Download the dataset (Issue #1)

```bash
bash scripts/download-dataset.sh
```

This clones `microsoft/CodeBERT` shallow into `/tmp/codebert`, copies the `CodeReviewer/code-review-data/` folder into `apps/ml-worker/training/data/raw/`, and cleans up. Expected output:

```
Dataset downloaded successfully
Location: <repo>/apps/ml-worker/training/data/raw
```

**Sanity check:**

```bash
python apps/ml-worker/training/dataset.py
```

Should print `Loaded N samples`, total + median counts, and 5 random examples. Save the output as `dataset_stats.md` for the Issue #1 acceptance record.

---

## 2. Label the dataset (Issue #2)

```bash
python apps/ml-worker/training/label_mapper.py
```

Loads every JSON under `data/raw/`, drops:
- comments shorter than 20 chars,
- pure style-nits (short comments made entirely of words like "nit", "typo", "rename"),
- zero-label samples,

then prints per-category counts and 3 random examples. Use this output to sanity-check that the keyword taxonomy is producing reasonable distributions.

---

## 3. Manually verify 500 samples (Issue #3)

```bash
python apps/ml-worker/training/verify_sample.py
```

You will be walked through 500 random labeled samples. For each:

```
Sample 47 of 500
Labels assigned: ['PERFORMANCE']
Labels vector:   [0, 1, 0, 0, 0, 0]
Comment:
This is a classic N+1 — move the query outside the loop.
Diff (first 8 lines):
...
Labels correct? [y/n/s to skip/q to quit]:
```

- **y** = approve
- **n** = reject (counted toward "most commonly wrong categories")
- **s** = skip (excluded from agreement rate)
- **q** = quit early; partial report is still saved

When you finish (or quit), `training/data/verification_report.json` and `verification_report.md` are written. **Acceptance:** agreement rate ≥ 90%. If it's lower, revise `KEYWORD_MAP` in `label_mapper.py` and re-run.

---

## 4. Split by PR ID (Issue #4)

```bash
python apps/ml-worker/training/split.py
```

Groups samples by **PR ID** (commit hash from the CodeReviewer file when available, else file basename). Shuffles with seed 42, splits 80/10/10 at the PR level. Outputs:

```
apps/ml-worker/training/data/
├── train.json     (80% of PRs)
├── val.json       (10% of PRs)
└── test.json      (10% of PRs — held out, _warning field at top)
```

**Critical rule:** `test.json` is the held-out set. Do not open, sample, or evaluate against it until you are ready to ship. It is committed to the repo so the team can verify it exists, but the top-level `_warning` field is the contract: ignore it.

---

## 5. Fine-tune CodeBERT (Issue #5) — Google Colab

This step requires a GPU. Use Google Colab Pro (T4 instance, ~$10/month or included with Pro).

### 5.1 Open a new Colab notebook

Runtime → Change runtime type → **T4 GPU**.

### 5.2 Clone the repo and install training deps

```python
!git clone https://github.com/tanmay-alpha/codelens.git
%cd codelens
!pip install -r apps/ml-worker/requirements-train.txt
```

### 5.3 (Optional) Mount Drive

If you want to persist the trained model across Colab sessions:

```python
from google.colab import drive
drive.mount('/content/drive')
```

### 5.4 Copy your data into the repo

If you ran steps 1–4 locally, upload `apps/ml-worker/training/data/train.json` and `val.json` to Colab (the actual files — do NOT upload `test.json`). Place them under `codelens/apps/ml-worker/training/data/`.

### 5.5 Set the HuggingFace token (for pushing the model)

```python
import os
os.environ["HF_TOKEN"] = "<your-hf-token>"
```

Get a token from https://huggingface.co/settings/tokens. The token must have write access to the target repo (`tanmay-alpha/codelens-codebert`).

### 5.6 Run training

```python
!python apps/ml-worker/training/train.py
```

This will:
1. Load `train.json` and `val.json`.
2. Tokenize with the CodeBERT tokenizer (`max_length=512`, `truncation=True`, `padding="max_length"`).
3. Fine-tune `microsoft/codebert-base` for 5 epochs, batch size 16, lr 2e-5.
4. Use `BCEWithLogitsLoss` (explicit in code) since this is multi-label classification.
5. Evaluate at every epoch and keep the best checkpoint by **val macro-F1**.
6. Push the best checkpoint to `tanmay-alpha/codelens-codebert` on the Hub.

Total wall time on a T4: ~2–4 hours.

### 5.7 Save the model locally (optional)

```python
!cp -r codelens-model /content/drive/MyDrive/codelens-model
```

---

## 6. Final evaluation (Issue #6)

```bash
python apps/ml-worker/training/evaluate.py
```

This runs ONCE, on the held-out `test.json`. It will:
1. Load the fine-tuned model from `tanmay-alpha/codelens-codebert` (falls back to local `./codelens-model` if Hub load fails).
2. Run inference on every test sample.
3. Run the keyword baseline (from `label_mapper.py`) on the same samples.
4. Compute per-label P/R/F1 and macro-F1 for both.
5. Write `evaluation_results.json` + `evaluation_results.md` next to `test.json`.

The markdown report has a placeholder row for the GPT-4o baseline — fill that in manually after running GPT-4o zero-shot on a 200-sample subset.

**Acceptance per the plan:**
- Fine-tuned model macro-F1 must beat the keyword baseline.
- Fine-tuned model macro-F1 must also beat the GPT-4o zero-shot baseline.
- If either fails, do NOT ship. Iterate on data/keywords or training config and re-run from step 5.

---

## File map

| File | Purpose |
|---|---|
| `download-dataset.sh` | Clones microsoft/CodeBERT, copies raw data |
| `dataset.py` | Inspects raw data (counts + 5 random samples) |
| `label_mapper.py` | Filters raw data + assigns 6-category multi-label vectors |
| `verify_sample.py` | Interactive 500-sample human verification |
| `split.py` | PR-level 80/10/10 train/val/test split |
| `train.py` | Fine-tunes CodeBERT (Colab T4) |
| `evaluate.py` | Held-out eval vs keyword + GPT-4o baselines |

---

## Config knobs (all at the top of `train.py`)

| Constant | Default | Why |
|---|---|---|
| `MODEL_NAME` | `microsoft/codebert-base` | Plan Section 7 — do not change without documenting |
| `NUM_LABELS` | `6` | One per category |
| `MAX_SEQ_LENGTH` | `512` | CodeBERT max; >512 needs sliding window (Issue #8) |
| `LEARNING_RATE` | `2e-5` | Standard for BERT-family fine-tuning |
| `BATCH_SIZE` | `16` | Fits T4 with seq=512 |
| `NUM_EPOCHS` | `5` | Empirically good for ~50k samples |
| `WEIGHT_DECAY` | `0.01` | Standard |
| `WARMUP_RATIO` | `0.1` | 10% warmup |
| `THRESHOLD` | `0.5` | Sigmoid threshold for converting logits → binary |
| `HF_REPO` | `tanmay-alpha/codelens-codebert` | Private Hub repo |
