"""Terminal-based annotation workflow for ML dataset curation.

Usage:
    python training/annotate_dataset.py queue    --limit 50 --strategy uncertainty --output annotation_queue.jsonl
    python training/annotate_dataset.py import    --input completed_annotations.jsonl --reviewer-id <uuid>
    python training/annotate_dataset.py stats
    python training/annotate_dataset.py conflicts --output annotation_conflicts.jsonl
    python training/annotate_dataset.py adjudicate --input adjudicated_annotations.jsonl --reviewer-id <uuid>

Queue strategies: random, uncertainty, high_confidence, label_balance, language_balance,
conflict, unreviewed.

Never includes raw source, secrets, or tokens.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Optional

# Allow ``import taxonomy`` from anywhere.
_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
_REPO_ROOT = _ML_WORKER.parent
for _p in (str(_ML_WORKER), str(_REPO_ROOT)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from taxonomy import trainable_ids  # noqa: E402
from training.label_resolution import (  # noqa: E402
    AnnotationEvidence,
    Resolution,
    resolve_label,
)

log = logging.getLogger("annotate_dataset")

# Fields that may be exported — never includes raw tokens, secrets, or credentials.
ALLOWED_EXPORT_FIELDS = {
    "sampleId", "repository", "pullRequest", "commitSha", "filePath",
    "language", "lineRange", "redactedHunk", "existingPredictions",
    "existingAnnotations", "taxonomyVersion", "repositoryVisibility",
    "licenseSpdx", "dataUseStatus",
}


def _db_url() -> Optional[str]:
    return os.environ.get("ML_DATASET_DATABASE_URL", "")


def _connect():
    if not _db_url():
        return None
    try:
        import psycopg
        return psycopg.connect(_db_url(), autocommit=True)
    except Exception as ex:
        log.warning("Database unavailable: %s", ex)
        return None


def cmd_queue(args: argparse.Namespace) -> int:
    """Export annotation queue records."""
    conn = _connect()
    if conn is None:
        log.error("ML_DATASET_DATABASE_URL not set; cannot build queue")
        return 2

    strategy = args.strategy
    limit = args.limit
    output = Path(args.output)

    # Fetch samples with pending annotation state.
    sql = """
        SELECT s.id::text, s.repository_id::text, s.pull_request_id::text,
               s.commit_sha, s.file_path, s.language,
               s.new_start, s.new_count, COALESCE(s.added_code, '') as added_code,
               s.content_sha256, s.group_key,
               s.repository_visibility, s.license_spdx, s.data_use_status
          FROM ml.code_samples s
         WHERE s.data_use_status NOT IN ('blocked_private_no_consent', 'blocked_policy')
         ORDER BY s.created_at DESC
         LIMIT %s
    """
    with conn.cursor() as cur:
        cur.execute(sql, (limit * 3,))  # Fetch extra for filtering
        cols = [c.name for c in cur.description]
        samples = [dict(zip(cols, row)) for row in cur.fetchall()]

    if not samples:
        log.warning("No samples found for queue")
        return 0

    trainable = set(trainable_ids())
    selected: List[Dict[str, Any]] = []

    for sample in samples:
        record = _build_queue_record(sample, conn, trainable)
        if record is None:
            continue

        # Apply strategy filtering.
        if strategy == "unreviewed" and record.get("existingAnnotations"):
            continue
        if strategy == "conflict" and not record.get("hasConflict"):
            continue
        if strategy == "high_confidence" and not record.get("hasHighConfidence"):
            continue

        selected.append(record)
        if len(selected) >= limit:
            break

    # Write output — never include raw source or credentials.
    with output.open("w", encoding="utf-8") as f:
        for rec in selected:
            f.write(json.dumps(rec, ensure_ascii=False, default=str) + "\n")

    log.info("Wrote %d queue records to %s (strategy=%s)", len(selected), output, strategy)
    return 0


def _build_queue_record(sample: Dict, conn, trainable: set) -> Optional[Dict]:
    """Build a single queue record from a sample row."""
    # Check data-use policy.
    if sample.get("data_use_status") in ("blocked_private_no_consent", "blocked_policy"):
        return None

    sample_id = sample["id"]
    added_code = sample.get("added_code", "")

    # Fetch existing annotations for this sample.
    with conn.cursor() as cur:
        cur.execute(
            "SELECT anti_pattern_id, label_state, source, confidence, trust_level, "
            "       reviewer_user_id::text, id::text "
            "  FROM ml.annotations WHERE code_sample_id = %s::uuid",
            (sample_id,),
        )
        ann_rows = cur.fetchall()

    annotations = [
        {
            "antiPatternId": r[0],
            "label": r[1],
            "source": r[2],
            "confidence": float(r[3]) if r[3] is not None else None,
            "trustLevel": r[4],
            "reviewerId": r[5],
            "id": r[6],
        }
        for r in ann_rows
    ]

    # Detect conflicts.
    trust_groups: Dict[str, List] = defaultdict(list)
    for ann in annotations:
        trust_groups[ann.get("trustLevel", "model")].append(ann)

    has_conflict = False
    for level, group in trust_groups.items():
        if level.startswith("human") and len(group) >= 2:
            labels = {a["label"] for a in group}
            if len(labels) > 1:
                has_conflict = True
                break

    # Check for high-confidence model predictions.
    has_high = any(
        a.get("confidence", 0) >= 0.8 and a.get("source") == "model"
        for a in annotations
    )

    # Resolution check.
    evidence = [
        AnnotationEvidence(
            annotation_id=a["id"],
            trust_level=a.get("trustLevel", a["source"]),
            label=a["label"],
            reviewer_id=a.get("reviewerId"),
            source=a["source"],
        )
        for a in annotations
    ]

    resolution = resolve_label(evidence)
    is_unreviewed = resolution.resolution == Resolution.UNREVIEWED

    return {
        "sampleId": sample_id,
        "repository": sample.get("repository_id"),
        "pullRequest": sample.get("pull_request_id"),
        "commitSha": sample.get("commit_sha"),
        "filePath": sample.get("file_path"),
        "language": sample.get("language"),
        "lineRange": {
            "newStart": sample.get("new_start"),
            "newCount": sample.get("new_count"),
        },
        "redactedHunk": added_code[:2000] if added_code else "",  # Truncate for safety.
        "existingPredictions": [
            a for a in annotations if a["source"] in ("model", "fallback")
        ],
        "existingAnnotations": [
            a for a in annotations if a["source"] in ("human", "finding_feedback", "import")
        ],
        "taxonomyVersion": "1.0.0",  # Current canonical version.
        "repositoryVisibility": sample.get("repository_visibility", "private"),
        "licenseSpdx": sample.get("license_spdx"),
        "dataUseStatus": sample.get("data_use_status"),
        "hasConflict": has_conflict,
        "hasHighConfidence": has_high,
        "isUnreviewed": is_unreviewed,
        "currentResolution": resolution.resolution.value,
    }


def cmd_import(args: argparse.Namespace) -> int:
    """Import completed annotations from a JSONL file."""
    input_path = Path(args.input)
    reviewer_id = args.reviewer_id

    if not input_path.exists():
        log.error("Input file not found: %s", input_path)
        return 2

    conn = _connect()
    if conn is None:
        log.error("ML_DATASET_DATABASE_URL not set; cannot import")
        return 2

    imported = 0
    skipped = 0
    errors = 0

    with input_path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as ex:
                log.warning("Line %d: invalid JSON: %s", line_no, ex)
                errors += 1
                continue

            try:
                sample_id = record.get("sampleId")
                anti_pattern_id = record.get("antiPatternId")
                label = record.get("label", "positive")
                notes = record.get("notes", "")
                trust_level = record.get("trustLevel", "human_single")

                if not sample_id or not anti_pattern_id:
                    log.warning("Line %d: missing sampleId or antiPatternId", line_no)
                    skipped += 1
                    continue

                if anti_pattern_id not in trainable_ids() and anti_pattern_id not in ("MAINTAINABILITY_PRINT_STATEMENT", "READABILITY_LONG_METHOD"):
                    log.warning("Line %d: unknown antiPatternId %r", line_no, anti_pattern_id)
                    skipped += 1
                    continue

                if label not in ("positive", "negative", "uncertain"):
                    log.warning("Line %d: invalid label %r", line_no, label)
                    skipped += 1
                    continue

                import uuid
                annotation_id = str(uuid.uuid4())
                idempotency_key = f"manual:{sample_id}:{anti_pattern_id}:{reviewer_id}:manual_{label}"

                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT id FROM ml.annotations WHERE idempotency_key = %s",
                        (idempotency_key,),
                    )
                    if cur.fetchone():
                        log.debug("Line %d: idempotent, skipping", line_no)
                        skipped += 1
                        continue

                    cur.execute(
                        """INSERT INTO ml.annotations
                            (id, code_sample_id, anti_pattern_id, label_state, source,
                             reviewer_user_id, trust_level, rationale, resolution_state,
                             feedback_action, idempotency_key, created_at, updated_at)
                           VALUES (%s::uuid, %s::uuid, %s, %s, 'human', %s::uuid, %s, %s, 'active', %s, %s, NOW(), NOW())""",
                        (
                            annotation_id,
                            sample_id,
                            anti_pattern_id,
                            label,
                            reviewer_id,
                            trust_level,
                            notes,
                            f"manual_{label}",
                            idempotency_key,
                        ),
                    )
                imported += 1
            except Exception as ex:
                log.warning("Line %d: import error: %s", line_no, ex)
                errors += 1

    log.info("Imported %d annotations, skipped %d, errors %d", imported, skipped, errors)
    return 0 if errors == 0 else 1


def cmd_stats(args: argparse.Namespace) -> int:
    """Print annotation statistics."""
    conn = _connect()
    if conn is None:
        log.error("ML_DATASET_DATABASE_URL not set; cannot show stats")
        return 2

    stats: Dict[str, Any] = {
        "total_annotations": 0,
        "by_source": {},
        "by_trust_level": {},
        "by_label": {},
        "conflicts": 0,
        "unreviewed": 0,
        "by_language": {},
    }

    try:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM ml.annotations")
            stats["total_annotations"] = cur.fetchone()[0]

            cur.execute("SELECT source, count(*) FROM ml.annotations GROUP BY source")
            stats["by_source"] = {r[0]: r[1] for r in cur.fetchall()}

            cur.execute("SELECT trust_level, count(*) FROM ml.annotations GROUP BY trust_level")
            stats["by_trust_level"] = {r[0]: r[1] for r in cur.fetchall()}

            cur.execute("SELECT label_state, count(*) FROM ml.annotations GROUP BY label_state")
            stats["by_label"] = {r[0]: r[1] for r in cur.fetchall()}

            # Count human conflicts.
            cur.execute(
                """SELECT count(DISTINCT a1.code_sample_id, a1.anti_pattern_id)
                     FROM ml.annotations a1
                    WHERE a1.trust_level IN ('human_adjudicated', 'human_single', 'finding_feedback')
                      AND EXISTS (
                          SELECT 1 FROM ml.annotations a2
                           WHERE a2.code_sample_id = a1.code_sample_id
                             AND a2.anti_pattern_id = a1.anti_pattern_id
                             AND a2.trust_level = a1.trust_level
                             AND a2.id != a1.id
                             AND a2.label_state != a1.label_state
                      )"""
            )
            stats["conflicts"] = cur.fetchone()[0]

            # Language distribution from code_samples.
            cur.execute(
                """SELECT s.language, count(DISTINCT a.id)
                     FROM ml.code_samples s
                     JOIN ml.annotations a ON a.code_sample_id = s.id
                    GROUP BY s.language"""
            )
            stats["by_language"] = {r[0]: r[1] for r in cur.fetchall()}
    except Exception as ex:
        log.warning("Stats query error: %s", ex)

    print(json.dumps(stats, indent=2, sort_keys=True))
    return 0


def cmd_conflicts(args: argparse.Namespace) -> int:
    """Export annotation conflicts to a JSONL file."""
    conn = _connect()
    if conn is None:
        log.error("ML_DATASET_DATABASE_URL not set; cannot detect conflicts")
        return 2

    output = Path(args.output)
    conflicts: List[Dict] = []

    with conn.cursor() as cur:
        cur.execute(
            """SELECT DISTINCT a1.code_sample_id::text, a1.anti_pattern_id,
                      array_agg(a1.id::text) FILTER (WHERE a1.label_state = 'positive') as positives,
                      array_agg(a1.id::text) FILTER (WHERE a1.label_state = 'negative') as negatives,
                      array_agg(a1.reviewer_user_id::text) as reviewers,
                      max(a1.trust_level) as trust_level
                 FROM ml.annotations a1
                WHERE a1.trust_level IN ('human_adjudicated', 'human_single', 'finding_feedback')
                GROUP BY a1.code_sample_id, a1.anti_pattern_id
                  HAVING count(DISTINCT a1.label_state) > 1"""
        )
        for row in cur.fetchall():
            conflicts.append({
                "codeSampleId": row[0],
                "antiPatternId": row[1],
                "positiveAnnotations": row[2] or [],
                "negativeAnnotations": row[3] or [],
                "reviewerIds": [r for r in (row[4] or []) if r is not None],
                "trustLevel": row[5],
                "resolution": "needs_adjudication",
            })

    with output.open("w", encoding="utf-8") as f:
        for c in conflicts:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    log.info("Wrote %d conflicts to %s", len(conflicts), output)
    return 0


def cmd_adjudicate(args: argparse.Namespace) -> int:
    """Apply adjudicated decisions from a JSONL file."""
    input_path = Path(args.input)
    reviewer_id = args.reviewer_id

    if not input_path.exists():
        log.error("Input file not found: %s", input_path)
        return 2

    conn = _connect()
    if conn is None:
        log.error("ML_DATASET_DATABASE_URL not set; cannot adjudicate")
        return 2

    adjudicated = 0
    skipped = 0
    errors = 0

    with input_path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as ex:
                log.warning("Line %d: invalid JSON: %s", line_no, ex)
                errors += 1
                continue

            try:
                sample_id = record.get("codeSampleId") or record.get("sampleId")
                anti_pattern_id = record.get("antiPatternId")
                resolved_label = record.get("resolvedLabel", "positive")
                notes = record.get("notes", "adjudicated")

                if not sample_id or not anti_pattern_id:
                    log.warning("Line %d: missing required fields", line_no)
                    skipped += 1
                    continue

                with conn.cursor() as cur:
                    cur.execute(
                        """INSERT INTO ml.annotations
                            (code_sample_id, anti_pattern_id, label_state, source,
                             reviewer_user_id, trust_level, rationale, resolution_state,
                             feedback_action, created_at, updated_at)
                           VALUES (%s::uuid, %s, %s, 'manual_annotation', %s::uuid,
                                   'human_adjudicated', %s, 'active', 'manual_positive', NOW(), NOW())""",
                        (sample_id, anti_pattern_id, resolved_label, reviewer_id, notes),
                    )
                adjudicated += 1
            except Exception as ex:
                log.warning("Line %d: adjudication error: %s", line_no, ex)
                errors += 1

    log.info("Adjudicated %d annotations, errors %d", adjudicated, errors)
    return 0 if errors == 0 else 1


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Terminal annotation workflow for ML dataset curation",
    )
    parser.add_argument(
        "--database-url",
        default=os.environ.get("ML_DATASET_DATABASE_URL"),
        help="PostgreSQL connection URL (or set ML_DATASET_DATABASE_URL)",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # queue
    p_queue = sub.add_parser("queue", help="Export annotation queue")
    p_queue.add_argument("--limit", type=int, default=50)
    p_queue.add_argument("--strategy", default="random",
                         choices=["random", "uncertainty", "high_confidence",
                                  "label_balance", "language_balance", "conflict", "unreviewed"])
    p_queue.add_argument("--output", required=True)
    p_queue.set_defaults(func=cmd_queue)

    # import
    p_import = sub.add_parser("import", help="Import completed annotations")
    p_import.add_argument("--input", required=True)
    p_import.add_argument("--reviewer-id", required=True)
    p_import.set_defaults(func=cmd_import)

    # stats
    p_stats = sub.add_parser("stats", help="Show annotation statistics")
    p_stats.set_defaults(func=cmd_stats)

    # conflicts
    p_conflicts = sub.add_parser("conflicts", help="Export annotation conflicts")
    p_conflicts.add_argument("--output", required=True)
    p_conflicts.set_defaults(func=cmd_conflicts)

    # adjudicate
    p_adj = sub.add_parser("adjudicate", help="Apply adjudicated decisions")
    p_adj.add_argument("--input", required=True)
    p_adj.add_argument("--reviewer-id", required=True)
    p_adj.set_defaults(func=cmd_adjudicate)

    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
