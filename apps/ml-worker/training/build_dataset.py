"""Build, validate, persist, and freeze versioned ML dataset artifacts."""

from __future__ import annotations

import argparse
import json
import logging
import os
import subprocess
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Iterable

from app.taxonomy import Taxonomy, load_taxonomy
from training.dataset_contract import (
    ALLOWED_DATA_USE,
    DatasetRecord,
    LabelEvidence,
    LabelState,
    write_records,
)
from training.dataset_manifest import (
    DatasetManifest,
    file_sha256,
    manifest_now,
    read_manifest,
    write_manifest,
)
from training.group_split import build_split_plan, summarize
from training.label_resolution import (
    AnnotationEvidence,
    Resolution,
    ReviewState,
    clean_review_evidence,
    resolve_label,
)
from training.validate_dataset import Finding, validate_dataset_dir, write_quality_report

log = logging.getLogger(__name__)
GOLD_TRUST = frozenset({"human_adjudicated", "human_single", "finding_feedback", "import"})


@dataclass(frozen=True)
class CodeSampleRow:
    id: str
    repository_id: str
    pull_request_id: str
    commit_sha: str
    file_path: str
    language: str
    old_start: int
    old_count: int
    new_start: int
    new_count: int
    hunk_sha256: str
    content_sha256: str
    group_key: str
    raw_hunk: str
    added_code: str
    context_code: str
    repository_visibility: str
    license_spdx: str | None
    data_use_status: str
    redaction_version: str


@dataclass(frozen=True)
class AnnotationRow:
    id: str
    code_sample_id: str
    anti_pattern_id: str
    label_state: str
    source: str
    confidence: float | None
    reviewer_user_id: str | None
    trust_level: str
    resolution_state: str


@dataclass(frozen=True)
class SampleReviewRow:
    id: str
    code_sample_id: str
    reviewer_user_id: str | None
    review_status: str
    reviewed_label_ids: tuple[str, ...]
    clean_confirmed: bool


def _column_names(description: Any) -> list[str]:
    return [getattr(item, "name", item[0]) for item in description]


def _fetch_samples(conn: Any) -> list[CodeSampleRow]:
    sql = """
        SELECT id::text, repository_id::text, pull_request_id::text,
               commit_sha, file_path, language, old_start, old_count,
               new_start, new_count, hunk_sha256, content_sha256, group_key,
               raw_hunk, COALESCE(added_code, '') AS added_code,
               COALESCE(context_code, '') AS context_code,
               repository_visibility, license_spdx, data_use_status,
               redaction_version
          FROM ml.code_samples
         ORDER BY id
    """
    with conn.cursor() as cursor:
        cursor.execute(sql)
        columns = _column_names(cursor.description)
        return [CodeSampleRow(**dict(zip(columns, row))) for row in cursor.fetchall()]


def _fetch_annotations(conn: Any, sample_ids: Iterable[str]) -> dict[str, list[AnnotationRow]]:
    ids = list(sample_ids)
    if not ids:
        return {}
    sql = """
        SELECT id::text, code_sample_id::text, anti_pattern_id, label_state,
               source, confidence, reviewer_user_id::text, trust_level,
               resolution_state
          FROM ml.annotations
         WHERE code_sample_id::text = ANY(%s)
         ORDER BY created_at, id
    """
    result: dict[str, list[AnnotationRow]] = defaultdict(list)
    with conn.cursor() as cursor:
        cursor.execute(sql, (ids,))
        columns = _column_names(cursor.description)
        for row in cursor.fetchall():
            annotation = AnnotationRow(**dict(zip(columns, row)))
            result[annotation.code_sample_id].append(annotation)
    return dict(result)


