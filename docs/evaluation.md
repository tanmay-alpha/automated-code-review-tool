# CodeLens — Model Evaluation

This document describes how the fine-tuned CodeBERT model is evaluated against
baselines, the dataset it is trained and tested on, and the exact commands
required to reproduce the numbers.

Source: [ENGINEERING_PLAN.md](../ENGINEERING_PLAN.md.md) Section 6 (data pipeline)
and Section 7 (ML pipeline).

---

## 1. Methodology

### Task definition

Multi-label classification of a code diff into one or more of six anti-pattern
categories. For each input diff the model produces a 6-dimensional probability
vector — one sigmoid logit per category — thresholded at **0.5**.

| Category index | Category name |
|---|---|
| 0 | `SECURITY` |
| 1 | `PERFORMANCE` |
| 2 | `ARCHITECTURE` |
| 3 | `RELIABILITY` |
| 4 | `READABILITY` |
| 5 | `MAINTAINABILITY` |

### Loss function

`BCEWithLogitsLoss` applied per label independently. Each category is treated as
its own binary problem; a single diff can activate any subset of categories.

### Metrics

For every category we report:

- **Precision** — `TP / (TP + FP)`. Of the categories the model flagged, how many were right?
- **Recall** — `TP / (TP + FN)`. Of the categories that should have been flagged, how many did we catch?
- **F1** — harmonic mean of precision and recall. The single number we compare across models.

**Macro-averaged F1** is the primary selection metric — unweighted mean of the
per-category F1 scores, so rare categories matter as much as common ones.

### Baselines

Every evaluation run reports three rows in the same table:

1. **Fine-tuned CodeBERT** — our `tanmay-alpha/codelens-codebert` model.
2. **Keyword matching baseline** — `label_mapper.py` applied directly to the
   test diffs. This is what we would ship if we did no ML at all.
3. **GPT-4o zero-shot** — manually run on 200 stratified test samples with a
   structured prompt asking for the same six labels. (Saved as
   `training/data/eval/gpt4o_results.json`.) Cost target: ≤ $2.

A new model ships only if its **macro-F1 beats both baselines**.

### Sigmoid threshold

Fixed at `0.5` for all six labels. Changing it requires editing
`evaluate.py` and re-running the full eval — documented in the script's header
comment.

### What "held-out" means here

Test data (`test.json`) is committed to the repo for reproducibility but **must
not be opened, inspected, or used for any decision during development**. PR
splits (not random rows) ensure the same PR never appears in train and test.

---

## 2. Target Metrics

Targets from `ENGINEERING_PLAN.md` Section 9 (release gates). Final numbers
filled in by `evaluate.py` after the first full training run.

| Category       | Precision | Recall | F1    |
|----------------|-----------|--------|-------|
| SECURITY       | pending — run evaluate.py | pending — run evaluate.py | pending — run evaluate.py |
| PERFORMANCE    | pending — run evaluate.py | pending — run evaluate.py | pending — run evaluate.py |
| ARCHITECTURE   | pending — run evaluate.py | pending — run evaluate.py | pending — run evaluate.py |
| RELIABILITY    | pending — run evaluate.py | pending — run evaluate.py | pending — run evaluate.py |
| READABILITY    | pending — run evaluate.py | pending — run evaluate.py | pending — run evaluate.py |
| MAINTAINABILITY| pending — run evaluate.py | pending — run evaluate.py | pending — run evaluate.py |
| **Macro avg**  | pending — run evaluate.py | pending — run evaluate.py | **≥ 0.68 (release gate)** |
| GPT-4o baseline (200 samples) | pending — run evaluate.py | pending — run evaluate.py | pending — run evaluate.py |

