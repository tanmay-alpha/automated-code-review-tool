# Benchmark: dev / fallback

13 PRs, 24 supported expected findings, 7 unsupported defects, 33 labeled negatives. Match tolerance: ±3 lines.

| Metric | Value |
| --- | --- |
| Precision | 0.960 |
| Recall (supported) | 1.000 |
| F1 | 0.980 |
| TP / FP / FN | 24 / 1 / 0 |
| False positives per PR | 0.077 |
| FPs on labeled decoys | 1 |
| Severity accuracy (matched) | 0.917 |
| Line localization accuracy | 1.000 |
| Hunk localization accuracy | 1.000 |
| Near misses (right hunk, wrong lines) | 0 |
| Coverage of all labeled defects | 0.774 |
| Unsupported defects (never counted) | 7 |
| Unsupported-file rate | 0.296 |
| Findings with all guidance fields | 1.000 |
| Latency p50 / p95 (ms) | 0.499 / 0.697 |
| Peak Python heap (KiB) | 22.600 |

## Confusion by anti-pattern

| antiPattern | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| `MAINTAINABILITY_COMMENTED_CODE` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `MAINTAINABILITY_PRINT_STATEMENT` | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `PERFORMANCE_N_PLUS_ONE` | 3 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `PERFORMANCE_QUADRATIC_LOOP` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `READABILITY_LONG_METHOD` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `READABILITY_MAGIC_NUMBER` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `RELIABILITY_BROAD_EXCEPTION` | 3 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `RELIABILITY_MISSING_TIMEOUT` | 4 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `SECURITY_HARDCODED_SECRET` | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `SECURITY_SQL_INJECTION` | 4 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `SECURITY_WEAK_CRYPTO` | 2 | 1 | 0 | 0.667 | 1.000 | 0.800 |

## By category

| Category | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| MAINTAINABILITY | 3 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| PERFORMANCE | 4 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| READABILITY | 2 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| RELIABILITY | 7 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| SECURITY | 8 | 1 | 0 | 0.889 | 1.000 | 0.941 |

## Per PR

| PR | Expected | Unsupported | TP | FP | FN |
| --- | --- | --- | --- | --- | --- |
| dev-01-py-user-lookup-sqli | 1 | 0 | 1 | 0 | 0 |
| dev-02-ts-profile-client | 2 | 1 | 2 | 0 | 0 |
| dev-03-java-payment-service | 3 | 1 | 3 | 0 | 0 |
| dev-04-py-customer-sync | 4 | 1 | 4 | 0 | 0 |
| dev-05-js-format-decoys | 0 | 0 | 0 | 0 | 0 |
| dev-06-py-settings-crypto | 2 | 0 | 2 | 1 | 0 |
| dev-07-java-user-repository | 2 | 1 | 2 | 0 | 0 |
| dev-08-ts-report-pagination | 1 | 2 | 1 | 0 | 0 |
| dev-09-py-importer-exceptions | 2 | 1 | 2 | 0 | 0 |
| dev-10-mixed-unsupported-files | 1 | 0 | 1 | 0 | 0 |
| dev-11-js-order-service | 3 | 0 | 3 | 0 | 0 |
| dev-12-py-cli-and-long-statement | 1 | 0 | 1 | 0 | 0 |
| dev-13-py-unicode-path | 2 | 0 | 2 | 0 | 0 |

## Errors

- dev-06-py-settings-crypto FP: SECURITY_WEAK_CRYPTO config/settings.py:15-15

## Unsupported defects

boundary_error: 2, incorrect_exception_handling: 1, null_dereference: 2, race_condition: 1, resource_leak: 1

Never counted as detected; listed so recall is not overstated.
