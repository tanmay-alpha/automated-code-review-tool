"""Focused dataset-contract tests without a PostgreSQL dependency."""

from __future__ import annotations

import hashlib
from pathlib import Path

from app.taxonomy import load_taxonomy
from training.build_dataset import (
    AnnotationRow,
    CodeSampleRow,
    SampleReviewRow,
    build_artifacts,
    resolve_dataset_records,
)
from training.dataset_contract import DatasetRecord, LabelEvidence
from training.dataset_manifest import read_manifest
from training.group_split import build_split_plan, duplicate_components


def _record(
    sample_id: str,
    *,
    raw_hunk: str | None = None,
    group_key: str | None = None,
    added_code: str = "new",
) -> DatasetRecord:
    raw_hunk = raw_hunk or f"@@ -1 +1 @@\n-old-{sample_id}\n+new-{sample_id}"
    sample_parity = sum(ord(character) for character in sample_id) % 2
    labels = tuple(
        LabelEvidence(
            anti_pattern_id=label,
            state="positive" if (index + sample_parity) % 2 == 0 else "negative",
            trust_level="human_single",
            annotation_ids=(f"annotation-{sample_id}-{label}",),
        )
        for index, label in enumerate(load_taxonomy().trainable_ids())
    )
    return DatasetRecord(
        sample_id=sample_id,
        repository_id=f"repo-{sample_id}",
        pull_request_id=f"pr-{sample_id}",
        commit_sha="a" * 40,
        file_path="src/example.py",
        language="python",
        old_start=1,
        old_count=1,
        new_start=1,
        new_count=1,
        hunk_sha256=hashlib.sha256(raw_hunk.encode()).hexdigest(),
        content_sha256=hashlib.sha256(added_code.encode()).hexdigest(),
        group_key=group_key or f"group-{sample_id}",
        raw_hunk=raw_hunk,
        added_code=added_code,
        context_code="",
        repository_visibility="public",
        license_spdx="MIT",
        data_use_status="allowed_public",
        redaction_version="v1",
        taxonomy_version=load_taxonomy().version,
        labels=labels,
    )


def test_exact_duplicates_are_grouped_before_split() -> None:
    duplicate = "@@ -1 +1 @@\n-old\n+new"
    records = [
        _record("one", raw_hunk=duplicate),
        _record("two", raw_hunk=duplicate),
        _record("three", raw_hunk="@@ -2 +2 @@\n-a\n+b"),
    ]
    sample_splits, _, pairs = build_split_plan(records, seed=17)
    components, _ = duplicate_components(records)
    assert components["one"] == components["two"]
    assert sample_splits["one"] == sample_splits["two"]
    assert any(pair.kind == "exact" for pair in pairs)


def test_artifact_manifest_is_reproducible(tmp_path: Path) -> None:
    records = [_record(str(index)) for index in range(1, 5)]
    common = {
        "records": records,
        "build_findings": [],
        "dataset_name": "review-gold",
        "dataset_version": "1.0.0",
        "taxonomy_version": load_taxonomy().version,
        "source_git_sha": "b" * 40,
        "seed": 42,
        "created_at": "2026-08-11T00:00:00+00:00",
        "near_duplicate_threshold": 0.85,
    }
    first, first_report = build_artifacts(output_dir=tmp_path / "one", **common)
    second, second_report = build_artifacts(output_dir=tmp_path / "two", **common)
    assert first.manifest_sha256 == second.manifest_sha256
    assert first.samples_sha256 == second.samples_sha256
    assert first.splits_sha256 == second.splits_sha256
    assert first_report["critical_failures"] == 0
    assert second_report["critical_failures"] == 0
    assert read_manifest(tmp_path / "one" / "manifest.json") == first


def test_secret_quality_gate_blocks_escaped_content(tmp_path: Path) -> None:
    secret = 'api_key = "sk_live_this_is_not_safe"'
    _, report = build_artifacts(
        records=[_record("secret", raw_hunk=f"@@ -1 +1 @@\n+{secret}", added_code=secret)],
        build_findings=[],
        output_dir=tmp_path,
        dataset_name="review-gold",
        dataset_version="1.0.0",
        taxonomy_version=load_taxonomy().version,
        source_git_sha="b" * 40,
        seed=42,
        created_at="2026-08-11T00:00:00+00:00",
        near_duplicate_threshold=0.85,
    )
    assert report["critical_failures"] > 0
    assert "secret_escaped_redaction" in {
        finding["code"] for finding in report["findings"]
    }


