# Benchmark: heldout / fallback

8 PRs, 16 supported expected findings, 5 unsupported defects, 13 labeled negatives. Match tolerance: ±3 lines.

| Metric | Value |
| --- | --- |
| Precision | 0.933 |
| Recall (supported) | 0.875 |
| F1 | 0.903 |
| TP / FP / FN | 14 / 1 / 2 |
| False positives per PR | 0.125 |
| FPs on labeled decoys | 1 |
| Severity accuracy (matched) | 0.929 |
| Line localization accuracy | 1.000 |
| Hunk localization accuracy | 1.000 |
| Near misses (right hunk, wrong lines) | 0 |
| Coverage of all labeled defects | 0.667 |
| Unsupported defects (never counted) | 5 |
| Unsupported-file rate | 0.333 |
| Findings with all guidance fields | 1.000 |
| Latency p50 / p95 (ms) | 0.499 / 0.692 |
| Peak Python heap (KiB) | 19.300 |

## Confusion by anti-pattern

| antiPattern | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| `MAINTAINABILITY_COMMENTED_CODE` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `MAINTAINABILITY_PRINT_STATEMENT` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `PERFORMANCE_N_PLUS_ONE` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `PERFORMANCE_QUADRATIC_LOOP` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `READABILITY_MAGIC_NUMBER` | 1 | 0 | 1 | 1.000 | 0.500 | 0.667 |
| `RELIABILITY_BROAD_EXCEPTION` | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `RELIABILITY_MISSING_TIMEOUT` | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `SECURITY_HARDCODED_SECRET` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `SECURITY_SQL_INJECTION` | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `SECURITY_WEAK_CRYPTO` | 2 | 1 | 1 | 0.667 | 0.667 | 0.667 |

## By category

| Category | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| MAINTAINABILITY | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| PERFORMANCE | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| READABILITY | 1 | 0 | 1 | 1.000 | 0.500 | 0.667 |
| RELIABILITY | 4 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| SECURITY | 5 | 1 | 1 | 0.833 | 0.833 | 0.833 |

## Per PR

| PR | Expected | Unsupported | TP | FP | FN |
| --- | --- | --- | --- | --- | --- |
| heldout-01-py-search-endpoint | 5 | 0 | 5 | 0 | 0 |
| heldout-02-ts-billing-integration | 3 | 1 | 3 | 0 | 0 |
| heldout-03-java-order-service | 3 | 1 | 2 | 0 | 1 |
| heldout-04-py-thread-counter | 2 | 1 | 1 | 0 | 1 |
| heldout-05-js-audit-decoys | 1 | 0 | 1 | 1 | 0 |
| heldout-06-mixed-unsupported-files | 1 | 0 | 1 | 0 | 0 |
| heldout-07-java-report-dao | 1 | 2 | 1 | 0 | 0 |
| heldout-08-py-tests-only | 0 | 0 | 0 | 0 | 0 |

## Errors

- heldout-03-java-order-service FN: SECURITY_WEAK_CRYPTO src/main/java/com/example/orders/OrderService.java:25-25
- heldout-04-py-thread-counter FN: READABILITY_MAGIC_NUMBER app/metrics/counter.py:18-18
- heldout-05-js-audit-decoys FP: SECURITY_WEAK_CRYPTO src/lib/audit.js:10-10

## Unsupported defects

boundary_error: 1, null_dereference: 1, race_condition: 1, resource_leak: 1, wrong_comparison: 1

Never counted as detected; listed so recall is not overstated.
