# Review benchmark

`benchmarks/` holds a small, reviewed, PR-level benchmark for the review
engines, and `benchmarks/run_benchmark.py` scores an engine against it. It
measures the rule engine that the default deployment runs (`MODEL_NAME=none`).
No model results are reported because the repository ships no trained or
promoted checkpoint.

## Reproduce

From the repository root, with the ML worker's dependencies installed
(`pip install -r apps/ml-worker/requirements-test.txt`):

```bash
python benchmarks/run_benchmark.py
```

This scores both splits with the in-process rule engine, writes
`benchmarks/results/<split>-<engine>.{json,md}`, and records the model engine
as skipped unless `MODEL_NAME` names a promoted checkpoint. Options:

| Option | Effect |
| --- | --- |
| `--split dev\|heldout\|all` | Which fixtures to score (default `all`). |
| `--engine fallback\|model\|url` | Repeatable. Default `fallback` and `model`. |
| `--worker-url http://host:8000` | Also score a running worker over HTTP (`ML_WORKER_SECRET` from the environment); the report records the `engine` each response declared. |
| `--repeat N` | Timed runs per PR for latency (default 5). |
| `--tolerance N` | Line tolerance for a match (default 3). |
| `--out DIR` | Output directory (default `benchmarks/results`). |

`apps/ml-worker/tests/test_benchmark_harness.py` checks the metric arithmetic
on a hand-computed synthetic case, rejects malformed ground truth, and asserts
that a fresh run reproduces the committed `benchmarks/results/*-fallback.json`
exactly (latency excluded). The numbers below are copied from those files.

The "before" numbers come from `benchmarks/results/baseline/`, produced by the
same harness at commit `1fd27d0`, whose rules are those of `main` (`821a418`).
To regenerate them, check out that commit and run the command above with
`--out benchmarks/results/baseline`.

## Method

The design borrows two ideas from recent code-review benchmarks; no data,
claims or scores from them are used:

- **Review whole pull requests and keep ground truth honest** (SWRBench,
  arXiv 2509.01494). Each fixture is a PR-shaped unified diff with one or more
  files, and its ground truth lists every defect a careful reviewer should
  raise, including defects the engine cannot express.
- **Score localization and use project context** (ContextCRBench,
  arXiv 2511.07017). A finding must name the right file and lines, several
  fixtures need another file to understand the defect (for example, the source
  of an injected value), and the report separates line-level from hunk-level
  localization.

### Fixtures

Each case is `benchmarks/fixtures/<split>/<id>.diff` plus `<id>.yaml`:

```yaml
id: dev-02-ts-profile-client
title: ...
provenance: Synthetic. Hand-written for this benchmark; ...
expected:
  - {antiPattern: RELIABILITY_MISSING_TIMEOUT, file: src/api/profile.ts, lines: [7, 9], severity: minor, note: ...}
  - {antiPattern: unsupported, defect: null_dereference, file: src/api/profile.ts, lines: [12, 12], severity: major, note: ...}
negatives:
  - {antiPattern: MAINTAINABILITY_PRINT_STATEMENT, file: src/api/profile.ts, lines: [3, 3], reason: console.log inside a comment.}
```

- `expected` entries use taxonomy IDs, or `unsupported` with a `defect` class
  for real defects outside the taxonomy (null dereference, race condition,
  resource leak, boundary error, wrong comparison, incorrect exception
  handling).
- `negatives` are decoys a precise reviewer must not flag: matches inside
  comments, strings, docs and tests, parameterized queries, non-security
  hashes, log-and-rethrow handlers. `*` covers any anti-pattern (used for
  files outside the reviewed languages).
- The harness validates every entry before scoring: known IDs and severities,
  files present in the diff, and at least one added line inside every expected
  range. It walks the diff with its own parser, so a defect in the worker's
  parser cannot hide itself.

**Provenance.** All 21 fixtures are synthetic code written for this
benchmark, not taken from any repository or existing dataset. Diffs were
generated from hand-written before/after files with Python's `difflib`
(3 context lines); one fixture uses a hand-written git C-quoted path header.
Ground truth was written with the code, before the engine was run on it, and
follows the labeling policy below.

