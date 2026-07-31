"""
automated-code-review-tool — generate realistic synthetic training data for
the fine-tuned classifier.

Produces 3-way splits (train / val / test) of <diff, anti_patterns, language>
records. Each record carries a list of concrete anti-pattern IDs drawn from
the canonical taxonomy (``taxonomy/anti_patterns.yaml``).

**Honesty note:** these are synthetic examples from hand-crafted templates,
not a real benchmark dataset. They exist to ship a working end-to-end pipeline
and to provide deterministic unit-test fixtures.

Usage::

    python apps/ml-worker/training/generate_training_data.py \\
        --output-dir training/data \\
        --train-size 200 --val-size 50 --test-size 50 \\
        --seed 42

Output schema (one record per line inside the JSON array)::

    {
      "diff": "unified diff text ...",
      "anti_patterns": ["SECURITY_HARDCODED_SECRET", "RELIABILITY_BROAD_EXCEPTION"],
      "language": "python"
    }
"""
from __future__ import annotations

import argparse
import json
import random
import re
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Canonical taxonomy — load once at module level
# ---------------------------------------------------------------------------
_TAXONOMY_PATH = Path(__file__).resolve().parents[3] / "taxonomy" / "anti_patterns.yaml"

# Use the taxonomy loader; fall back to an inline hard-coded list if PyYAML
# is unavailable so the module is always importable (e.g. during linting).
try:
    from app.taxonomy import load_taxonomy

    _TAXONOMY = load_taxonomy(_TAXONOMY_PATH)
except Exception:  # noqa: BLE001
    # Inline fallback — mirrors taxonomy/anti_patterns.yaml exactly.
    _FALLBACK_RAW = [
        ("SECURITY_HARDCODED_SECRET", "SECURITY", "critical", "Hardcoded secret in source"),
        ("SECURITY_SQL_INJECTION", "SECURITY", "critical", "SQL string concatenation"),
        ("SECURITY_WEAK_CRYPTO", "SECURITY", "major", "Weak hashing algorithm"),
        ("PERFORMANCE_N_PLUS_ONE", "PERFORMANCE", "major", "N+1 query pattern"),
        ("PERFORMANCE_QUADRATIC_LOOP", "PERFORMANCE", "major", "Quadratic nested loop"),
        ("RELIABILITY_BROAD_EXCEPTION", "RELIABILITY", "major", "Bare/broad except"),
        ("RELIABILITY_MISSING_TIMEOUT", "RELIABILITY", "major", "No timeout on I/O"),
        ("READABILITY_MAGIC_NUMBER", "READABILITY", "minor", "Unexplained numeric literal"),
        ("READABILITY_LONG_METHOD", "READABILITY", "minor", "Method is too long"),
        ("MAINTAINABILITY_DUPLICATE_CODE", "MAINTAINABILITY", "minor", "Copy-pasted logic"),
    ]
    _FALLBACK_IDS = [r[0] for r in _FALLBACK_RAW]
    _TAXONOMY_IDS: list[str] = _FALLBACK_IDS
else:
    _TAXONOMY_IDS = _TAXONOMY.ids()


# ---------------------------------------------------------------------------
# Diff templates (one per anti-pattern, keyed by canonical ID)
# ---------------------------------------------------------------------------
# Each template contains a realistic unified diff fragment that exhibits the
# anti-pattern plus a free-text review comment (kept as metadata, not input).

