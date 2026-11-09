# Benchmark: dev / fallback

13 PRs, 24 supported expected findings, 7 unsupported defects, 33 labeled negatives. Match tolerance: ±3 lines.

| Metric | Value |
| --- | --- |
| Precision | 0.389 |
| Recall (supported) | 0.583 |
| F1 | 0.467 |
| TP / FP / FN | 14 / 22 / 10 |
| False positives per PR | 1.692 |
| FPs on labeled decoys | 22 |
| Severity accuracy (matched) | 0.857 |
| Line localization accuracy | 1.000 |
| Hunk localization accuracy | 1.000 |
| Near misses (right hunk, wrong lines) | 0 |
| Coverage of all labeled defects | 0.452 |
| Unsupported defects (never counted) | 7 |
| Unsupported-file rate | 0.296 |
| Findings with all guidance fields | 1.000 |
| Latency p50 / p95 (ms) | 0.731 / 1.092 |
| Peak Python heap (KiB) | 20.300 |

## Confusion by anti-pattern

| antiPattern | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| `MAINTAINABILITY_COMMENTED_CODE` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `MAINTAINABILITY_PRINT_STATEMENT` | 1 | 5 | 1 | 0.167 | 0.500 | 0.250 |
| `PERFORMANCE_N_PLUS_ONE` | 2 | 0 | 1 | 1.000 | 0.667 | 0.800 |
| `PERFORMANCE_QUADRATIC_LOOP` | 1 | 0 | 0 | 1.000 | 1.000 | 1.000 |
| `READABILITY_LONG_METHOD` | 1 | 1 | 0 | 0.500 | 1.000 | 0.667 |
| `READABILITY_MAGIC_NUMBER` | 1 | 9 | 0 | 0.100 | 1.000 | 0.182 |
| `RELIABILITY_BROAD_EXCEPTION` | 3 | 1 | 0 | 0.750 | 1.000 | 0.857 |
| `RELIABILITY_MISSING_TIMEOUT` | 0 | 0 | 4 | n/a | 0.000 | 0.000 |
| `SECURITY_HARDCODED_SECRET` | 1 | 2 | 1 | 0.333 | 0.500 | 0.400 |
| `SECURITY_SQL_INJECTION` | 2 | 1 | 2 | 0.667 | 0.500 | 0.571 |
| `SECURITY_WEAK_CRYPTO` | 1 | 3 | 1 | 0.250 | 0.500 | 0.333 |

## By category

| Category | TP | FP | FN | Precision | Recall | F1 |
| --- | --- | --- | --- | --- | --- | --- |
| MAINTAINABILITY | 2 | 5 | 1 | 0.286 | 0.667 | 0.400 |
| PERFORMANCE | 3 | 0 | 1 | 1.000 | 0.750 | 0.857 |
| READABILITY | 2 | 10 | 0 | 0.167 | 1.000 | 0.286 |
| RELIABILITY | 3 | 1 | 4 | 0.750 | 0.429 | 0.545 |
| SECURITY | 4 | 6 | 4 | 0.400 | 0.500 | 0.444 |

## Per PR

| PR | Expected | Unsupported | TP | FP | FN |
| --- | --- | --- | --- | --- | --- |
| dev-01-py-user-lookup-sqli | 1 | 0 | 1 | 2 | 0 |
| dev-02-ts-profile-client | 2 | 1 | 1 | 2 | 1 |
| dev-03-java-payment-service | 3 | 1 | 1 | 0 | 2 |
| dev-04-py-customer-sync | 4 | 1 | 2 | 1 | 2 |
| dev-05-js-format-decoys | 0 | 0 | 0 | 8 | 0 |
| dev-06-py-settings-crypto | 2 | 0 | 2 | 4 | 0 |
| dev-07-java-user-repository | 2 | 1 | 1 | 0 | 1 |
| dev-08-ts-report-pagination | 1 | 2 | 1 | 0 | 0 |
| dev-09-py-importer-exceptions | 2 | 1 | 2 | 1 | 0 |
| dev-10-mixed-unsupported-files | 1 | 0 | 1 | 2 | 0 |
| dev-11-js-order-service | 3 | 0 | 1 | 0 | 2 |
| dev-12-py-cli-and-long-statement | 1 | 0 | 1 | 2 | 0 |
| dev-13-py-unicode-path | 2 | 0 | 0 | 0 | 2 |