**Labeling policy.**

| antiPattern | Positive when | Negative when |
| --- | --- | --- |
| `SECURITY_HARDCODED_SECRET` | A real-looking credential literal in non-test source (critical). | Environment lookups, placeholders, made-up test fixtures. |
| `SECURITY_SQL_INJECTION` | SQL text built from a runtime value by concatenation, interpolation or formatting, then executed. Critical when the value visibly comes from a request, otherwise major. | Bound parameters; SQL-looking text in logs or comments. |
| `SECURITY_WEAK_CRYPTO` | MD5/SHA-1/DES/RC4/`Math.random` protecting passwords, signatures or tokens. | Content addressing, ETags, checksums of trusted data. |
| `PERFORMANCE_N_PLUS_ONE` | A DB or HTTP call per iteration of a data-sized loop. | One query whose results the loop streams. |
| `PERFORMANCE_QUADRATIC_LOOP` | Nested loops over two input collections where a map lookup would do. | Sequential loops. |
| `RELIABILITY_BROAD_EXCEPTION` | A catch-all handler that swallows or converts the error. | A broad handler that logs and re-raises. |
| `RELIABILITY_MISSING_TIMEOUT` | An outbound HTTP call or client with no timeout. | Calls with an explicit timeout or abort signal. |
| `READABILITY_MAGIC_NUMBER` | An unnamed literal >= 10 in production logic. | Named constants, HTTP status codes, literals in strings, comments and tests. |
| `READABILITY_LONG_METHOD` | A statement over 200 characters. | A line whose length is one string literal (a URL constant). |
| `MAINTAINABILITY_COMMENTED_CODE` | Three or more commented-out statements. | Prose comments. |
| `MAINTAINABILITY_PRINT_STATEMENT` | `print`/`console.log` left in production code. | Comments, strings, tests, a CLI's deliberate output. |

### Splits

| Split | PRs | Supported findings | Unsupported defects | Negatives | Use |
| --- | --- | --- | --- | --- | --- |
| `dev` | 13 | 24 | 7 | 33 | May be used to tune rules. |
| `heldout` | 8 | 16 | 5 | 13 | Never used for tuning. |

Both splits were written and committed (`1fd27d0`) before any rule changed.
During rule work only the dev report was read; for held-out only the overall
line was printed for the baseline, and its per-PR errors were first read after
the rules were frozen and committed (`37e3e14`). The held-out errors listed
below were deliberately left unfixed. A rule change made in response to a
held-out case must move that case to `dev` and replace it with a new
held-out case.

### Metrics

Matching is one-to-one: a prediction matches an expected finding when the
antiPattern and file are equal and the predicted range overlaps the expected
range widened by ±3 lines; pairs are assigned greedily, closest first.

| Metric | Definition |
| --- | --- |
| TP / FP / FN | Matched predictions / unmatched predictions / unmatched supported expected findings, per antiPattern and overall (micro). |
| Precision, recall, F1 | TP/(TP+FP), TP/(TP+FN), 2TP/(2TP+FP+FN). Recall is over supported findings only. |
| False positives per PR | FP / number of PRs. |
| FPs on labeled decoys | FPs that overlap a negative for the same antiPattern (or `*`). |
| Severity accuracy | Matched findings whose severity equals the labeled severity. |
| Line localization accuracy | Matched findings whose range overlaps the exact labeled lines (no tolerance). |
| Hunk localization accuracy | Matched findings inside the same diff hunk as the labeled lines. |
| Near misses | Unmatched predictions with the right antiPattern in the same hunk as an unmatched expected finding (counted as FP and FN). |
| Coverage of all labeled defects | TP / (supported + unsupported expected). Shows how much of the real review the engine covers. |
| Unsupported defects | Count by class. Never counted as detected; a prediction overlapping one is an FP. |
| Unsupported-file rate | Changed files the engine does not analyze (binary, no added lines, language not reviewed) / changed files. |
| Explainability | Findings that carry non-empty `explanation`, `evidence`, `remediation` and `caveat`. |
| Latency p50 / p95 | Nearest-rank percentiles of wall-clock time per PR over `--repeat` runs. Host-specific. |
| Peak Python heap | Largest `tracemalloc` peak while scanning one PR (not process RSS). |