TEMPLATES: list[dict[str, Any]] = [
    # ── SECURITY ──────────────────────────────────────────────────────
    {
        "name": "hardcoded_api_key",
        "anti_patterns": ["SECURITY_HARDCODED_SECRET"],
        "language": "python",
        "diff": """\
diff --git a/app/config.py b/app/config.py
index abc1234..def5678 100644
--- a/app/config.py
+++ b/app/config.py
@@ -1,7 +1,9 @@
 import os

 class Config:
-    STRIPE_KEY = os.environ.get("STRIPE_KEY")
+    STRIPE_KEY = "sk_live_abc123_sample_placeholder"
+    API_TOKEN = "ghp_abcdef123456_sample_placeholder"
     DEBUG = False""",
        "comment": "Hardcoded API credentials in source — rotate and use env vars.",
    },
    {
        "name": "sql_injection",
        "anti_patterns": ["SECURITY_SQL_INJECTION"],
        "language": "python",
        "diff": """\
diff --git a/app/users.py b/app/users.py
index abc1234..def5678 100644
--- a/app/users.py
+++ b/app/users.py
@@ -20,7 +20,8 @@ def get_user(db, username):
     # BUG: string concatenation allows SQL injection
-    query = "SELECT * FROM users WHERE username = '" + username + "'"
+    query = "SELECT * FROM users WHERE username = '" + username + "'"
     return db.execute(query).fetchone()""",
        "comment": "SQL injection via string concatenation — use parameterised queries.",
    },
    {
        "name": "weak_crypto",
        "anti_patterns": ["SECURITY_WEAK_CRYPTO"],
        "language": "python",
        "diff": """\
diff --git a/app/auth.py b/app/auth.py
index abc1234..def5678 100644
--- a/app/auth.py
+++ b/app/auth.py
@@ -5,7 +5,7 @@ import hashlib
 def hash_password(password):
-    return hashlib.md5(password.encode()).hexdigest()
+    return hashlib.md5(password.encode()).hexdigest()
+    # TODO: upgrade to bcrypt""",
        "comment": "MD5 is cryptographically broken — use bcrypt or argon2.",
    },
    # ── PERFORMANCE ───────────────────────────────────────────────────
    {
        "name": "n_plus_one",
        "anti_patterns": ["PERFORMANCE_N_PLUS_ONE"],
        "language": "python",
        "diff": """\
diff --git a/app/posts.py b/app/posts.py
index abc1234..def5678 100644
--- a/app/posts.py
+++ b/app/posts.py
@@ -30,6 +30,9 @@ def list_posts_with_authors():
     posts = db.query(Post).all()
     result = []
     for post in posts:
+        author = db.query(User).filter(User.id == post.author_id).first()
         post.author_name = author.name
         result.append(post)""",
        "comment": "N+1 query — one DB round-trip per post. Use a JOIN or eager load.",
    },
    {
        "name": "quadratic_loop",
        "anti_patterns": ["PERFORMANCE_QUADRATIC_LOOP"],
        "language": "python",
        "diff": """\
diff --git a/app/matching.py b/app/matching.py
index abc1234..def5678 100644
--- a/app/matching.py
+++ b/app/matching.py
@@ -15,6 +15,13 @@ def find_matches(items, threshold):
     matches = []
     for i, a in enumerate(items):
         for j, b in enumerate(items):
+            if i == j:
+                continue
             score = compute_similarity(a, b)
             if score > threshold:
                 matches.append((i, j, score))
+    return matches  # O(n^2) — use LSH instead""",
        "comment": "Quadratic nested loop for similarity matching. Use LSH or vector DB.",
    },
    # ── RELIABILITY ───────────────────────────────────────────────────
    {
        "name": "bare_except",
        "anti_patterns": ["RELIABILITY_BROAD_EXCEPTION"],
        "language": "python",
        "diff": """\
diff --git a/app/pipeline.py b/app/pipeline.py
index abc1234..def5678 100644
--- a/app/pipeline.py
+++ b/app/pipeline.py
@@ -40,6 +40,9 @@ def run_step(data):
     try:
         result = transform(data)
+    except:
+        pass
+        # swallow everything
     return result""",
        "comment": "Bare except swallows KeyboardInterrupt and SystemExit — catch specific exceptions.",
    },
    {
        "name": "missing_timeout",
        "anti_patterns": ["RELIABILITY_MISSING_TIMEOUT"],
        "language": "python",
        "diff": """\
diff --git a/app/fetcher.py b/app/fetcher.py
index abc1234..def5678 100644
--- a/app/fetcher.py
+++ b/app/fetcher.py
@@ -5,7 +5,7 @@ import requests
 def fetch(url):
     resp = requests.get(url)
-    return resp.json()
+    return resp.json()  # no timeout — hangs if upstream stalls""",
        "comment": "HTTP GET without a timeout — add connect + read timeouts.",
    },
    # ── READABILITY ───────────────────────────────────────────────────
    {
        "name": "magic_number",
        "anti_patterns": ["READABILITY_MAGIC_NUMBER"],
        "language": "python",
        "diff": """\
diff --git a/app/cache.py b/app/cache.py
index abc1234..def5678 100644
--- a/app/cache.py
+++ b/app/cache.py
@@ -10,7 +10,8 @@ class Cache:
     def set(self, key, value):
-        self._store[key] = value
-        time.sleep
-        del self._store[key]
+        self._store[key] = value
+        time.sleep  # 1-hour TTL — should be a named constant
+        del self._store[key]""",
        "comment": "Magic number buried in sleep call — extract to a named constant.",
    },
    {
        "name": "long_method",
        "anti_patterns": ["READABILITY_LONG_METHOD"],
        "language": "python",
        "diff": """\
diff --git a/app/processor.py b/app/processor.py
index abc1234..def5678 100644
--- a/app/processor.py
+++ b/app/processor.py
@@ -5,7 +5,90 @@ def handle(event):
-    # 200-line inline processing with 6 nested conditionals
-    if event.type == "A":
-        # 40 lines
-        pass
-    elif event.type == "B":
-        # 40 lines
-        pass""",
        "comment": "200-line method with deep nesting. Extract each branch into named handlers.",
    },
    # ── MAINTAINABILITY ───────────────────────────────────────────────
    {
        "name": "duplicate_logic",
        "anti_patterns": ["MAINTAINABILITY_DUPLICATE_CODE"],
        "language": "python",
        "diff": """\
diff --git a/app/reports.py b/app/reports.py
index abc1234..def5678 100644
--- a/app/reports.py
+++ b/app/reports.py
@@ -20,6 +20,14 @@ def monthly_report():
     total = sum(r.amount for r in records)
+    # duplicate of logic in quarterly_report() and annual_report()
+    gross = sum(r.amount for r in records)
+    taxable = gross * 0.9
+    deductions = taxable * 0.15
+    net = taxable - deductions
     return format(total)""",
        "comment": "Same aggregation logic is duplicated in 3 report methods — extract a shared helper.",
    },
]