## Errors

- dev-01-py-user-lookup-sqli FP: SECURITY_SQL_INJECTION app/users/repository.py:14-14
- dev-01-py-user-lookup-sqli FP: READABILITY_MAGIC_NUMBER app/api/users.py:9-9
- dev-02-ts-profile-client FP: MAINTAINABILITY_PRINT_STATEMENT src/api/profile.ts:3-3
- dev-02-ts-profile-client FP: MAINTAINABILITY_PRINT_STATEMENT src/api/profile.ts:4-4
- dev-02-ts-profile-client FN: RELIABILITY_MISSING_TIMEOUT src/api/profile.ts:7-9
- dev-03-java-payment-service FN: RELIABILITY_MISSING_TIMEOUT src/main/java/com/example/payments/PaymentService.java:12-12
- dev-03-java-payment-service FN: SECURITY_WEAK_CRYPTO src/main/java/com/example/payments/PaymentService.java:28-28
- dev-04-py-customer-sync FP: READABILITY_MAGIC_NUMBER app/sync/customers.py:15-15
- dev-04-py-customer-sync FN: PERFORMANCE_N_PLUS_ONE app/sync/customers.py:12-14
- dev-04-py-customer-sync FN: RELIABILITY_MISSING_TIMEOUT app/sync/customers.py:14-14
- dev-05-js-format-decoys FP: MAINTAINABILITY_PRINT_STATEMENT src/utils/format.js:3-3
- dev-05-js-format-decoys FP: READABILITY_MAGIC_NUMBER src/utils/format.js:3-3
- dev-05-js-format-decoys FP: READABILITY_MAGIC_NUMBER src/utils/format.js:19-19
- dev-05-js-format-decoys FP: SECURITY_WEAK_CRYPTO src/utils/format.js:22-22
- dev-05-js-format-decoys FP: SECURITY_WEAK_CRYPTO src/utils/format.js:23-23
- dev-05-js-format-decoys FP: MAINTAINABILITY_PRINT_STATEMENT tests/format.test.js:4-4
- dev-05-js-format-decoys FP: READABILITY_MAGIC_NUMBER tests/format.test.js:5-5
- dev-05-js-format-decoys FP: READABILITY_MAGIC_NUMBER tests/format.test.js:6-6
- dev-06-py-settings-crypto FP: SECURITY_WEAK_CRYPTO config/settings.py:15-15
- dev-06-py-settings-crypto FP: SECURITY_HARDCODED_SECRET tests/test_settings.py:3-3
- dev-06-py-settings-crypto FP: SECURITY_HARDCODED_SECRET tests/test_settings.py:7-7
- dev-06-py-settings-crypto FP: READABILITY_MAGIC_NUMBER tests/test_settings.py:9-9
- dev-07-java-user-repository FN: SECURITY_SQL_INJECTION src/main/java/com/example/users/UserRepository.java:34-36
- dev-09-py-importer-exceptions FP: RELIABILITY_BROAD_EXCEPTION app/importer.py:29-29
- dev-10-mixed-unsupported-files FP: READABILITY_MAGIC_NUMBER cmd/server/main.go:10-10
- dev-10-mixed-unsupported-files FP: READABILITY_MAGIC_NUMBER cmd/server/main.go:11-11
- dev-11-js-order-service FN: SECURITY_SQL_INJECTION src/services/orders.js:14-14
- dev-11-js-order-service FN: RELIABILITY_MISSING_TIMEOUT src/services/orders.js:19-19
- dev-12-py-cli-and-long-statement FP: MAINTAINABILITY_PRINT_STATEMENT scripts/export_users.py:14-14
- dev-12-py-cli-and-long-statement FP: READABILITY_LONG_METHOD app/notifications.py:8-8
- dev-13-py-unicode-path FN: SECURITY_HARDCODED_SECRET app/services/café.py:4-4
- dev-13-py-unicode-path FN: MAINTAINABILITY_PRINT_STATEMENT app/services/café.py:8-8

## Unsupported defects

boundary_error: 2, incorrect_exception_handling: 1, null_dereference: 2, race_condition: 1, resource_leak: 1

Never counted as detected; listed so recall is not overstated.
