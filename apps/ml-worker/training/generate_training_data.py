"""
automated-code-review-tool — generate realistic synthetic training data for CodeBERT fine-tuning.

Produces 3-way splits (train / val / test) of <diff, comment, labels> triples.
Diff text is generated from hand-crafted templates (one per anti-pattern)
so every label has high fidelity: the diff actually contains the bug pattern.

Labels (6 binary, in LABEL_NAMES order):
  0 — SECURITY
  1 — PERFORMANCE
  2 — ARCHITECTURE
  3 — RELIABILITY
  4 — READABILITY
  5 — MAINTAINABILITY

Usage:
    python training/generate_training_data.py \
        --output-dir training/data \
        --train-size 2000 --val-size 200 --test-size 200 \
        --seed 42
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path
from typing import Any

LABEL_NAMES = [
    "SECURITY",
    "PERFORMANCE",
    "ARCHITECTURE",
    "RELIABILITY",
    "READABILITY",
    "MAINTAINABILITY",
]

CATEGORIES = {
    "SECURITY": 0,
    "PERFORMANCE": 1,
    "ARCHITECTURE": 2,
    "RELIABILITY": 3,
    "READABILITY": 4,
    "MAINTAINABILITY": 5,
}

# ── diff templates (one per anti-pattern) ───────────────────────────────
# Each template has a `diff` field (a realistic git diff fragment) and a
# `comment` (what a senior engineer would say on the PR).

TEMPLATES: list[dict[str, Any]] = [
    # ── SECURITY ──────────────────────────────────────────────────────
    {
        "name": "hardcoded_api_key",
        "categories": ["SECURITY"],
        "diff": """diff --git a/app/config.py b/app/config.py
index abc1234..def5678 100644
--- a/app/config.py
+++ b/app/config.py
@@ -1,7 +1,9 @@
 import os

 class Config:
--    STRIPE_KEY = os.environ.get("STRIPE_KEY")
+    # FIXME: remove before prod
+    STRIPE_KEY = "sample_stripe_key_placeholder"
+    API_TOKEN = "sample_github_token_placeholder"
     DEBUG = False""",
        "comment": "Hardcoded API credentials in source — anyone with repo access "
                   "can exfiltrate these. Move to environment variables or a secrets manager.",
    },
    {
        "name": "sql_injection",
        "categories": ["SECURITY"],
        "diff": """diff --git a/app/users.py b/app/users.py
index abc1234..def5678 100644
--- a/app/users.py
+++ b/app/users.py
@@ -20,7 +20,8 @@ def get_user(db, username):
     # BUG: string concatenation allows SQL injection
-    query = "SELECT * FROM users WHERE username = '" + username + "'"
+    query = "SELECT * FROM users WHERE username = '" + username + "'"
     return db.execute(query).fetchone()""",
        "comment": "SQL injection via string concatenation — an attacker can bypass "
                   "authentication or exfiltrate data. Use parameterised queries.",
    },
    {
        "name": "weak_crypto",
        "categories": ["SECURITY"],
        "diff": """diff --git a/app/auth.py b/app/auth.py
index abc1234..def5678 100644
--- a/app/auth.py
+++ b/app/auth.py
@@ -5,7 +5,7 @@ import hashlib
 def hash_password(password):