def test_resolution_preserves_explicit_negative_provenance() -> None:
    taxonomy = load_taxonomy()
    positive_label, negative_label = taxonomy.trainable_ids()[:2]
    raw_hunk = "@@ -1 +1 @@\n-old\n+new"
    sample = CodeSampleRow(
        id="sample-1",
        repository_id="repo-1",
        pull_request_id="pr-1",
        commit_sha="c" * 40,
        file_path="src/a.py",
        language="python",
        old_start=1,
        old_count=1,
        new_start=1,
        new_count=1,
        hunk_sha256="d" * 64,
        content_sha256=hashlib.sha256(b"new").hexdigest(),
        group_key="repo-1:pr-1",
        raw_hunk=raw_hunk,
        added_code="new",
        context_code="",
        repository_visibility="public",
        license_spdx="MIT",
        data_use_status="allowed_public",
        redaction_version="v1",
    )
    annotations = {
        sample.id: [
            AnnotationRow(
                id="a-positive",
                code_sample_id=sample.id,
                anti_pattern_id=positive_label,
                label_state="positive",
                source="human",
                confidence=None,
                reviewer_user_id="reviewer",
                trust_level="human_single",
                resolution_state="active",
            )
        ]
    }
    reviews = {
        sample.id: [
            SampleReviewRow(
                id="review-1",
                code_sample_id=sample.id,
                reviewer_user_id="reviewer",
                review_status="complete",
                reviewed_label_ids=(negative_label,),
                clean_confirmed=True,
            )
        ]
    }
    records, findings = resolve_dataset_records([sample], annotations, reviews, taxonomy)
    assert not [finding for finding in findings if finding.severity == "critical"]
    assert records[0].positive_labels == (positive_label,)
    assert records[0].negative_labels == (negative_label,)
    negative = next(item for item in records[0].labels if item.state == "negative")
    assert negative.review_ids == ("review-1",)


def test_automated_annotation_is_not_promoted_to_gold() -> None:
    taxonomy = load_taxonomy()
    sample = _sample_with_policy("allowed_public")
    annotation = AnnotationRow(
        id="model-1",
        code_sample_id=sample.id,
        anti_pattern_id=taxonomy.trainable_ids()[0],
        label_state="positive",
        source="model",
        confidence=0.99,
        reviewer_user_id=None,
        trust_level="model",
        resolution_state="active",
    )
    records, findings = resolve_dataset_records(
        [sample], {sample.id: [annotation]}, {}, taxonomy
    )
    assert records == []
    assert any(item.code == "sample_without_gold_evidence" for item in findings)


def test_positive_annotation_conflicting_with_clean_review_is_rejected() -> None:
    taxonomy = load_taxonomy()
    label = taxonomy.trainable_ids()[0]
    sample = _sample_with_policy("allowed_public")
    annotation = AnnotationRow(
        id="positive",
        code_sample_id=sample.id,
        anti_pattern_id=label,
        label_state="positive",
        source="human",
        confidence=None,
        reviewer_user_id="reviewer",
        trust_level="human_single",
        resolution_state="active",
    )
    review = SampleReviewRow(
        id="clean",
        code_sample_id=sample.id,
        reviewer_user_id="reviewer",
        review_status="complete",
        reviewed_label_ids=(label,),
        clean_confirmed=True,
    )
    records, findings = resolve_dataset_records(
        [sample], {sample.id: [annotation]}, {sample.id: [review]}, taxonomy
    )
    assert records == []
    assert any(
        item.code == "annotation_clean_review_conflict" for item in findings
    )


def _sample_with_policy(policy: str) -> CodeSampleRow:
    raw_hunk = "@@ -1 +1 @@\n-old\n+new"
    return CodeSampleRow(
        id="sample-policy",
        repository_id="repo",
        pull_request_id="pr",
        commit_sha="e" * 40,
        file_path="a.py",
        language="python",
        old_start=1,
        old_count=1,
        new_start=1,
        new_count=1,
        hunk_sha256="f" * 64,
        content_sha256=hashlib.sha256(b"new").hexdigest(),
        group_key="repo:pr",
        raw_hunk=raw_hunk,
        added_code="new",
        context_code="",
        repository_visibility="public",
        license_spdx="MIT",
        data_use_status=policy,
        redaction_version="v1",
    )
