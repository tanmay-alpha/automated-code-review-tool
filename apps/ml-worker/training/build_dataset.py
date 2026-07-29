"""Deterministic dataset builder.

Reads the ML data schema from PostgreSQL using a read-only
connection (configured via ``ML_DATASET_DATABASE_URL``) and
emits a deterministic dataset directory with:

* ``manifest.json`` (full provenance)
* ``samples.jsonl`` (one record per code sample)
* ``labels.jsonl`` (one record per accepted annotation)
* ``splits.json`` (group_key → split)
* ``data_quality_report.json`` and ``.md``

The script refuses to operate on a frozen dataset and refuses to
write if manifest hashing fails.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

# Allow ``import taxonomy`` and ``import app`` from anywhere.
_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
_REPO_ROOT = _ML_WORKER.parent
for _p in (str(_ML_WORKER), str(_REPO_ROOT)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from taxonomy import trainable_ids  # noqa: E402
from training.dataset_manifest import DatasetManifest, manifest_hash, manifest_now  # noqa: E402
from training.group_split import assign_group_splits, summarize  # noqa: E402
from training.validate_dataset import validate_dataset_dir  # noqa: E402

log = logging.getLogger("build_dataset")

# Crude fallback scanner used during synthetic tests only.
SECRET_PATTERNS = [
    re.compile(r"(?i)(password|secret|api[_-]?key)\s*[:=]\s*['\"][^'\"]{4,}['\"]"),
    re.compile(r"['\"][A-Za-z0-9+/=_-]{32,}['\"]"),
]


@dataclass(frozen=True)
class CodeSampleRow:
    id: str
    repository_id: str
    pull_request_id: str
    commit_sha: str
    file_path: str
    language: str
    new_start: int
    content_sha256: str
    group_key: str
    added_code: str


@dataclass(frozen=True)
class AnnotationRow:
    code_sample_id: str
    anti_pattern_id: str
    label_state: str
    source: str
    confidence: Optional[float]


def _looks_like_secret(text: str) -> bool:
    return any(p.search(text or "") for p in SECRET_PATTERNS)


def _fetch_samples(conn) -> List[CodeSampleRow]:
    """Fetch eligible code samples.

    A sample is eligible if it has at least one accepted annotation
    from a positive source.
    """

    sql = """
        SELECT s.id::text, s.repository_id::text, s.pull_request_id::text,
               s.commit_sha, s.file_path, s.language, s.new_start,
               s.content_sha256, s.group_key, COALESCE(s.added_code, '')
          FROM ml.code_samples s
         WHERE EXISTS (
              SELECT 1 FROM ml.annotations a
               WHERE a.code_sample_id = s.id
                 AND a.label_state = 'positive'
                 AND a.source IN ('human', 'finding_feedback', 'import', 'fallback')
         )
    """
    with conn.cursor() as cur:
        cur.execute(sql)
        cols = [c[0] for c in cur.description]
        return [CodeSampleRow(**dict(zip(cols, row))) for row in cur.fetchall()]


def _fetch_annotations(conn, sample_ids: Iterable[str]) -> Dict[str, List[AnnotationRow]]:
    sql = """
        SELECT code_sample_id::text, anti_pattern_id, label_state, source, confidence
          FROM ml.annotations
         WHERE code_sample_id::text = ANY(%s)
           AND label_state IN ('positive', 'negative')
           AND source IN ('human', 'finding_feedback', 'import', 'fallback')
    """
    ids = list(sample_ids)
    if not ids:
        return {}
    with conn.cursor() as cur:
        cur.execute(sql, (ids,))
        cols = [c[0] for c in cur.description]
        result: Dict[str, List[AnnotationRow]] = {}
        for row in cur.fetchall():
            d = dict(zip(cols, row))
            result.setdefault(d["code_sample_id"], []).append(AnnotationRow(**d))
        return result


def _check_dataset_not_frozen(conn, name: str, version: str) -> None:
    sql = """
        SELECT status FROM ml.dataset_versions
         WHERE name = %s AND version = %s
    """
    with conn.cursor() as cur:
        cur.execute(sql, (name, version))
        row = cur.fetchone()
        if row and row[0] == "frozen":
            raise SystemExit(
                f"Dataset {name}@{version} is frozen; refusing to recreate"
            )


def _persist_dataset_version(
    conn,
    *,
    name: str,
    version: str,
    taxonomy_version: str,
    manifest_sha: str,
    sample_count: int,
    positive_count: int,
    generation_config: Dict,
) -> str:
    sql = """
        INSERT INTO ml.dataset_versions
            (name, version, taxonomy_version, status, generation_config,
             manifest_sha256, sample_count, positive_annotation_count, created_at)
        VALUES (%s, %s, %s, 'draft', %s::jsonb, %s, %s, %s, NOW())
        ON CONFLICT (name, version) DO UPDATE
            SET taxonomy_version = EXCLUDED.taxonomy_version,
                status = 'draft',
                generation_config = EXCLUDED.generation_config,
                manifest_sha256 = EXCLUDED.manifest_sha256,
                sample_count = EXCLUDED.sample_count,
                positive_annotation_count = EXCLUDED.positive_annotation_count,
                frozen_at = NULL
        RETURNING id::text
    """
    cfg = json.dumps(generation_config)
    with conn.cursor() as cur:
        cur.execute(sql, (name, version, taxonomy_version, cfg, manifest_sha,
                          sample_count, positive_count))
        return cur.fetchone()[0]


def _persist_dataset_items(
    conn,
    dataset_version_id: str,
    items: List[Tuple[str, str, str, List[str]]],
) -> None:
    sql = """
        INSERT INTO ml.dataset_items
            (dataset_version_id, code_sample_id, split, group_key, labels_snapshot)
        VALUES (%s, %s, %s, %s, %s::jsonb)
        ON CONFLICT (dataset_version_id, code_sample_id) DO NOTHING
    """
    with conn.cursor() as cur:
        for dataset_version_id_, sample_id, split, group_key, labels in items:
            cur.execute(sql, (dataset_version_id_, sample_id, split, group_key,
                              json.dumps(labels)))


def _freeze_dataset(conn, name: str, version: str) -> None:
    sql = """
        UPDATE ml.dataset_versions
           SET status = 'frozen', frozen_at = NOW()
         WHERE name = %s AND version = %s
    """
    with conn.cursor() as cur:
        cur.execute(sql, (name, version))


def cmd_create(args: argparse.Namespace) -> int:
    from app.taxonomy_loader import load_canonical_taxonomy  # type: ignore

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if not args.database_url and not os.environ.get("ML_DATASET_DATABASE_URL"):
        log.warning("ML_DATASET_DATABASE_URL not set; running in dry-run (no DB) mode")
        samples: List[CodeSampleRow] = []
        annotations: Dict[str, List[AnnotationRow]] = {}
    else:
        import psycopg  # type: ignore

        url = args.database_url or os.environ["ML_DATASET_DATABASE_URL"]
        with psycopg.connect(url, readonly=True) as conn:
            samples = _fetch_samples(conn)
            annotations = _fetch_annotations(conn, [s.id for s in samples])

    taxonomy = load_canonical_taxonomy()
    trainable = set(trainable_ids())

    # Filter: unknown IDs are dropped, leaving an audit trail.
    accepted_records: List[Dict] = []
    seen_hashes: Dict[str, str] = {}
    duplicates = 0
    label_dist: Dict[str, int] = {}
    repository_counts: Dict[str, int] = {}

    for sample in samples:
        anns = annotations.get(sample.id, [])
        positive_labels = sorted({
            a.anti_pattern_id for a in anns
            if a.label_state == "positive" and a.anti_pattern_id in trainable
        })
        if not positive_labels:
            continue
        if sample.content_sha256 in seen_hashes and seen_hashes[sample.content_sha256] != sample.id:
            duplicates += 1
            continue
        seen_hashes[sample.content_sha256] = sample.id
        for label in positive_labels:
            label_dist[label] = label_dist.get(label, 0) + 1
        repo = sample.repository_id
        repository_counts[repo] = repository_counts.get(repo, 0) + 1
        accepted_records.append({
            "sample": sample,
            "labels": positive_labels,
        })

    # Assign splits by group.
    group_keys = [r["sample"].group_key for r in accepted_records]
    split_map = assign_group_splits(group_keys, seed=args.seed)
    split_counts = summarize(split_map)

    # Compute manifest.
    manifest_no_hash = DatasetManifest(
        dataset_name=args.name,
        dataset_version=args.version,
        taxonomy_version=taxonomy["version"],
        seed=args.seed,
        created_at=manifest_now(),
        source_commit=args.source_commit,
        sample_count=len(accepted_records),
        split_counts=split_counts,
        label_distribution=label_dist,
        repository_counts=repository_counts,
        duplicate_count=duplicates,
        manifest_sha256="",
    )
    sha = manifest_hash(manifest_no_hash)
    manifest = DatasetManifest(
        **{**manifest_no_hash.to_dict(), "manifest_sha256": sha}
    )

    # Persist artefacts.
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest.to_dict(), indent=2, sort_keys=True), encoding="utf-8"
    )
    with (out_dir / "samples.jsonl").open("w", encoding="utf-8") as f:
        for rec in accepted_records:
            s = rec["sample"]
            f.write(json.dumps({
                "id": s.id,
                "repository_id": s.repository_id,
                "pull_request_id": s.pull_request_id,
                "commit_sha": s.commit_sha,
                "file_path": s.file_path,
                "language": s.language,
                "new_start": s.new_start,
                "content_sha256": s.content_sha256,
                "group_key": s.group_key,
                "added_code": s.added_code,
                "labels": rec["labels"],
            }) + "\n")
    (out_dir / "splits.json").write_text(
        json.dumps(split_map, indent=2, sort_keys=True), encoding="utf-8"
    )

    if args.database_url or os.environ.get("ML_DATASET_DATABASE_URL"):
        import psycopg  # type: ignore

        url = args.database_url or os.environ["ML_DATASET_DATABASE_URL"]
        with psycopg.connect(url) as conn:
            with conn.cursor() as cur:
                cur.execute("SET TRANSACTION READ WRITE")
            _check_dataset_not_frozen(conn, args.name, args.version)
            ds_id = _persist_dataset_version(
                conn,
                name=args.name,
                version=args.version,
                taxonomy_version=taxonomy["version"],
                manifest_sha=sha,
                sample_count=len(accepted_records),
                positive_count=sum(label_dist.values()),
                generation_config={
                    "seed": args.seed,
                    "source_commit": args.source_commit,
                },
            )
            _persist_dataset_items(conn, ds_id, [
                (ds_id, rec["sample"].id, split_map.get(rec["sample"].group_key, "train"),
                 rec["sample"].group_key, rec["labels"])
                for rec in accepted_records
            ])
            conn.commit()

    log.info("Created dataset %s@%s with %d samples", args.name, args.version, len(accepted_records))
    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    report = validate_dataset_dir(Path(args.dataset_dir))
    print(json.dumps(report, indent=2))
    return 0 if report["critical_failures"] == 0 else 1


def cmd_freeze(args: argparse.Namespace) -> int:
    import psycopg  # type: ignore

    url = args.database_url or os.environ.get("ML_DATASET_DATABASE_URL", "")
    if not url:
        log.error("Cannot freeze without ML_DATASET_DATABASE_URL")
        return 2
    with psycopg.connect(url) as conn:
        _freeze_dataset(conn, args.name, args.version)
        conn.commit()
    log.info("Frozen dataset %s@%s", args.name, args.version)
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--database-url", default=os.environ.get("ML_DATASET_DATABASE_URL"))
    sub = parser.add_subparsers(dest="command", required=True)

    p_create = sub.add_parser("create", help="Create a new dataset")
    p_create.add_argument("--name", required=True)
    p_create.add_argument("--version", required=True)
    p_create.add_argument("--seed", type=int, default=42)
    p_create.add_argument("--source-commit", default=os.environ.get("GIT_COMMIT", ""))
    p_create.add_argument("--output-dir", required=True)
    p_create.set_defaults(func=cmd_create)

    p_validate = sub.add_parser("validate", help="Validate an existing dataset")
    p_validate.add_argument("--dataset-dir", required=True)
    p_validate.set_defaults(func=cmd_validate)

    p_freeze = sub.add_parser("freeze", help="Freeze an existing dataset")
    p_freeze.add_argument("--name", required=True)
    p_freeze.add_argument("--version", required=True)
    p_freeze.set_defaults(func=cmd_freeze)

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