## Results

Rule engine (`fallback`), tolerance ±3 lines. "Before" is `main`'s rules
(`benchmarks/results/baseline/`); "after" is this branch
(`benchmarks/results/`).

| Metric | Dev before | Dev after | Held-out before | Held-out after |
| --- | --- | --- | --- | --- |
| Precision | 0.389 | 0.960 | 0.600 | 0.933 |
| Recall (supported) | 0.583 | 1.000 | 0.562 | 0.875 |
| F1 | 0.467 | 0.980 | 0.581 | 0.903 |
| TP / FP / FN | 14 / 22 / 10 | 24 / 1 / 0 | 9 / 6 / 7 | 14 / 1 / 2 |
| False positives per PR | 1.692 | 0.077 | 0.750 | 0.125 |
| FPs on labeled decoys | 22 | 1 | 6 | 1 |
| PRs fully detected / with supported findings | 6 / 12 | 12 / 12 | 1 / 7 | 5 / 7 |
| Severity accuracy (matched) | 0.857 | 0.917 | 1.000 | 0.929 |
| Line / hunk localization accuracy | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 |
| Coverage of all labeled defects | 0.452 | 0.774 | 0.429 | 0.667 |
| Unsupported defects (never counted) | 7 | 7 | 5 | 5 |
| Unsupported-file rate | 0.296 (8/27) | 0.296 (8/27) | 0.250 (3/12) | 0.333 (4/12) |
| Findings with all guidance fields | 1.000 | 1.000 | 1.000 | 1.000 |
| Latency p50 / p95 (ms) | 0.731 / 1.092 | 0.565 / 0.807 | 0.364 / 0.806 | 0.469 / 0.671 |
| Peak Python heap (KiB) | 20.3 | 22.8 | 14.7 | 19.4 |

The "before" explainability rate is already 1.000 because the baseline commit
includes the guidance fields; only the rules differ. The dev-only parser fix
for git-quoted paths (`eb45bfd`, before the rule changes) moved dev to
P 0.421 / R 0.667 / F1 0.516 on its own.

The unsupported-file rate did not fall: the quoted-path fix makes the
non-ASCII Python file reviewable, while restricting rules to the documented
languages stops Go (dev) and Rust (held-out) files from being rule-scanned.

### Confusion by antiPattern (TP / FP / FN)

| antiPattern | Dev before | Dev after | Held-out before | Held-out after |
| --- | --- | --- | --- | --- |
| `SECURITY_HARDCODED_SECRET` | 1 / 2 / 1 | 2 / 0 / 0 | 1 / 1 / 0 | 1 / 0 / 0 |
| `SECURITY_SQL_INJECTION` | 2 / 1 / 2 | 4 / 0 / 0 | 0 / 1 / 2 | 2 / 0 / 0 |
| `SECURITY_WEAK_CRYPTO` | 1 / 3 / 1 | 2 / 1 / 0 | 1 / 0 / 2 | 2 / 1 / 1 |
| `PERFORMANCE_N_PLUS_ONE` | 2 / 0 / 1 | 3 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 |
| `PERFORMANCE_QUADRATIC_LOOP` | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 |
| `RELIABILITY_BROAD_EXCEPTION` | 3 / 1 / 0 | 3 / 0 / 0 | 2 / 0 / 0 | 2 / 0 / 0 |
| `RELIABILITY_MISSING_TIMEOUT` | 0 / 0 / 4 | 4 / 0 / 0 | 0 / 0 / 2 | 2 / 0 / 0 |
| `READABILITY_MAGIC_NUMBER` | 1 / 9 / 0 | 1 / 0 / 0 | 1 / 2 / 1 | 1 / 0 / 1 |
| `READABILITY_LONG_METHOD` | 1 / 1 / 0 | 1 / 0 / 0 | — | — |
| `MAINTAINABILITY_COMMENTED_CODE` | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 | 1 / 0 / 0 |
| `MAINTAINABILITY_PRINT_STATEMENT` | 1 / 5 / 1 | 2 / 0 / 0 | 1 / 2 / 0 | 1 / 0 / 0 |