def _fetch_reviews(conn: Any, sample_ids: Iterable[str]) -> dict[str, list[SampleReviewRow]]:
    ids = list(sample_ids)
    if not ids:
        return {}
    sql = """
        SELECT id::text, code_sample_id::text, reviewer_user_id::text,
               review_status, reviewed_label_ids, clean_confirmed
          FROM ml.sample_reviews
         WHERE code_sample_id::text = ANY(%s)
         ORDER BY created_at, id
    """
    result: dict[str, list[SampleReviewRow]] = defaultdict(list)
    with conn.cursor() as cursor:
        cursor.execute(sql, (ids,))
        columns = _column_names(cursor.description)
        for row in cursor.fetchall():
            raw = dict(zip(columns, row))
            reviewed = raw.get("reviewed_label_ids") or []
            if isinstance(reviewed, str):
                reviewed = json.loads(reviewed)
            raw["reviewed_label_ids"] = tuple(str(value) for value in reviewed)
            review = SampleReviewRow(**raw)
            result[review.code_sample_id].append(review)
    return dict(result)


def resolve_dataset_records(
    samples: list[CodeSampleRow],
    annotations: dict[str, list[AnnotationRow]],
    reviews: dict[str, list[SampleReviewRow]],
    taxonomy: Taxonomy,
) -> tuple[list[DatasetRecord], list[Finding]]:
    trainable = tuple(taxonomy.trainable_ids())
    all_ids = set(taxonomy.ids())
    records: list[DatasetRecord] = []
    findings: list[Finding] = []

    for sample in samples:
        if sample.data_use_status not in ALLOWED_DATA_USE:
            findings.append(Finding("warning", "sample_policy_excluded", f"excluded {sample.data_use_status}", (sample.id,)))
            continue
        sample_annotations = annotations.get(sample.id, [])
        unknown = sorted({item.anti_pattern_id for item in sample_annotations if item.anti_pattern_id not in all_ids})
        if unknown:
            findings.append(Finding("critical", "unknown_annotation_label", f"unknown annotation IDs: {unknown}", (sample.id,)))
            continue

        evidence_by_label: dict[str, list[AnnotationEvidence]] = defaultdict(list)
        for item in sample_annotations:
            evidence_by_label[item.anti_pattern_id].append(
                AnnotationEvidence(
                    annotation_id=item.id,
                    trust_level=item.trust_level,
                    label=item.label_state,
                    reviewer_id=item.reviewer_user_id,
                    source=item.source,
                    resolution_state=item.resolution_state,
                    is_adjudicated=item.trust_level == "human_adjudicated",
                )
            )
        review_states = [
            ReviewState(
                review_id=item.id,
                review_status=item.review_status,
                clean_confirmed=item.clean_confirmed,
                reviewed_labels=item.reviewed_label_ids,
                reviewer_id=item.reviewer_user_id,
            )
            for item in reviews.get(sample.id, [])
        ]

        resolved: list[LabelEvidence] = []
        has_conflict = False
        for anti_pattern_id in trainable:
            resolution = resolve_label(evidence_by_label.get(anti_pattern_id, []))
            negative_reviews = clean_review_evidence(review_states, anti_pattern_id)
            if resolution.resolution == Resolution.CONFLICT:
                findings.append(Finding("critical", "annotation_conflict", f"unresolved conflict for {anti_pattern_id}", (sample.id,)))
                has_conflict = True
                continue
            if (
                negative_reviews
                and resolution.winning_trust == "human_single"
                and resolution.resolution in {Resolution.POSITIVE, Resolution.UNCERTAIN_WEAK}
            ):
                findings.append(
                    Finding(
                        "critical",
                        "annotation_clean_review_conflict",
                        f"positive/uncertain human annotation conflicts with clean review for {anti_pattern_id}",
                        (sample.id,),
                    )
                )
                has_conflict = True
                continue
            if negative_reviews and resolution.winning_trust != "human_adjudicated":
                annotation_ids = (
                    resolution.annotation_ids
                    if resolution.resolution is Resolution.NEGATIVE
                    else ()
                )
                resolved.append(
                    LabelEvidence(
                        anti_pattern_id=anti_pattern_id,
                        state="negative",
                        trust_level="human_single",
                        annotation_ids=annotation_ids,
                        review_ids=negative_reviews,
                    )
                )
                continue
            if resolution.resolution in {Resolution.POSITIVE, Resolution.NEGATIVE} and resolution.winning_trust in GOLD_TRUST:
                state: LabelState = (
                    "positive"
                    if resolution.resolution is Resolution.POSITIVE
                    else "negative"
                )
                resolved.append(
                    LabelEvidence(
                        anti_pattern_id=anti_pattern_id,
                        state=state,
                        trust_level=str(resolution.winning_trust),
                        annotation_ids=resolution.annotation_ids,
                    )
                )
                continue
        if has_conflict:
            continue
        if not resolved:
            findings.append(Finding("warning", "sample_without_gold_evidence", "sample has no resolved human-backed label", (sample.id,)))
            continue

        try:
            records.append(
                DatasetRecord(
                    sample_id=sample.id,
                    repository_id=sample.repository_id,
                    pull_request_id=sample.pull_request_id,
                    commit_sha=sample.commit_sha,
                    file_path=sample.file_path,
                    language=sample.language,
                    old_start=sample.old_start,
                    old_count=sample.old_count,
                    new_start=sample.new_start,
                    new_count=sample.new_count,
                    hunk_sha256=sample.hunk_sha256,
                    content_sha256=sample.content_sha256,
                    group_key=sample.group_key,
                    raw_hunk=sample.raw_hunk,
                    added_code=sample.added_code,
                    context_code=sample.context_code,
                    repository_visibility=sample.repository_visibility,
                    license_spdx=sample.license_spdx,
                    data_use_status=sample.data_use_status,
                    redaction_version=sample.redaction_version,
                    taxonomy_version=taxonomy.version,
                    labels=tuple(resolved),
                )
            )
        except ValueError as exc:
            findings.append(Finding("critical", "invalid_sample_record", str(exc), (sample.id,)))
    return records, findings