The "pending — run evaluate.py" marker is a literal placeholder string. It
exists because no model has been trained yet (Issue #5 in the engineering plan).
When the run completes, `evaluate.py` overwrites this section with the real
numbers.

---

## 3. How to reproduce

End-to-end reproduction of the training-data pipeline **and** the evaluation
table. Estimated runtime: ~30 minutes for data prep, several hours for training,
~5 minutes for evaluation.

### 3.1. Prerequisites

```bash
# Python 3.11
pyenv install 3.11 && pyenv local 3.11

# Install training-only dependencies (heavier)
cd apps/ml-worker
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-train.txt

# Required environment variables
export HF_TOKEN=hf_xxxxxxxxxxxxx                   # private model repo access
export ML_MODEL_NAME=tanmay-alpha/codelens-codebert
```

### 3.2. Build the dataset (Steps 1–5 from Section 6)

```bash
# 1. Download raw CodeReviewer data
bash scripts/download-dataset.sh

# 2. Filter noise comments
cd apps/ml-worker
python -m training.dataset --stage filter

# 3. Apply keyword label mapper (6-category multi-label vectors)
python -m training.label_mapper

# 4. Manually verify 500 random samples — interactive, aim for ≥90% agreement
python -m training.verify_sample

# 5. Split by PR ID (not random row) → train/val/test JSON files
python -m training.split
```

Outputs land in `apps/ml-worker/training/data/`:

```
training/data/
├── raw/           # downloaded CodeReviewer data
├── filtered/      # after step 2
├── labeled/       # after step 3
├── train.json     # 80% of PRs
├── val.json       # 10% of PRs
└── test.json      # 10% of PRs — DO NOT OPEN DURING DEVELOPMENT
```

### 3.3. Train the model (Issue #5)

```bash
python -m training.train
# Saves best checkpoint (by val macro-F1) to HuggingFace Hub private repo
# Trains for 5 epochs at lr=2e-5, batch_size=16, max_seq_length=512
```

### 3.4. Run evaluation

```bash
python -m training.evaluate
```

`evaluate.py` will:

1. Load `tanmay-alpha/codelens-codebert` from HuggingFace Hub.
2. Run inference over `test.json`.
3. Compute per-category precision / recall / F1 at threshold 0.5.
4. Compute macro-F1.
5. Re-run `label_mapper.py` against the test diffs for the keyword baseline.
6. Read `training/data/eval/gpt4o_results.json` for the GPT-4o row.
7. Overwrite Section 2 of this file with the results, and write
   `training/data/eval/results.json` for the CI artifact.

### 3.5. Verify the release gate

The script exits non-zero if `macro_f1 < 0.68`, which fails the
`ci-ml-worker` workflow. Locally:

```bash
python -m training.evaluate --check-gate
```

---

## 4. Dataset Statistics

| Field | Value |
|---|---|
| Source | `microsoft/CodeBERT` → `CodeReviewer/code-review-data` (open-source, MIT) |
| License | MIT (Microsoft Research) |
| Raw samples | ~150,000 |
| Filter rule 1 | `comment` length < 20 chars → drop |
| Filter rule 2 | `comment` matches only `nit|typo|rename|spacing|whitespace|format` → drop |
| Filter rule 3 | `label == 0` (non-actionable comment) → drop |
| Filter rule 4 | `diff` is empty → drop |
| Filtered yield | ~55,000 samples |
| Label mapper | Keyword match across 6 categories — multi-label (one sample can match multiple) |
| Samples with zero labels | discarded |
| Final labeled set | ~40,000–55,000 (depends on manual verification pass) |
| Manual verification | 500 random samples reviewed, target ≥90% inter-rater agreement |
| Split strategy | **By PR ID, not row** (prevents same PR appearing in train + test) |
| Train split | 80% of PRs |
| Val split | 10% of PRs |
| Test split | 10% of PRs — committed but not opened during development |

### Filter pseudocode (from `training/dataset.py`)

```python
def keep(sample):
    if len(sample.comment) < 20:
        return False
    if re.fullmatch(r"(nit|typo|rename|spacing|whitespace|format)", sample.comment, re.I):
        return False
    if sample.label == 0:
        return False
    if not sample.diff.strip():
        return False
    return True
```

### Label mapper keyword map (from `training/label_mapper.py`)

```python
KEYWORD_MAP = {
    "SECURITY":       ["password", "secret", "api_key", "token", "credential",
                       "sql injection", "eval(", "hardcoded", "plaintext"],
    "PERFORMANCE":    ["n+1", "loop", "query inside", "redundant",
                       "unnecessary iteration", "O(n^2)", "nested loop",
                       "repeated call"],
    "ARCHITECTURE":   ["god class", "too many responsibilities",
                       "single responsibility", "circular", "feature envy",
                       "coupling"],
    "RELIABILITY":    ["bare except", "except:", "null check", "none check",
                       "not handled", "resource leak", "unclosed"],
    "READABILITY":    ["magic number", "unclear", "misleading",
                       "confusing name"],
    "MAINTAINABILITY":["too long", "deep nesting", "duplicate", "copy-paste"],
}
```

### Output record schema

Each line in `train.json` / `val.json` / `test.json`:

```json
{
  "diff":   "unified diff string",
  "labels": [0, 0, 1, 0, 0, 1],
  "pr_id":  12345
}
```

`labels` is a 6-element binary vector aligned to the order
`SECURITY, PERFORMANCE, ARCHITECTURE, RELIABILITY, READABILITY, MAINTAINABILITY`.