-    return hashlib.md5(password.encode()).hexdigest()
+    return hashlib.md5(password.encode()).hexdigest()
+    # TODO: upgrade to bcrypt""",
        "comment": "MD5 is cryptographically broken and unsuitable for password hashing. "
                   "Use bcrypt, argon2, or scrypt with a proper work factor.",
    },
    # ── PERFORMANCE ───────────────────────────────────────────────────
    {
        "name": "n_plus_one",
        "categories": ["PERFORMANCE"],
        "diff": """diff --git a/app/posts.py b/app/posts.py
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
        "comment": "N+1 query — one DB round-trip per post. Use a JOIN or "
                   "eager-load to collapse to a single query.",
    },
    {
        "name": "quadratic_loop",
        "categories": ["PERFORMANCE"],
        "diff": """diff --git a/app/matching.py b/app/matching.py
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
+    return matches  # O(n^2) — use locality-sensitive hashing instead""",
        "comment": "Quadratic nested loop for similarity matching will not scale. "
                   "Consider locality-sensitive hashing or a vector DB.",
    },
    {
        "name": "eager_list_load",
        "categories": ["PERFORMANCE"],
        "diff": """diff --git a/app/dashboard.py b/app/dashboard.py
index abc1234..def5678 100644
--- a/app/dashboard.py
+++ b/app/dashboard.py
@@ -8,6 +8,7 @@ def get_dashboard_data():
     users = db.query(User).all()
-    data = []
-    for user in users:
-        orders = db.query(Order).filter(Order.user_id == user.id).all()
-        data.append({"user": user, "orders": orders})
-    return data""",
        "comment": "Loading all users then iterating with individual queries per "
                   "user will timeout on large datasets. Use a joined load.",
    },
    # ── ARCHITECTURE ──────────────────────────────────────────────────
    {
        "name": "god_class",
        "categories": ["ARCHITECTURE"],
        "diff": """diff --git a/app/engine.py b/app/engine.py
index abc1234..def5678 100644
--- a/app/engine.py
+++ b/app/engine.py
@@ -1,4 +1,20 @@
 class Engine:
+    # 1,200-line class handling auth, DB, email, payments, logging, and UI rendering
+    def handle_request(self, request):
+        if request.type == "login":
+            self._validate_credentials(request)
+            self._create_session(request)
+        elif request.type == "payment":
+            self._charge_card(request)
+            self._send_receipt(request)
+        # ... 20 more branches""",
        "comment": "This class violates Single Responsibility with 20+ methods. "
                   "Split into focused modules: auth, billing, notifications.",
    },
    {
        "name": "circular_import",
        "categories": ["ARCHITECTURE"],
        "diff": """diff --git a/app/models.py b/app/models.py
index abc1234..def5678 100644
--- a/app/models.py
+++ b/app/models.py
@@ -1,5 +1,6 @@
-from app.services import calculate_total
 from Order(Base):
+    from app.services import calculate_total
     total = Column(Float)
     def compute(self):
         return calculate_total(self)""",
        "comment": "Circular import between models and services. Move shared logic "
                   "to a third module that both can import.",
    },
    {
        "name": "magic_number",
        "categories": ["READABILITY", "ARCHITECTURE"],
        "diff": """diff --git a/app/cache.py b/app/cache.py
index abc1234..def5678 100644
--- a/app/cache.py
+++ b/app/cache.py
@@ -10,7 +10,8 @@ class Cache:
     def set(self, key, value):
-        self._store[key] = value
-        time.sleep
-        del self._store[key]
+        self._store[key] = value
+        time.sleep  # 1 hour TTL — should be a named constant
+        del self._store[key]""",
        "comment": "Magic number 3600 buried in a sleep call. Define a named "
                   "constant like CACHE_TTL_SECONDS = 3600.",
    },
    # ── RELIABILITY ───────────────────────────────────────────────────
    {
        "name": "bare_except",
        "categories": ["RELIABILITY"],
        "diff": """diff --git a/app/pipeline.py b/app/pipeline.py
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
        "comment": "Bare except swallows KeyboardInterrupt, SystemExit, and "
                   "MemoryError — the process will silently produce wrong results.",
    },
    {
        "name": "missing_retry",
        "categories": ["RELIABILITY"],
        "diff": """diff --git a/app/external.py b/app/external.py
index abc1234..def5678 100644
--- a/app/external.py
+++ b/app/external.py
@@ -8,7 +8,7 @@ def call_upstream(url):
     import urllib.request
     req = urllib.request.Request(url)
-    return urllib.request.urlopen(req, timeout=5).read()
+    return urllib.request.urlopen(req, timeout=5).read()
+    # No retry — transient 503s will fail the entire pipeline""",
        "comment": "No retry on transient upstream failures. Add exponential "
                   "backoff with jitter and a max-retry cap.",
    },
    {
        "name": "missing_timeout",
        "categories": ["RELIABILITY"],
        "diff": """diff --git a/app/fetcher.py b/app/fetcher.py
index abc1234..def5678 100644
--- a/app/fetcher.py
+++ b/app/fetcher.py
@@ -5,7 +5,7 @@ import requests
 def fetch(url):
     resp = requests.get(url)
-    return resp.json()
+    return resp.json()  # no timeout — hangs forever if upstream stalls""",
        "comment": "HTTP GET without a timeout. A slow upstream will exhaust "
                   "the thread pool. Add connect + read timeouts.",
    },
    # ── READABILITY ───────────────────────────────────────────────────
    {
        "name": "long_method",
        "categories": ["READABILITY"],
        "diff": """diff --git a/app/processor.py b/app/processor.py
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
        "comment": "200-line method with deep nesting. Extract each branch into "
                   "a named handler and use a dispatch table.",
    },
    {
        "name": "cryptic_name",
        "categories": ["READABILITY"],
        "diff": """diff --git a/app/utils.py b/app/utils.py
index abc1234..def5678 100644
--- a/app/utils.py
+++ b/app/utils.py
@@ -1,5 +1,6 @@
 def proc(d, k, v):
-    return {**d, k: v}
+    return {**d, k: v}  # cryptic — what does proc do?""",
        "comment": "One-letter parameter names make the intent opaque. "
                   "Rename to something that conveys purpose.",
    },
    # ── MAINTAINABILITY ───────────────────────────────────────────────
    {
        "name": "commented_out_code",
        "categories": ["MAINTAINABILITY"],
        "diff": """diff --git a/app/payment.py b/app/payment.py
index abc1234..def5678 100644
--- a/app/payment.py
+++ b/app/payment.py
@@ -15,6 +15,12 @@ def charge(amount, card):
     # Legacy providers (retained for audit trail)
+    # stripe.Charge.create(amount=amount, source=card)
+    # paypal rest.Payment.create(...)
+    # braintree.Transaction.sale(...)
+    # razorpay.Payment.create(...)
+    # square.Payment.create(...)
+    # authorize.net.createTransactionRequest(...)
     gateway.charge(amount, card)""",
        "comment": "Six blocks of commented-out code. Delete them — git history "
                   "preserves the old logic if you need to revert.",
    },
    {
        "name": "duplicate_logic",
        "categories": ["MAINTAINABILITY"],
        "diff": """diff --git a/app/reports.py b/app/reports.py
index abc1234..def5678 100644
--- a/app/reports.py
+++ b/app/reports.py
@@ -20,6 +20,14 @@ def monthly_report():
     total = sum(r.amount for r in records)
+    # duplicate of the logic in quarterly_report() and annual_report()
+    gross = sum(r.amount for r in records)
+    taxable = gross * 0.9
+    deductions = taxable * 0.15
+    net = taxable - deductions
     return format(total)""",
        "comment": "Same aggregation logic is duplicated in 3 report methods. "
                   "Extract a shared _summarise() helper.",
    },
]