# ---------------------------------------------------------------------------
# Text augmentation
# ---------------------------------------------------------------------------

def _perturb_diff(diff: str, rng: random.Random) -> str:
    """Lightweight text perturbation to increase training data diversity.

    Changes variable names and string literals to produce structurally
    identical but textually distinct examples.
    """
    lines = diff.split("\n")
    result: list[str] = []
    identifiers: dict[str, str] = {}
    for line in lines:
        for old, new in identifiers.items():
            line = line.replace(old, new)
        if rng.random() < 0.15:
            words = re.findall(r"[a-z_][a-z0-9_]{3,}", line)
            for w in words:
                if w not in identifiers and len(w) > 3:
                    new_w = w[:4] + rng.choice("abcdefghijklmnop") + w[5:]
                    identifiers[w] = new_w
        result.append(line)
    return "\n".join(result)


def _perturb_comment(comment: str, rng: random.Random) -> str:
    """Light variation of the review comment text."""
    prefixes = ["", "Nit: ", "Blocking: ", "", "Please fix: "]
    suffixes = [
        "",
        " This needs to be addressed before merge.",
        "",
        "",
        "",
    ]
    return rng.choice(prefixes) + comment + rng.choice(suffixes)


# ---------------------------------------------------------------------------
# Generation helpers
# ---------------------------------------------------------------------------

def generate_example(
    template: dict[str, Any],
    rng: random.Random,
) -> dict[str, Any]:
    """Produce one training record from a template.

    The record follows this schema:

    * ``diff`` — perturbed unified diff string (UTF-8)
    * ``anti_patterns`` — list of canonical anti-pattern IDs
    * ``language`` — detected language tag
    """
    diff = _perturb_diff(template["diff"], rng)
    comment = _perturb_comment(template["comment"], rng)  # metadata only
    anti_patterns = list(template["anti_patterns"])
    language = template.get("language", "python")
    return {
        "diff": diff,
        "anti_patterns": anti_patterns,
        "language": language,
        # Review comment is stored as metadata for future use.  It is NOT
        # part of the classifier input (train–serve parity).
        "_comment": comment,
    }


