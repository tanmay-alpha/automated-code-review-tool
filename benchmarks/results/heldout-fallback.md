# Benchmark: heldout / fallback

8 PRs, 16 supported expected findings, 5 unsupported defects, 13 labeled negatives. Match tolerance: ±3 lines.

| Metric | Value |
| --- | --- |
| Precision | 0.600 |
| Recall (supported) | 0.562 |
| F1 | 0.581 |
| TP / FP / FN | 9 / 6 / 7 |
| False positives per PR | 0.750 |
| FPs on labeled decoys | 6 |
| Severity accuracy (matched) | 1.000 |
| Line localization accuracy | 1.000 |
| Hunk localization accuracy | 1.000 |
| Near misses (right hunk, wrong lines) | 0 |
| Coverage of all labeled defects | 0.429 |
| Unsupported defects (never counted) | 5 |
| Unsupported-file rate | 0.250 |
| Findings with all guidance fields | 1.000 |
| Latency p50 / p95 (ms) | 0.301 / 0.391 |
| Peak Python heap (KiB) | 14.700 |

## Confusion by anti-pattern

| antiPattern | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| `MAINTAINABILITY_COMMENTED_CODE` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `MAINTAINABILITY_PRINT_STATEMENT` | 1 | 2 | 0 | 0.333 | 1.000 | 0.500 |
| `PERFORMANCE_N_PLUS_ONE` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `PERFORMANCE_QUADRATIC_LOOP` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `READABILITY_MAGIC_NUMBER` | 1 | 2 | 1 | 0.333 | 0.500 | 0.400 |
| `RELIABILITY_BROAD_EXCEPTION` | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `RELIABILITY_MISSING_TIMEOUT` | 0 | 0 | 2 | n/a | 0.000 | 0.000 |
| `SECURITY_HARDCODED_SECRET` | 1 | 1 | 0 | 0.500 | 1.000 | 0.667 |
| `SECURITY_SQL_INJECTION` | 0 | 1 | 2 | 0.000 | 0.000 | 0.000 |
| `SECURITY_WEAK_CRYPTO` | 1 | 0 | 2 | 1.000 | 0.333 | 0.500 |

## By category

| Category | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| MAINTAINABILITY | 2 | 2 | 0 | 0.500 | 1.000 | 0.667 |
| PERFORMANCE | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| READABILITY | 1 | 2 | 1 | 0.333 | 0.500 | 0.400 |
| RELIABILITY | 2 | 0 | 2 | 1.000 | 0.500 | 0.667 |
| SECURITY | 2 | 2 | 4 | 0.500 | 0.333 | 0.400 |

## Per PR

| PR | Expected | Unsupported | TP | FP | FN |
| --- | --- | --- | --- | --- | --- |
| heldout-01-py-search-endpoint | 5 | 0 | 3 | 0 | 2 |
| heldout-02-ts-billing-integration | 3 | 1 | 2 | 1 | 1 |
| heldout-03-java-order-service | 3 | 1 | 2 | 0 | 1 |
| heldout-04-py-thread-counter | 2 | 1 | 1 | 0 | 1 |
| heldout-05-js-audit-decoys | 1 | 0 | 0 | 1 | 1 |
| heldout-06-mixed-unsupported-files | 1 | 0 | 1 | 0 | 0 |
| heldout-07-java-report-dao | 1 | 2 | 0 | 0 | 1 |
| heldout-08-py-tests-only | 0 | 0 | 0 | 4 | 0 |

## Errors

- heldout-01-py-search-endpoint FN: SECURITY_SQL_INJECTION app/routes/search.py:12-12
- heldout-01-py-search-endpoint FN: RELIABILITY_MISSING_TIMEOUT app/routes/search.py:15-15
- heldout-02-ts-billing-integration FP: MAINTAINABILITY_PRINT_STATEMENT src/integrations/billing.ts:5-5
- heldout-02-ts-billing-integration FN: RELIABILITY_MISSING_TIMEOUT src/integrations/billing.ts:7-9
- heldout-03-java-order-service FN: SECURITY_WEAK_CRYPTO src/main/java/com/example/orders/OrderService.java:25-25
- heldout-04-py-thread-counter FN: READABILITY_MAGIC_NUMBER app/metrics/counter.py:18-18
- heldout-05-js-audit-decoys FP: SECURITY_SQL_INJECTION src/lib/audit.js:5-5
- heldout-05-js-audit-decoys FN: SECURITY_WEAK_CRYPTO src/lib/audit.js:14-14
- heldout-07-java-report-dao FN: SECURITY_SQL_INJECTION src/main/java/com/example/reports/ReportDao.java:17-17
- heldout-08-py-tests-only FP: SECURITY_HARDCODED_SECRET tests/test_billing.py:5-5
- heldout-08-py-tests-only FP: READABILITY_MAGIC_NUMBER tests/test_billing.py:8-8
- heldout-08-py-tests-only FP: MAINTAINABILITY_PRINT_STATEMENT tests/test_billing.py:10-10
- heldout-08-py-tests-only FP: READABILITY_MAGIC_NUMBER tests/test_billing.py:16-16

## Unsupported defects

boundary_error: 1, null_dereference: 1, race_condition: 1, resource_leak: 1, wrong_comparison: 1

Never counted as detected; listed so recall is not overstated.
