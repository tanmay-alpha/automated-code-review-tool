#!/usr/bin/env bash
# =====================================================
# CodeLens — Download CodeReviewer dataset (Issue #1)
# =====================================================
# Clones microsoft/CodeBERT into /tmp/codebert and copies
# the code-review-data folder into
# apps/ml-worker/training/data/raw/.
#
# Source plan: ENGINEERING_PLAN.md, Section 6, Step 1.
# =====================================================

set -euo pipefail

REPO_URL="https://github.com/microsoft/CodeBERT.git"
TMP_DIR="/tmp/codebert"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_DIR="${REPO_ROOT}/apps/ml-worker/training/data/raw"

echo "[1/4] Cleaning previous temp clone at ${TMP_DIR}..."
rm -rf "${TMP_DIR}"

echo "[2/4] Cloning ${REPO_URL} (shallow) into ${TMP_DIR}..."
git clone --depth 1 "${REPO_URL}" "${TMP_DIR}"

SRC_DIR="${TMP_DIR}/CodeReviewer/code-review-data"
if [ ! -d "${SRC_DIR}" ]; then
  echo "ERROR: expected path not found: ${SRC_DIR}" >&2
  echo "Check that microsoft/CodeBERT repo still contains CodeReviewer/code-review-data" >&2
  exit 1
fi

echo "[3/4] Copying code-review-data into ${DEST_DIR}..."
mkdir -p "${DEST_DIR}"
# -a preserves attributes; trailing /. copies contents, not the folder itself.
cp -a "${SRC_DIR}/." "${DEST_DIR}/"

echo "[4/4] Cleaning up ${TMP_DIR}..."
rm -rf "${TMP_DIR}"

echo ""
echo "Dataset downloaded successfully"
echo "Location: ${DEST_DIR}"
echo ""
echo "Next step: run 'python apps/ml-worker/training/dataset.py' to inspect."
