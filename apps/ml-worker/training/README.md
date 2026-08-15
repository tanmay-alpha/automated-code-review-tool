# ML dataset and checkpoint lifecycle

Run every command below from `apps/ml-worker`. The canonical label order comes
from `../../taxonomy/anti_patterns.yaml`; none of these tools defines labels
locally. Runtime inference dependencies remain in `requirements.txt`, while
PostgreSQL, scikit-learn, and training utilities stay in
`requirements-train.txt`.

## Install and verify

```powershell
python -m pip install -r requirements-train.txt -r requirements-test.txt
ruff check app training tests
mypy app training
pytest -m "not slow" -q
```

## Annotation

Set the dataset database connection without writing it to an artifact:

```powershell
$env:ML_DATASET_DATABASE_URL = "postgresql://user:password@localhost:5432/code_review"
python -m training.annotate_dataset queue --output artifacts/annotation-queue.jsonl --limit 100 --strategy unreviewed
python -m training.annotate_dataset import --input artifacts/annotation-decisions.jsonl
python -m training.annotate_dataset conflicts
python -m training.annotate_dataset adjudicate --input artifacts/adjudications.jsonl
python -m training.annotate_dataset stats
```

An annotation decision is one JSON object per line:

```json
{"sampleId":"00000000-0000-0000-0000-000000000001","antiPatternId":"SECURITY_HARDCODED_SECRET","label":"positive","reviewerId":"00000000-0000-0000-0000-000000000002","lineStart":10,"lineEnd":10}
```

Adjudication uses the same fields with `resolvedLabel` instead of `label`.
Imports reject unknown taxonomy IDs, unsafe repository policy, invalid ranges,
and duplicate reviewer decisions. Active same-trust positive/negative evidence
is a conflict until adjudicated.

## Build and freeze a dataset

```powershell
python -m training.build_dataset create --name review-gold --version 1.0.0 --output-dir artifacts/datasets/review-gold-1.0.0
python -m training.build_dataset validate --dataset-dir artifacts/datasets/review-gold-1.0.0
python -m training.build_dataset freeze --dataset-dir artifacts/datasets/review-gold-1.0.0
```

`create` reads approved redacted samples, explicit per-label human evidence,
and clean-review negatives from PostgreSQL. It writes deterministic
`samples.jsonl`, `splits.json`, `manifest.json`, and
`data_quality_report.json`. Exact and near-duplicate components are assigned as
groups, so they cannot cross train, validation, and test. `freeze` refuses any
critical quality failure and makes the database release immutable.

## Baselines, train, tune, and evaluate

```powershell
python -m training.baselines --dataset-dir artifacts/datasets/review-gold-1.0.0 --output artifacts/baselines.json
python -m training.train --dataset-dir artifacts/datasets/review-gold-1.0.0 --output-dir artifacts/checkpoint-raw
python -m training.tune_thresholds --dataset-dir artifacts/datasets/review-gold-1.0.0 --checkpoint artifacts/checkpoint-raw --output-dir artifacts/checkpoint-tuned
python -m training.evaluate --dataset-dir artifacts/datasets/review-gold-1.0.0 --checkpoint artifacts/checkpoint-tuned --output-dir artifacts/evaluation
```

Training reads only train and validation records, applies masked loss to
explicit label decisions, caps train-derived positive weights, and stops on
validation loss. Thresholds are tuned per label on validation only. Evaluation
then reads test once and records the transformer, production-rule, and
train-only TF-IDF/logistic baselines. Empty support remains visible; results
are never synthesized.

## Smoke and approve, without deployment

```powershell
python -m training.smoke_checkpoint --dataset-dir artifacts/datasets/review-gold-1.0.0 --checkpoint artifacts/checkpoint-tuned --output artifacts/smoke-report.json
python -m training.promote --dataset-dir artifacts/datasets/review-gold-1.0.0 --checkpoint artifacts/checkpoint-tuned --evaluation-dir artifacts/evaluation --smoke-report artifacts/smoke-report.json --output-dir artifacts/checkpoint-approved
```

The smoke command performs a real local load, production compatibility check,
and minimal windowed inference. Promotion requires a frozen zero-critical
dataset, validation-tuned thresholds with per-label support, frozen-test
metrics, both baselines, and the matching smoke report. It writes
`promotion.json` and marks the new checkpoint `approved_not_deployed`; it does
not change deployment configuration or apply an arbitrary F1 cutoff.