def _git_sha() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    value = completed.stdout.strip()
    if len(value) != 40:
        raise RuntimeError("git rev-parse HEAD did not return a full SHA")
    return value


def build_artifacts(
    *,
    records: list[DatasetRecord],
    build_findings: list[Finding],
    output_dir: Path,
    dataset_name: str,
    dataset_version: str,
    taxonomy_version: str,
    source_git_sha: str,
    seed: int,
    created_at: str,
    near_duplicate_threshold: float,
) -> tuple[DatasetManifest, dict[str, Any]]:
    output_dir.mkdir(parents=True, exist_ok=True)
    existing_manifest = output_dir / "manifest.json"
    if existing_manifest.exists() and read_manifest(existing_manifest).frozen:
        raise ValueError(f"refusing to overwrite frozen dataset at {output_dir}")

    sample_splits, components, duplicate_pairs = build_split_plan(
        records, seed=seed, near_threshold=near_duplicate_threshold
    )
    split_records = [replace(record, split=sample_splits[record.sample_id]) for record in records]
    samples_path = output_dir / "samples.jsonl"
    write_records(samples_path, split_records)
    splits_payload = {
        "schema_version": 1,
        "seed": seed,
        "near_duplicate_threshold": near_duplicate_threshold,
        "samples": dict(sorted(sample_splits.items())),
        "components": dict(sorted(components.items())),
        "duplicate_pairs": [pair.to_dict() for pair in duplicate_pairs],
    }
    splits_path = output_dir / "splits.json"
    splits_path.write_text(json.dumps(splits_payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    labels: Counter[str] = Counter()
    negative_labels: Counter[str] = Counter()
    languages: Counter[str] = Counter()
    for record in split_records:
        languages[record.language] += 1
        labels.update(record.positive_labels)
        negative_labels.update(record.negative_labels)
    manifest = DatasetManifest(
        dataset_name=dataset_name,
        dataset_version=dataset_version,
        taxonomy_version=taxonomy_version,
        source_git_sha=source_git_sha,
        seed=seed,
        sample_count=len(split_records),
        repository_count=len({record.repository_id for record in split_records}),
        split_counts=summarize(sample_splits),
        label_distribution=dict(sorted(labels.items())),
        negative_label_distribution=dict(sorted(negative_labels.items())),
        language_distribution=dict(sorted(languages.items())),
        redaction_versions=sorted({record.redaction_version for record in split_records}),
        created_at=created_at,
        samples_sha256=file_sha256(samples_path),
        splits_sha256=file_sha256(splits_path),
        near_duplicate_threshold=near_duplicate_threshold,
    )
    manifest = write_manifest(existing_manifest, manifest)
    report = validate_dataset_dir(output_dir)
    if build_findings:
        report["findings"].extend(item.to_dict() for item in build_findings)
        report["critical_failures"] += sum(item.severity == "critical" for item in build_findings)
        report["warnings"] += sum(item.severity == "warning" for item in build_findings)
    write_quality_report(output_dir, report)
    return manifest, report


def _check_dataset_not_frozen(conn: Any, name: str, version: str) -> None:
    with conn.cursor() as cursor:
        cursor.execute("SELECT status FROM ml.dataset_versions WHERE name=%s AND version=%s", (name, version))
        row = cursor.fetchone()
    if row and row[0] != "draft":
        raise ValueError(f"dataset {name}@{version} is not mutable: {row[0]}")


def _persist_dataset(
    conn: Any,
    manifest: DatasetManifest,
    records: list[DatasetRecord],
) -> None:
    _check_dataset_not_frozen(conn, manifest.dataset_name, manifest.dataset_version)
    with conn.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO ml.dataset_versions
                (name, version, taxonomy_version, status, generation_config,
                 manifest_sha256, sample_count, positive_annotation_count, created_at)
            VALUES (%s, %s, %s, 'draft', %s::jsonb, %s, %s, %s, NOW())
            ON CONFLICT (name, version) DO UPDATE SET
                taxonomy_version=EXCLUDED.taxonomy_version,
                generation_config=EXCLUDED.generation_config,
                manifest_sha256=EXCLUDED.manifest_sha256,
                sample_count=EXCLUDED.sample_count,
                positive_annotation_count=EXCLUDED.positive_annotation_count
            RETURNING id::text
            """,
            (
                manifest.dataset_name,
                manifest.dataset_version,
                manifest.taxonomy_version,
                json.dumps({"seed": manifest.seed, "source_git_sha": manifest.source_git_sha}),
                manifest.manifest_sha256,
                manifest.sample_count,
                sum(len(record.positive_labels) for record in records),
            ),
        )
        dataset_id = cursor.fetchone()[0]
        for record in records:
            cursor.execute(
                """
                INSERT INTO ml.dataset_items
                    (dataset_version_id, code_sample_id, split, group_key, labels_snapshot)
                VALUES (%s::uuid, %s::uuid, %s, %s, %s::jsonb)
                ON CONFLICT (dataset_version_id, code_sample_id) DO UPDATE SET
                    split=EXCLUDED.split,
                    group_key=EXCLUDED.group_key,
                    labels_snapshot=EXCLUDED.labels_snapshot
                """,
                (dataset_id, record.sample_id, record.split, record.group_key, json.dumps([item.to_dict() for item in record.labels])),
            )


def cmd_create(args: argparse.Namespace) -> int:
    database_url = args.database_url or os.environ.get("ML_DATASET_DATABASE_URL")
    if not database_url:
        raise SystemExit("ML_DATASET_DATABASE_URL or --database-url is required")
    import psycopg

    taxonomy = load_taxonomy()
    with psycopg.connect(
        database_url, options="-c default_transaction_read_only=on"
    ) as conn:
        samples = _fetch_samples(conn)
        annotations = _fetch_annotations(conn, (sample.id for sample in samples))
        reviews = _fetch_reviews(conn, (sample.id for sample in samples))
    records, findings = resolve_dataset_records(samples, annotations, reviews, taxonomy)
    manifest, report = build_artifacts(
        records=records,
        build_findings=findings,
        output_dir=Path(args.output_dir),
        dataset_name=args.name,
        dataset_version=args.version,
        taxonomy_version=taxonomy.version,
        source_git_sha=args.source_git_sha or _git_sha(),
        seed=args.seed,
        created_at=args.created_at or manifest_now(),
        near_duplicate_threshold=args.near_duplicate_threshold,
    )
    if report["critical_failures"]:
        log.error("dataset has %d critical quality failures; draft was not persisted", report["critical_failures"])
        return 1
    persisted_records = _read_built_records(Path(args.output_dir))
    with psycopg.connect(database_url) as conn:
        _persist_dataset(conn, manifest, persisted_records)
        conn.commit()
    return 0


def _read_built_records(dataset_dir: Path) -> list[DatasetRecord]:
    from training.dataset_contract import read_records

    return read_records(dataset_dir / "samples.jsonl")


def cmd_validate(args: argparse.Namespace) -> int:
    dataset_dir = Path(args.dataset_dir)
    report = validate_dataset_dir(dataset_dir)
    write_quality_report(dataset_dir, report)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["critical_failures"] == 0 else 1


def cmd_freeze(args: argparse.Namespace) -> int:
    database_url = args.database_url or os.environ.get("ML_DATASET_DATABASE_URL")
    if not database_url:
        raise SystemExit("ML_DATASET_DATABASE_URL or --database-url is required")
    dataset_dir = Path(args.dataset_dir)
    draft = read_manifest(dataset_dir / "manifest.json")
    if draft.frozen:
        raise SystemExit("dataset artifact is already frozen")
    report = validate_dataset_dir(dataset_dir)
    if report["critical_failures"]:
        write_quality_report(dataset_dir, report)
        raise SystemExit("dataset has critical quality failures; refusing to freeze")
    if draft.synthetic and not args.allow_synthetic_smoke:
        raise SystemExit("synthetic datasets require --allow-synthetic-smoke and are not promotable")

    frozen = write_manifest(dataset_dir / "manifest.json", replace(draft, frozen=True, manifest_sha256=""))
    frozen_report = validate_dataset_dir(dataset_dir)
    write_quality_report(dataset_dir, frozen_report)
    if frozen_report["critical_failures"]:
        write_manifest(dataset_dir / "manifest.json", replace(draft, manifest_sha256=""))
        raise SystemExit("frozen artifact validation failed")

    import psycopg

    try:
        with psycopg.connect(database_url) as conn:
            with conn.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE ml.dataset_versions
                       SET manifest_sha256=%s, status='frozen', frozen_at=NOW()
                     WHERE name=%s AND version=%s AND status='draft'
                    """,
                    (
                        frozen.manifest_sha256,
                        frozen.dataset_name,
                        frozen.dataset_version,
                    ),
                )
                if cursor.rowcount != 1:
                    raise RuntimeError(
                        "dataset draft row was not found or was already frozen"
                    )
            conn.commit()
    except Exception:
        write_manifest(
            dataset_dir / "manifest.json",
            replace(draft, frozen=False, manifest_sha256=""),
        )
        write_quality_report(dataset_dir, validate_dataset_dir(dataset_dir))
        raise
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", default=os.environ.get("ML_DATASET_DATABASE_URL"))
    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create")
    create.add_argument("--name", required=True)
    create.add_argument("--version", required=True)
    create.add_argument("--output-dir", required=True)
    create.add_argument("--seed", type=int, default=42)
    create.add_argument("--source-git-sha")
    create.add_argument("--created-at")
    create.add_argument("--near-duplicate-threshold", type=float, default=0.85)
    create.set_defaults(func=cmd_create)

    validate = subparsers.add_parser("validate")
    validate.add_argument("--dataset-dir", required=True)
    validate.set_defaults(func=cmd_validate)

    freeze = subparsers.add_parser("freeze")
    freeze.add_argument("--dataset-dir", required=True)
    freeze.add_argument("--allow-synthetic-smoke", action="store_true")
    freeze.set_defaults(func=cmd_freeze)

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
