# ML system design

The ML subsystem is designed around versioned, redacted pull-request hunks.
The deterministic rule engine remains the production detector until an optional
multi-label checkpoint is trained, evaluated, and explicitly promoted.

## Canonical contracts

- `taxonomy/anti_patterns.yaml` defines the semantic taxonomy version and the
  concrete anti-pattern IDs. Only entries marked `trainable` are model labels.
- `apps/ml-worker/app/taxonomy.py` validates and loads that taxonomy for serving
  and training.
- `apps/ml-worker/app/preprocessing.py` defines the text representation shared
  by training, evaluation, and serving.
- Checkpoint metadata must identify its taxonomy, ordered labels, dataset
  manifest, base model, and per-label thresholds before it can be loaded.

Broad categories such as `SECURITY` and `RELIABILITY` are reporting metadata,
not six classifier outputs.

## Data lifecycle

```text
redacted PR hunks
    -> ml.code_samples
    -> human or feedback annotations
    -> per-label resolution and provenance
    -> immutable dataset version
    -> grouped train / validation / test splits
    -> rule and linear baselines
    -> optional CodeBERT experiment
    -> validation-only threshold tuning
    -> one frozen-test evaluation
    -> explicit model promotion
```

A code sample represents one localized diff hunk and records repository, pull
request, commit, file, language, line-range, content identity, redaction, and
data-use metadata. Binary and deleted-file content is not useful training input.
Raw secrets and unconsented private code must not enter outbox payloads, dataset
artifacts, annotation exports, experiment output, or logs.

Annotations distinguish `positive`, `negative`, and `uncertain` evidence. The
absence of a finding is not negative evidence. Every resolved label retains its
source annotation IDs and trust level; model and fallback predictions are not
automatically gold labels. Conflicting human labels require adjudication.

## Dataset contract

A frozen dataset lives outside Git and contains:

- `manifest.json`
- `samples.jsonl`
- `splits.json`
- `data_quality_report.json`

The manifest binds the dataset version to a taxonomy version, source revision,
seed, split counts, label and language distributions, redaction versions, and a
deterministic manifest hash. The hash is computed without the hash field itself.

Repository and pull-request groups cannot cross splits. Exact or near-duplicate
groups cannot cross splits either. Validation rejects unknown labels, missing
provenance, unsupported policy states, leaked secrets, empty hunks, duplicate
sample IDs, and split leakage before freezing.

## Evaluation and promotion

Rule, TF-IDF/logistic, and transformer candidates must consume the same frozen
split and preprocessing representation. Model selection, early stopping, and
per-label threshold tuning use validation data only. The test split is evaluated
after the experiment is frozen; generated metrics stay with experiment artifacts
outside Git.

No checkpoint is promoted merely because it can be loaded. Promotion also
requires compatible metadata, a frozen dataset with no critical quality failure,
an evaluation artifact, baseline comparison, and deployment smoke tests. If a
promoted model is missing or incompatible, serving degrades to the deterministic
fallback and reports the engine actually used.

See [the training guide](../apps/ml-worker/training/README.md) for executable
commands once a reviewed dataset and approved checkpoint are available.
