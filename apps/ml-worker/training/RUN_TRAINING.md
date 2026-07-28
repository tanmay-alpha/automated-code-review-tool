# automated-code-review-tool Training Pipeline

End-to-end pipeline for fine-tuning `microsoft/codebert-base` on
multi-label code-review comments, then pushing the result to a
HuggingFace Hub repo.

## Quick start (5 steps)

```bash
# 1. Install dependencies (CPU-only PyTorch is fine for the data generation
#    + tokenization steps; GPU needed for actual fine-tuning).
pip install -r requirements-train.txt

# 2. Generate training data (writes train.json / val.json / test.json)
python training/generate_training_data.py \
    --output-dir training/data \
    --train-size 4000 --val-size 400 --test-size 400 \
    --seed 42

# 3. Verify label distribution is healthy
python training/verify_sample.py training/data/train.json 200

# 4. Fine-tune CodeBERT (run on a GPU machine or Colab Pro)
python training/train.py \
    --output-dir ./automated-code-review-tool-model \
    --data-dir training/data \
    --model-name microsoft/codebert-base \
    --epochs 5 --batch-size 16 --lr 2e-5 \
    --push-to-hub --hf-repo YOUR_USER/automated-code-review-tool-codebert

# 5. (Optional) Evaluate on the held-out test set
python training/evaluate.py \
    --model-dir ./automated-code-review-tool-model \
    --data-dir training/data \
    --output evaluation_results.json
```

## What the pipeline does

### 1. `generate_training_data.py`
Generates synthetic but realistic <diff, comment, label> triples by
sampling from 18 hand-crafted templates (one per anti-pattern). Each
template contains a real diff fragment that actually exhibits the bug,
plus the comment a senior engineer would write on the PR. Light text
perturbation increases diversity without breaking the structure.

**Output:** three JSON files with 6 binary labels per example.
Labels cover the 6 categories defined in `model.py`:
SECURITY, PERFORMANCE, ARCHITECTURE, RELIABILITY, READABILITY,
MAINTAINABILITY.

**Honesty note:** synthetic data will not produce state-of-the-art F1
scores. It exists to ship a *working* end-to-end pipeline you can
demonstrate. To replace with real data, point the loader at a
CodeReviewer JSONL file or your own corpus.

### 2. `train.py`
Standard HuggingFace Trainer flow:
- `AutoTokenizer` + `AutoModelForSequenceClassification(num_labels=6)`
- `problem_type="multi_label_classification"` with `BCEWithLogitsLoss`
- Linear warmup, AdamW, weight decay 0.01
- Best model selected by validation macro-F1
- Optional `push_to_hub` to publish to a HF Hub repo

### 3. `evaluate.py`
Computes precision, recall, and macro-F1 per label on the held-out
test set. Writes results to `evaluation_results.json`.

### 4. Loading the fine-tuned model
The ML worker (FastAPI) loads whatever model is set in `MODEL_NAME`.
After fine-tuning, push to Hub with:
```bash
huggingface-cli login
python training/train.py --push-to-hub --hf-repo YOUR_USER/automated-code-review-tool-codebert
```
Then set `MODEL_NAME=YOUR_USER/automated-code-review-tool-codebert` in the worker env.

## Resource requirements

| Stage             | CPU only   | GPU recommended |
|-------------------|------------|-----------------|
| Data generation   | OK         | Not needed      |
| Tokenization      | OK (slow)  | Not needed      |
| Fine-tuning (5ep) | Hours/days | T4 / V100 / A10 |
| Inference         | OK         | Recommended     |

## Honest limitations

1. The synthetic training data is small (~4000 examples) and curated
   from hand-written templates. Real fine-tuning requires at least
   10-50k real PR-review pairs.
2. Generated labels are determined by the template category — there is
   no human-rated ground truth.
3. The CodeBERT model itself is not retrained here; only the
   classification head is added and trained on synthetic data.

The pipeline is wired correctly so that swapping in a real dataset
(CodeReviewer JSONL or your own) requires only pointing
`generate_training_data.py` at the source file.