def generate_split(
    n: int,
    templates: list[dict[str, Any]],
    rng: random.Random,
) -> list[dict[str, Any]]:
    """Generate *n* examples by sampling with replacement from *templates*."""
    return [generate_example(rng.choice(templates), rng) for _ in range(n)]


# ---------------------------------------------------------------------------
# Schema validation
# ---------------------------------------------------------------------------

_REQUIRED_FIELDS = ("diff", "anti_patterns", "language")
_VALID_LANGUAGES = {"python", "javascript", "typescript", "java", "unknown"}


def validate_record(record: dict[str, Any]) -> None:
    """Raise ValueError if *record* does not conform to the output schema."""
    for field in _REQUIRED_FIELDS:
        if field not in record:
            raise ValueError(f"Missing required field: {field}")
    if not isinstance(record["anti_patterns"], list):
        raise ValueError("anti_patterns must be a list")
    if not record["anti_patterns"]:
        raise ValueError("anti_patterns must be non-empty")
    for ap_id in record["anti_patterns"]:
        if ap_id not in _TAXONOMY_IDS:
            raise ValueError(
                f"Unknown anti-pattern ID: {ap_id!r}. "
                f"Expected one of: {', '.join(_TAXONOMY_IDS)}"
            )
    lang = record["language"]
    if lang not in _VALID_LANGUAGES:
        raise ValueError(
            f"Unknown language: {lang!r}. Expected one of: {', '.join(sorted(_VALID_LANGUAGES))}"
        )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate synthetic training data for automated-code-review-tool",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("training/data"),
        help="Directory for output JSON files (default: training/data)",
    )
    parser.add_argument(
        "--train-size",
        type=int,
        default=2000,
        help="Number of training examples (must be > 0)",
    )
    parser.add_argument(
        "--val-size",
        type=int,
        default=200,
        help="Number of validation examples (must be > 0)",
    )
    parser.add_argument(
        "--test-size",
        type=int,
        default=200,
        help="Number of test examples (must be > 0)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for reproducibility (default: 42)",
    )
    args = parser.parse_args(argv)

    # ------------------------------------------------------------------
    # Validate arguments
    # ------------------------------------------------------------------
    if args.train_size <= 0:
        parser.error("--train-size must be a positive integer")
    if args.val_size <= 0:
        parser.error("--val-size must be a positive integer")
    if args.test_size <= 0:
        parser.error("--test-size must be a positive integer")
    if args.seed < 0:
        parser.error("--seed must be a non-negative integer")

    args.output_dir.mkdir(parents=True, exist_ok=True)

    rng = random.Random(args.seed)

    splits: dict[str, list[dict[str, Any]]] = {
        "train": generate_split(args.train_size, TEMPLATES, rng),
        "val": generate_split(args.val_size, TEMPLATES, rng),
        "test": generate_split(args.test_size, TEMPLATES, rng),
    }

    for split_name, records in splits.items():
        # Validate every record before writing.
        for i, rec in enumerate(records):
            try:
                validate_record(rec)
            except ValueError as exc:
                raise ValueError(
                    f"Invalid record {i} in split {split_name!r}: {exc}"
                ) from exc

        path = args.output_dir / f"{split_name}.json"
        payload = {
            "_generated": True,
            "_generator_seed": args.seed,
            "_taxonomy_path": str(_TAXONOMY_PATH),
            "_label_field": "anti_patterns",
            "data": records,
        }
        path.write_text(
            json.dumps(payload, indent=2, ensure_ascii=True) + "\n",
            encoding="utf-8",
        )
        print(f"Wrote {len(records)} examples to {path}")

    # ------------------------------------------------------------------
    # Distribution summary
    # ------------------------------------------------------------------
    print("\nAnti-pattern distribution (train split):")
    from collections import Counter

    counts: Counter = Counter()
    for rec in splits["train"]:
        for ap in rec["anti_patterns"]:
            counts[ap] += 1
    for ap_id in _TAXONOMY_IDS:
        cnt = counts.get(ap_id, 0)
        pct = cnt / len(splits["train"]) * 100
        print(f"  {ap_id:<35s}: {cnt:5d} / {len(splits['train'])} ({pct:5.1f}%)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