# ── text augmentation ──────────────────────────────────────────────────

def _perturb_diff(diff: str, rng: random.Random) -> str:
    """Lightweight text perturbation to increase training data diversity.

    Currently just changes variable names and string literals to
    produce structurally identical but textually distinct examples.
    """
    lines = diff.split("\n")
    result = []
    identifiers: dict[str, str] = {}
    for line in lines:
        # Replace bare Python identifiers (not inside existing code tokens)
        for old, new in identifiers.items():
            line = line.replace(old, new)
        # Occasionally generate a new identifier alias for a word we see
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
    prefixes = [
        "",
        "Nit: ",
        "Blocking: ",
        "",
        "Please fix: ",
    ]
    suffixes = [
        "",
        " This needs to be addressed before merge.",
        "",
        "",
        "",
    ]
    return rng.choice(prefixes) + comment + rng.choice(suffixes)


# ── main generation logic ─────────────────────────────────────────────

def generate_example(template: dict[str, Any], rng: random.Random) -> dict[str, Any]:
    diff = _perturb_diff(template["diff"], rng)
    comment = _perturb_comment(template["comment"], rng)
    labels = [0] * len(LABEL_NAMES)
    for cat in template["categories"]:
        labels[CATEGORIES[cat]] = 1
    return {
        "diff": diff,
        "comment": comment,
        "labels": labels,
    }


def generate_split(
    n: int,
    templates: list[dict[str, Any]],
    rng: random.Random,
) -> list[dict[str, Any]]:
    """Generate n examples by randomly sampling (with replacement) from templates."""
    examples = []
    for _ in range(n):
        tpl = rng.choice(templates)
        examples.append(generate_example(tpl, rng))
    return examples


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate automated-code-review-tool training data")
    parser.add_argument("--output-dir", type=Path, default=Path("training/data"))
    parser.add_argument("--train-size", type=int, default=2000)
    parser.add_argument("--val-size", type=int, default=200)
    parser.add_argument("--test-size", type=int, default=200)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)

    rng = random.Random(args.seed)

    splits = {
        "train": generate_split(args.train_size, TEMPLATES, rng),
        "val": generate_split(args.val_size, TEMPLATES, rng),
        "test": generate_split(args.test_size, TEMPLATES, rng),
    }

    for split_name, data in splits.items():
        path = args.output_dir / f"{split_name}.json"
        payload = {
            "_generated": True,
            "_label_names": LABEL_NAMES,
            "data": data,
        }
        path.write_text(json.dumps(payload, indent=2))
        print(f"Wrote {len(data)} examples to {path}")

    print("\nLabel distribution:")
    for i, name in enumerate(LABEL_NAMES):
        train_pos = sum(1 for ex in splits["train"] if ex["labels"][i] == 1)
        pct = train_pos / len(splits["train"]) * 100
        print(f"  {name:15s}: {train_pos:5d}/{len(splits['train'])} ({pct:.1f}%)")


if __name__ == "__main__":
    main()
