"""Export safe annotation queues and import deduplicated human decisions."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from app.secret_redaction import redact_secrets
from app.taxonomy import load_taxonomy
from training.dataset_contract import ALLOWED_DATA_USE

LABEL_STATES = frozenset({"positive", "negative", "uncertain"})


def annotation_idempotency_key(record: dict[str, Any], *, adjudicated: bool = False) -> str:
    """Return a stable key for one reviewer decision without embedding PII."""
    payload = {
        "anti_pattern_id": record["anti_pattern_id"],
        "label_state": record["label_state"],
        "reviewer_id": record["reviewer_id"],
        "sample_id": record["sample_id"],
        "type": "adjudication" if adjudicated else "annotation",
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return f"annotation:v1:{hashlib.sha256(encoded).hexdigest()}"


def normalize_annotation(raw: dict[str, Any], trainable_ids: set[str]) -> dict[str, Any]:
    """Validate external JSON and return the database field vocabulary."""
    anti_pattern_id = str(raw.get("antiPatternId", "")).strip()
    if anti_pattern_id not in trainable_ids:
        raise ValueError(f"antiPatternId is not a trainable taxonomy ID: {anti_pattern_id!r}")
    label_state = str(raw.get("label", "")).strip().lower()
    if label_state not in LABEL_STATES:
        raise ValueError("label must be positive, negative, or uncertain")
    sample_id = str(raw.get("sampleId", "")).strip()
    reviewer_id = str(raw.get("reviewerId", "")).strip()
    if not sample_id or not reviewer_id:
        raise ValueError("sampleId and reviewerId are required")
    line_start = int(raw.get("lineStart", 1))
    line_end = int(raw.get("lineEnd", line_start))
    if line_start < 1 or line_end < line_start:
        raise ValueError("lineStart/lineEnd must be a positive inclusive range")
    return {
        "sample_id": sample_id,
        "anti_pattern_id": anti_pattern_id,
        "label_state": label_state,
        "reviewer_id": reviewer_id,
        "line_start": line_start,
        "line_end": line_end,
        "notes": str(raw.get("notes", "")).strip() or None,
    }


def _read_jsonl(path: Path) -> Iterable[tuple[int, dict[str, Any]]]:
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{line_number}: expected a JSON object")
        yield line_number, value


def _connect(database_url: str) -> Any:
    import psycopg

    return psycopg.connect(database_url)


def _require_database_url(args: argparse.Namespace) -> str:
    value = args.database_url or os.environ.get("ML_DATASET_DATABASE_URL")
    if not value:
        raise SystemExit("ML_DATASET_DATABASE_URL or --database-url is required")
    return str(value)


def _queue_rows(conn: Any) -> list[dict[str, Any]]:
    with conn.cursor() as cursor:
        cursor.execute(
            """
            SELECT s.id::text AS sample_id, s.language, s.raw_hunk,
                   s.new_start, s.new_count, s.data_use_status,
                   COALESCE(r.review_status, 'unreviewed') AS review_status
              FROM ml.code_samples s
              LEFT JOIN ml.sample_reviews r ON r.code_sample_id = s.id
             WHERE s.data_use_status = ANY(%s)
             ORDER BY s.id
            """,
            (sorted(ALLOWED_DATA_USE),),
        )
        names = [getattr(item, "name", item[0]) for item in cursor.description]
        return [dict(zip(names, row)) for row in cursor.fetchall()]


def cmd_queue(args: argparse.Namespace) -> int:
    taxonomy = load_taxonomy()
    with _connect(_require_database_url(args)) as conn:
        rows = _queue_rows(conn)
        with conn.cursor() as cursor:
            cursor.execute(
                """
                SELECT code_sample_id::text, anti_pattern_id, label_state,
                       source, trust_level, resolution_state
                  FROM ml.annotations
                 WHERE resolution_state = 'active'
                 ORDER BY code_sample_id, anti_pattern_id, created_at, id
                """
            )
            annotations = list(cursor.fetchall())

    by_sample: dict[str, list[dict[str, str]]] = defaultdict(list)
    for sample_id, anti_pattern_id, state, source, trust, resolution in annotations:
        by_sample[str(sample_id)].append(
            {
                "antiPatternId": str(anti_pattern_id),
                "label": str(state),
                "source": str(source),
                "trustLevel": str(trust),
                "resolutionState": str(resolution),
            }
        )

    payloads: list[dict[str, Any]] = []
    for row in rows:
        safe = redact_secrets(str(row["raw_hunk"]))
        payloads.append(
            {
                "sampleId": str(row["sample_id"]),
                "language": str(row["language"]),
                "rawHunk": safe.text,
                "lineStart": int(row["new_start"]),
                "lineEnd": max(
                    int(row["new_start"]),
                    int(row["new_start"]) + max(0, int(row["new_count"]) - 1),
                ),
                "reviewStatus": str(row["review_status"]),
                "taxonomyVersion": taxonomy.version,
                "allowedLabels": taxonomy.trainable_ids(),
                "activeAnnotations": by_sample.get(str(row["sample_id"]), []),
            }
        )
    if args.strategy == "conflict":
        payloads = [
            item
            for item in payloads
            if any(
                len({a["label"] for a in item["activeAnnotations"] if a["antiPatternId"] == label}) > 1
                for label in taxonomy.trainable_ids()
            )
        ]
    elif args.strategy == "unreviewed":
        payloads = [item for item in payloads if item["reviewStatus"] == "unreviewed"]
    elif args.strategy == "language_balance":
        counts = Counter(item["language"] for item in payloads)
        payloads.sort(key=lambda item: (counts[item["language"]], item["sampleId"]))
    payloads = payloads[: args.limit]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "".join(json.dumps(item, sort_keys=True) + "\n" for item in payloads),
        encoding="utf-8",
    )
    return 0


def _sample_policy_and_range(cursor: Any, sample_id: str) -> tuple[str, int, int] | None:
    cursor.execute(
        """
        SELECT data_use_status, new_start,
               GREATEST(new_start, new_start + GREATEST(new_count - 1, 0))
          FROM ml.code_samples
         WHERE id = %s::uuid
        """,
        (sample_id,),
    )
    row = cursor.fetchone()
    return None if row is None else (str(row[0]), int(row[1]), int(row[2]))


def cmd_import(args: argparse.Namespace) -> int:
    trainable = set(load_taxonomy().trainable_ids())
    accepted = 0
    skipped = 0
    with _connect(_require_database_url(args)) as conn, conn.cursor() as cursor:
        for line_number, raw in _read_jsonl(Path(args.input)):
            try:
                record = normalize_annotation(raw, trainable)
                sample = _sample_policy_and_range(cursor, record["sample_id"])
                if sample is None:
                    raise ValueError("sampleId does not exist")
                policy, first_line, last_line = sample
                if policy not in ALLOWED_DATA_USE:
                    raise ValueError(f"sample policy {policy!r} forbids annotation export use")
                if record["line_start"] < first_line or record["line_end"] > last_line:
                    raise ValueError(f"line range must be within [{first_line}, {last_line}]")
                key = annotation_idempotency_key(record)
                cursor.execute(
                    """
                    INSERT INTO ml.annotations
                        (code_sample_id, anti_pattern_id, label_state, line_start,
                         line_end, source, reviewer_user_id, rationale,
                         feedback_action, idempotency_key, trust_level,
                         resolution_state)
                    VALUES (%s::uuid, %s, %s, %s, %s, 'human', %s::uuid, %s,
                            %s, %s, 'human_single', 'active')
                    ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL
                    DO NOTHING
                    """,
                    (
                        record["sample_id"],
                        record["anti_pattern_id"],
                        record["label_state"],
                        record["line_start"],
                        record["line_end"],
                        record["reviewer_id"],
                        record["notes"],
                        f"manual_{record['label_state']}",
                        key,
                    ),
                )
                accepted += int(cursor.rowcount == 1)
                skipped += int(cursor.rowcount == 0)
                cursor.execute(
                    """
                    SELECT count(DISTINCT label_state)
                      FROM ml.annotations
                     WHERE code_sample_id=%s::uuid AND anti_pattern_id=%s
                       AND resolution_state='active'
                       AND label_state IN ('positive', 'negative')
                    """,
                    (record["sample_id"], record["anti_pattern_id"]),
                )
                if cursor.fetchone()[0] > 1:
                    cursor.execute(
                        """
                        INSERT INTO ml.sample_reviews
                            (code_sample_id, reviewer_user_id, review_status)
                        VALUES (%s::uuid, %s::uuid, 'needs_adjudication')
                        ON CONFLICT (code_sample_id, reviewer_user_id)
                        WHERE reviewer_user_id IS NOT NULL
                        DO UPDATE SET review_status='needs_adjudication', updated_at=NOW()
                        """,
                        (record["sample_id"], record["reviewer_id"]),
                    )
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError(f"{args.input}:{line_number}: {exc}") from exc
        conn.commit()
    print(json.dumps({"inserted": accepted, "deduplicated": skipped}, sort_keys=True))
    return 0


def cmd_conflicts(args: argparse.Namespace) -> int:
    with _connect(_require_database_url(args)) as conn, conn.cursor() as cursor:
        cursor.execute(
            """
            SELECT code_sample_id::text, anti_pattern_id,
                   array_agg(id::text ORDER BY created_at, id) AS annotation_ids
              FROM ml.annotations
             WHERE resolution_state='active'
               AND label_state IN ('positive', 'negative')
             GROUP BY code_sample_id, anti_pattern_id
            HAVING count(DISTINCT label_state) > 1
             ORDER BY code_sample_id, anti_pattern_id
            """
        )
        rows = [
            {"sampleId": str(row[0]), "antiPatternId": str(row[1]), "annotationIds": list(row[2])}
            for row in cursor.fetchall()
        ]
    print(json.dumps(rows, indent=2, sort_keys=True))
    return 0


def cmd_adjudicate(args: argparse.Namespace) -> int:
    trainable = set(load_taxonomy().trainable_ids())
    with _connect(_require_database_url(args)) as conn, conn.cursor() as cursor:
        for line_number, raw in _read_jsonl(Path(args.input)):
            try:
                normalized_raw = dict(raw)
                normalized_raw["label"] = raw.get("resolvedLabel")
                record = normalize_annotation(normalized_raw, trainable)
                if record["label_state"] == "uncertain":
                    raise ValueError("adjudication must resolve to positive or negative")
                sample = _sample_policy_and_range(cursor, record["sample_id"])
                if sample is None or sample[0] not in ALLOWED_DATA_USE:
                    raise ValueError("sample is missing or not approved for ML use")
                if record["line_start"] < sample[1] or record["line_end"] > sample[2]:
                    raise ValueError(f"line range must be within [{sample[1]}, {sample[2]}]")
                key = annotation_idempotency_key(record, adjudicated=True)
                cursor.execute(
                    "SELECT 1 FROM ml.annotations WHERE idempotency_key=%s",
                    (key,),
                )
                if cursor.fetchone() is not None:
                    continue
                cursor.execute(
                    """
                    SELECT id::text
                      FROM ml.annotations
                     WHERE code_sample_id=%s::uuid AND anti_pattern_id=%s
                       AND resolution_state='active'
                     ORDER BY created_at DESC, id DESC
                    """,
                    (record["sample_id"], record["anti_pattern_id"]),
                )
                active_ids = [str(row[0]) for row in cursor.fetchall()]
                if not active_ids:
                    raise ValueError("no active evidence exists to adjudicate")
                cursor.execute(
                    """
                    UPDATE ml.annotations SET resolution_state='superseded', updated_at=NOW()
                     WHERE id = ANY(%s::uuid[])
                    """,
                    (active_ids,),
                )
                cursor.execute(
                    """
                    INSERT INTO ml.annotations
                        (code_sample_id, anti_pattern_id, label_state, line_start,
                         line_end, source, reviewer_user_id, rationale,
                         feedback_action, idempotency_key, trust_level,
                         resolution_state, supersedes_annotation_id)
                    VALUES (%s::uuid, %s, %s, %s, %s, 'human', %s::uuid, %s,
                            %s, %s, 'human_adjudicated', 'active', %s::uuid)
                    ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL
                    DO NOTHING
                    """,
                    (
                        record["sample_id"], record["anti_pattern_id"],
                        record["label_state"], record["line_start"],
                        record["line_end"], record["reviewer_id"], record["notes"],
                        f"manual_{record['label_state']}", key, active_ids[0],
                    ),
                )
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError(f"{args.input}:{line_number}: {exc}") from exc
        conn.commit()
    return 0


def cmd_stats(args: argparse.Namespace) -> int:
    with _connect(_require_database_url(args)) as conn, conn.cursor() as cursor:
        cursor.execute(
            """
            SELECT anti_pattern_id, label_state, trust_level, count(*)
              FROM ml.annotations
             WHERE resolution_state='active'
             GROUP BY anti_pattern_id, label_state, trust_level
             ORDER BY anti_pattern_id, label_state, trust_level
            """
        )
        rows = [
            {"antiPatternId": row[0], "label": row[1], "trustLevel": row[2], "count": row[3]}
            for row in cursor.fetchall()
        ]
    print(json.dumps(rows, indent=2, sort_keys=True))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", default=os.environ.get("ML_DATASET_DATABASE_URL"))
    commands = parser.add_subparsers(dest="command", required=True)

    queue = commands.add_parser("queue")
    queue.add_argument("--output", required=True)
    queue.add_argument("--limit", type=int, default=100)
    queue.add_argument("--strategy", choices=("unreviewed", "conflict", "language_balance"), default="unreviewed")
    queue.set_defaults(func=cmd_queue)

    importer = commands.add_parser("import")
    importer.add_argument("--input", required=True)
    importer.set_defaults(func=cmd_import)

    conflicts = commands.add_parser("conflicts")
    conflicts.set_defaults(func=cmd_conflicts)

    adjudicate = commands.add_parser("adjudicate")
    adjudicate.add_argument("--input", required=True)
    adjudicate.set_defaults(func=cmd_adjudicate)

    stats = commands.add_parser("stats")
    stats.set_defaults(func=cmd_stats)

    args = parser.parse_args(argv)
    if getattr(args, "limit", 1) <= 0:
        parser.error("--limit must be positive")
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
