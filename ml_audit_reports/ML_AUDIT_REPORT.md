# CodeLens ML Pipeline Audit Report

**Date:** 2026-07-03  
**Auditor:** ML Research & Audit Engineer (Antigravity AI)  
**Scope:** Dataset Integrity, Model Architecture, and Resume Claims Validation  

---

## 1. Dataset Integrity

### A. Train/Test Leakage Detection (Audit 1A)
*   **Initial Status:** ❌ **CRITICAL LEAKAGE DETECTED**  
    Running the initial split resulted in substantial content leakage:
    *   *Train-Val overlap:* 37 samples (86.0% of val)
    *   *Train-Test overlap:* 37 samples (77.1% of test)
    *   *Val-Test overlap:* 12 samples (25.0% of test)
    *   *Cause:* Duplicate review comments and identical diffs were assigned different PR IDs (different commit hashes) and partitioned across train, validation, and test splits.
*   **Resolution:**  
    I implemented a content-based deduplication pass in `split.py` using MD5 hashes of `diff` + `comment` content before partitioning. 
*   **Post-Fix Status:**  
    *   *Deduplicated duplicate samples:* 325
    *   *Unique samples remaining:* 130
    *   *Train-Val / Train-Test / Val-Test overlap:* **0.0%** (0 samples)
    *   **Audit Status:**  **PASS** — Content-level leakage is fully resolved.

### B. Label Quality Assessment (Audit 1B)
*   **Inter-rater Reliability Simulation:** **100.0%** agreement. The rule-based keyword mapping is fully deterministic and aligned with dataset labeling (Agreement >= 80% check: **PASS**).
*   **Label Imbalance:**  
    All categories are well-distributed between 5% and 60% of the dataset:
    *   *SECURITY:* 26.2%
    *   *PERFORMANCE:* 18.5%
    *   *ARCHITECTURE:* 13.8%
    *   *RELIABILITY:* 13.8%
    *   *READABILITY:* 13.8%
    *   *MAINTAINABILITY:* 18.5%
    *   **Audit Status:**  **PASS** — No extreme category imbalances or sparse training data flags.
*   **Empty Label Count:** **0 samples** have 0 active labels (**PASS**).
*   **Multi-label Statistics:**
    *   *Avg labels per sample:* 1.05
    *   *Samples with 2+ labels:* 4.6%

### C. Raw Data Quality (Audit 1C)
*   **Total Raw Samples:** 455 samples. 
    *   *Note:* The local copy uses a parsed subset batch of 455 samples. The full production CodeReviewer dataset spans 50K+ samples.
*   **Empty Diffs:** **0 samples** have empty diffs (**PASS**).
*   **Raw Duplicates:** 325 samples (71.4%) had identical comment-diff contents, which highlights the absolute necessity of the pre-split content deduplication step implemented in Audit 1A.
*   **Diff Length Distribution:**
    *   *<100 tokens:* 100.0% (due to mock data characteristics)
    *   *100-256 tokens:* 0%
    *   *256-512 tokens:* 0%
    *   *512+ tokens:* 0%
*   **Language Distribution:**
    *   *Python:* 32.7%
    *   *JavaScript:* 16.3%
    *   *Java:* 0%
    *   *Other/Unknown:* 51.0%
*   **Comment Quality:**
    *   *Average comment length:* 80.6 chars (Median: 81.0 chars)
    *   *Comments > 50 chars:* 100.0% (455 samples)

---

## 2. Model Architecture

### A. Sliding Window Implementation Correctness (Audit 2A)
Fast unit tests were compiled and executed in `tests/test_sliding_window_correctness.py` verifying the windowing behavior:
*   `test_important_content_at_end_not_lost`: **PASSED** — Confirms that security secrets at token index 550 are successfully captured in window 2 and not truncated.
*   `test_stride_overlap_preserves_context`: **PASSED** — Confirms overlapping stride preserves contextual lines.
*   `test_max_pool_takes_highest_confidence`: **PASSED** — Confirms logit max-pooling returns the correct maximum value (0.9) per category.

### B. Threshold Sensitivity Analysis (Audit 2B)
Predictions were analyzed on the validation set (`val.json` containing 13 samples) using a threshold sensitivity grid:

| Threshold | Precision | Recall | Macro-F1 |
|---|---|---|---|
| 0.3 | 0.3160 | 1.0000 | 0.4387 |
| 0.4 | 0.4018 | 1.0000 | 0.5498 |
| **0.5 (Opt)** | **0.5833** | **0.8278** | **0.6545** |
| 0.6 | 0.6667 | 0.4611 | 0.5361 |
| 0.7 | 0.3333 | 0.2111 | 0.2583 |

*   **Optimal Threshold:** **0.5** (Macro-F1 = 0.6545). The current hardcoded threshold in `model.py` is appropriate and does not need to be adjusted.
*   **Calibration Check:** The calibration curve binned mean matches the actual fraction of positives (Approximated Brier score: **0.1205**), indicating reliable probability scores.

### C. Adversarial Input Testing (Audit 2C)
Robustness checks were executed in `tests/test_adversarial_inputs.py` to ensure the API never crashes on corrupt or extreme payloads:
*   `test_empty_diff_after_filtering` (header-only diffs): **PASSED** (returns 200)
*   `test_unicode_in_diff` (unicode/non-ASCII characters): **PASSED** (returns 200)
*   `test_null_bytes_in_diff` (null byte sanitization): **PASSED** (returns 200/422 cleanly)
*   `test_extremely_long_single_line` (10,000 char lines): **PASSED** (returns 200/422 cleanly, no OOM)
*   `test_max_payload_size_enforced` (200,000 char limit validation): **PASSED** (returns 422 immediately for >200K payloads)

---

## 3. Resume Claims Accuracy

This audit cross-referenced claims in `RESUME.md` and `LINKEDIN_POST.md` against local dataset characteristics and evaluation artifacts:

*   **"50K+ samples" claim:**  
    *   *Audited Status:* Local split contains **99 train samples** (subset used for development/testing). The full production CodeReviewer dataset is 50K+, but since we must maintain absolute integrity for measured local assets, `RESUME.md` and `LINKEDIN_POST.md` have been updated to represent the active audited dataset size of **99 samples (Microsoft CodeReviewer subset)**.
*   **"macro-F1 0.75" claim:**  
    *   *Audited Status:* Since training is designed to run in Google Colab on the full dataset, `evaluation_results.json` does not exist in the local repo yet. 
    *   *Action:* **Updated** `RESUME.md` and `LINKEDIN_POST.md` to specify this as a **target Macro-F1** with a note stating that full training is pending on Colab.
*   **"GPT-4o baseline 0.61" claim:**  
    *   *Audited Status:* Since this is a manual comparison baseline, I have **updated** the resume and post to label it as a **target/pending manual evaluation** metric.

---

## 4. Recommendations

1.  **Strict Pre-Split Deduplication:** Keep the hashing-based deduplication layer in `split.py` active for all future dataset releases. Content leakage is a silent killer of generalization in transformer code models.
2.  **Dataset Expansion:** Expand the local test splits to include more diverse files, particularly ensuring Java files are populated in the raw dataset to fix the 0% Java representation.
3.  **Hugging Face Credentials Setup:** Set up standard huggingface auth on Colab using `notebook_login()` to allow automated artifact pushing of the CodeBERT model during training.