No prediction landed on a labeled line with a different antiPattern (the
wrong-label table in each report is empty).

### Remaining errors after the change

| Split | Error | Why |
| --- | --- | --- |
| dev | FP `SECURITY_WEAK_CRYPTO` on `hashlib.md5(content)` for an ETag | The rule cannot see purpose; `usedforsecurity=False` suppresses it. |
| held-out | FP `SECURITY_WEAK_CRYPTO` on `createHash("sha1")` for content addressing | Same limitation, on the new algorithm-name check. |
| held-out | FN `SECURITY_WEAK_CRYPTO` on `Math.random()` for a reset token | No rule for insecure randomness. |
| held-out | FN `READABILITY_MAGIC_NUMBER` on `join(timeout=15)` | The rule only flags literals >= 20; the labeling policy says >= 10. |

Severity mismatches are all SQL injection labeled critical (request-derived
input) where the rule reports the taxonomy default, major.

### What changed in the rules

Every change below was motivated by a dev error; see the commit message of
`37e3e14` for the full list.

- Each hunk's new side (added and context lines) is lexed so call-shaped text
  inside comments and string literals no longer matches. This removed the
  print, crypto, SQL and magic-number decoy hits, including
  `// Docs: never call console.log(token) …` and
  `"Use console.log( only while debugging"`.
- Rules scan only Python, JavaScript, TypeScript and Java, as the README
  documents. Go, Rust and other code files are no longer rule-scanned.
- Test files skip the print and magic-number rules; a secret in a test file
  must have a provider token format.
- Broad handlers that re-raise or throw, well-known HTTP status codes,
  `print(..., file=...)` and single-literal constant lines are not reported.
- SQL injection also covers template literals, `%` and `.format` building,
  JDBC/JPA query methods, and SQL text assembled on an earlier line.
- Weak crypto also covers weak algorithm names passed to `getInstance`,
  `createHash`, `createCipheriv` and `hashlib.new`.
- `RELIABILITY_MISSING_TIMEOUT` is now rule-detected for `requests.*` and
  `urlopen` (Python), `fetch` and `axios` (JavaScript/TypeScript) and
  `HttpClient.newHttpClient()` (Java). A call is not reported when its
  arguments carry `timeout`, an abort `signal`, or forwarded options
  (`**kwargs`, `...options`). The N+1 rule also recognises HTTP verbs other
  than `get`.

The engine now reports `modelVersion: rule-baseline-v2`.

## Limitations

- **Synthetic and small.** 21 PRs and 40 supported findings. Per-antiPattern
  rows have one to four positives, so one fixture moves a row from 0 to 1.
  Treat the numbers as regression signals for these rules, not as an estimate
  of precision on real pull requests.
- **Written by the same team that tuned the rules.** The held-out split guards
  against tuning on the evaluated cases, not against shared blind spots in
  how fixtures and rules were imagined. The dev split is now fitted (F1 0.98);
  the held-out drop to 0.90 is the more honest figure.
- **One annotator.** Labels follow the policy above but were not
  independently adjudicated.
- **No model or LLM results.** No promoted checkpoint exists, so the model
  engine is recorded as skipped. No hosted model is used.
- **Rules are lexical.** The lexer does not model regex literals, nested
  template expressions, or strings and comments opened before the hunk. Data
  flow (is a value user-controlled?) and cross-file reasoning are out of
  scope; cross-file fixtures test localization of the sink only.
- **Latency and memory are host-specific** and exclude HTTP and JSON
  overhead unless `--worker-url` is used.
