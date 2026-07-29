# Phase 1A — Dataset, Annotation and ML Database Foundation

This document explains the data foundation built during Phase 1A of the automated-code-review-tool project.

## What constitutes a code sample

A **code sample** is a single unified-diff hunk — the smallest unit of a code
change that can be meaningfully analysed. A sample has exactly one `hunk_sha256`
(deterministic SHA-256 of the raw hunk) and one `content_sha256` (SHA-256 of
the added code lines only).

Each sample is associated with:

- `repository_id` and `pull_request_id` (foreign keys to the production schema).
- `commit_sha` — the git SHA of the PR head.
- `file_path` and `language` — the new-file path and detected language.
- `old_start`, `old_count`, `new_start`, `new_count` — source-line positions.
- `added_code`, `context_code` — the human-readable parts of the hunk.
- `content_sha256` — near-duplicate detection hash.
- `redaction_version` — which redactor version was applied (`v1`).

**Binary files and deleted files are not stored as code samples** because
they cannot represent a new defect in added code.

## Canonical taxonomy versioning

The canonical anti-pattern taxonomy lives in `taxonomy/anti_patterns.yaml` and
has a semantic `version` field (e.g. `1.0.0`).

Every dataset version records the `taxonomyVersion` it was produced from,
so you can tell exactly which taxonomy definition produced which dataset.

### Trainable vs fallback entries

Not every anti-pattern is appropriate for the ML classifier:

- `trainable: true` — the pattern has enough real-world examples for the model
  to learn. These IDs are the only model labels.
- `trainable: false` — the pattern is detected by the fallback rule engine only.
  It is still a valid `anti_pattern_id` in `ml.annotations`, but it is never
  returned by the model.

The function `taxonomy.trainable_ids()` returns the deterministic, ordered
tuple of trainable IDs. This list is used by training, evaluation and inference.

### Category and severity values

**Categories (exact):**

```text
SECURITY, PERFORMANCE, ARCHITECTURE, RELIABILITY, READABILITY, MAINTAINABILITY
```

**Severities (exact):**

```text
critical, major, minor
```

### Legacy alias mapping (V5 migration)

| Legacy ID                        | Canonical ID                  |
|----------------------------------|-------------------------------|
| `PERFORMANCE_N_PLUS_1`           | `PERFORMANCE_N_PLUS_ONE`      |
| `RELIABILITY_MAGIC_NUMBER`       | `READABILITY_MAGIC_NUMBER`    |
| `RELY_BARE_EXCEPT`               | `RELIABILITY_BROAD_EXCEPTION` |
| `READ_MAGIC_NUMBER`              | `READABILITY_MAGIC_NUMBER`    |

Mappings are unambiguous. Do not guess.

## Annotation sources and states

Annotations are labels attached to code samples. Each annotation records:

- `anti_pattern_id` — a canonical (or aliased) ID.
- `label_state` — one of `positive`, `negative`, `uncertain`.
- `source` — who created the annotation:

| Source            | Meaning                                              |
|-------------------|------------------------------------------------------|
| `fallback`        | Rule engine found the pattern.                        |
| `model`           | ML classifier predicted the pattern.                  |
| `human`           | A human reviewer added or confirmed the label.        |
| `finding_feedback`| Created automatically from user feedback on a finding.|
| `import`          | Imported from an external dataset.                    |

### Feedback semantics

The finding workflow exposes `accepted`, `dismissed`, `fixed` statuses:

- **accepted** → creates a `positive` annotation (the reviewer agreed with the finding).
- **dismissed** → creates a `negative` annotation (the reviewer disagreed with the finding).
- **fixed**    → creates a `positive` annotation plus resolution metadata (the PR is now clean).

Annotation creation is **idempotent** — calling it twice with the same finding ID
produces no duplicate annotations. Conflicting feedback is recorded, not silently
replaced.

## Dataset lifecycle

Datasets are produced by the Python tool in `apps/ml-worker/training/build_dataset.py`.

### Commands

```bash
# Create a new dataset version (draft status)
python training/build_dataset.py create \
  --name code-review-real \
  --version 0.1.0 \
  --seed 42 \
  --output-dir training/data/code-review-real-0.1.0

# Validate an existing dataset directory
python training/build_dataset.py validate \
  --dataset-dir training/data/code-review-real-0.1.0

# Freeze a dataset (cannot be unfrozen)
python training/build_dataset.py freeze \
  --name code-review-real \
  --version 0.1.0
```

### Draft vs frozen

- **draft** — the dataset can still be rebuilt. Samples may still change.
- **frozen** — immutable. The manifest hash is final. No further changes are
  allowed. Attempting to modify a frozen dataset fails the validation step.

### Split-isolation rules

- A group is defined by `(repository_id, pull_request_id)`.
- All samples from the same PR **must** go to the same split.
- The same rule applies at the repository level when repository-level
  isolation is enabled.
- The deterministic group split prevents data leakage from train/validation/test.

### Secret redaction behaviour

Before persisting a code sample, every value matching common secret patterns
is replaced with `<REDACTED_SECRET>`.

- Raw secrets are **never logged**.
- The redactor version (`v1`) is stored on every sample.
- Redacted samples retain enough structural information for pattern detection.
- The quality validator checks for patterns that escaped redaction.

## Dataset manifest

Every dataset version directory includes a `manifest.json`:

```json
{
  "datasetName": "code-review-real",
  "datasetVersion": "0.1.0",
  "taxonomyVersion": "1.0.0",
  "seed": 42,
  "createdAt": "2026-07-29T...",
  "sourceCommit": "abc123...",
  "sampleCount": 1024,
  "splitCounts": { "train": 717, "validation": 153, "test": 154 },
  "labelDistribution": { "SECURITY_HARDCODED_SECRET": 12, ... },
  "repositoryCounts": { "org/repo-a": 500, ... },
  "duplicateCount": 8,
  "manifestSha256": "..."
}
```

The manifest contains **no database credentials, OAuth tokens or source-code secrets**.

## Known limitations

- **No model has been trained during Phase 1A.** No accuracy claim is supported.
- Synthetic test fixtures are used for pipeline validation; they do not
  represent real-world performance.
- Model training begins only after a real, frozen, reviewed dataset exists.
- Group isolation is at PR and repository granularity; sub-group leakage
  (e.g. commits within the same PR that share author/date patterns) is not
  separately controlled.
- The quality-score formula is deterministic and does not account for
  time-of-day or review-thread ordering.
