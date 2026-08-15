"""
FastAPI ML worker endpoint contract tests.

Uses FastAPI's TestClient and a fake `app.state.model` so the tests
run without loading the real CodeBERT checkpoint (~500 MB).

Run from the repo root:
    cd apps/ml-worker && python -m pytest tests/test_api.py -v
"""
from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient

from app.model import ModelPrediction
from app.schemas import Finding


SECRET_HEADER = {"X-ML-Worker-Secret": "testsecret"}


# ----------------------------------------------------------------------
# Tests
# ----------------------------------------------------------------------
def test_health_returns_ok(client: TestClient):
    """No auth required; returns 200 with the expected envelope."""
    client.app.state.model.model_name = "fake/automated-code-review-tool-test"
    client.app.state.model.device = "cpu"
    r = client.get("/ml/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] in ("ok", "healthy")
    assert body["modelLoaded"] is True
    assert body["modelName"] == "fake/automated-code-review-tool-test"
    assert body["device"] == "cpu"
    assert body["engine"] == "model"
    assert body["taxonomyVersion"] == "1.0.0"


def test_health_reports_operational_fallback(client: TestClient):
    client.app.state.model = None
    r = client.get("/ml/health")
    assert r.status_code == 200
    assert r.json()["status"] == "healthy"
    assert r.json()["engine"] == "fallback"
    assert r.json()["modelLoaded"] is False


def test_review_rejects_missing_secret(client: TestClient):
    r = client.post("/ml/review", json={"diff": "+x = 1", "language": "python"})
    assert r.status_code == 403
    # We deliberately return a generic "Forbidden" detail rather than
    # telling the unauthenticated caller which header is expected —
    # leaking header names in 403 bodies is a small but real attack-surface
    # win (lets adversaries probe auth mechanisms blindly). The test
    # asserts on status + detail shape, not on the header name.
    body = r.json()
    assert "detail" in body
    assert body["detail"] == "Forbidden"


def test_review_rejects_wrong_secret(client: TestClient):
    r = client.post(
        "/ml/review",
        headers={"X-ML-Worker-Secret": "WRONG"},
        json={"diff": "+x = 1", "language": "python"},
    )
    assert r.status_code == 403
    assert r.json()["detail"] == "Forbidden"


def test_review_rejects_empty_diff(client: TestClient):
    r = client.post(
        "/ml/review",
        headers=SECRET_HEADER,
        json={"diff": "", "language": "python"},
    )
    # Pydantic enforces min_length=1 → 422 from FastAPI.
    assert r.status_code == 422


def test_review_rejects_invalid_language(client: TestClient):
    r = client.post(
        "/ml/review",
        headers=SECRET_HEADER,
        json={"diff": "+x = 1", "language": "rust"},
    )
    assert r.status_code == 422


def test_review_accepts_valid_diff(client: TestClient):
    """A real-looking diff returns a clean envelope with the mock's findings."""
    # Configure the fake to return one N+1 finding.
    client.app.state.model.predict_hunk.return_value = ModelPrediction(
        findings=(Finding(
            antiPattern="PERFORMANCE_N_PLUS_ONE",
            category="PERFORMANCE",
            severity="major",
            confidence=0.87,
            explanation="N+1 query pattern detected.",
        ),),
        windows_processed=1,
    )
    r = client.post(
        "/ml/review",
        headers=SECRET_HEADER,
        json={
            "diff": (
                "diff --git a/orders.py b/orders.py\n"
                "--- a/orders.py\n"
                "+++ b/orders.py\n"
                "@@ -1,1 +1,3 @@\n"
                " def review():\n"
                "+    for user in users:\n"
                "+        db.query(user.id)"
            ),
            "language": "python",
            "mode": "diff",
        },
    )
    assert r.status_code == 200
    body = r.json()
    assert isinstance(body["findings"], list)
    assert len(body["findings"]) == 1
    assert body["findings"][0]["antiPattern"] == "PERFORMANCE_N_PLUS_ONE"
    assert body["findings"][0]["severity"] == "major"
    assert 0.0 <= body["qualityScore"] <= 100.0
    assert body["processingTimeMs"] >= 0
    assert body["windowsProcessed"] >= 1
    assert body["engine"] == "model"
    call = client.app.state.model.predict_hunk.call_args
    assert call.kwargs["file_path"] == "orders.py"
    assert len(call.kwargs["hunk_hash"]) == 64
    assert call.kwargs["line_start"] == 1


def test_model_failure_degrades_to_localized_fallback(client: TestClient):
    client.app.state.model.predict_hunk.side_effect = RuntimeError("GPU unavailable")
    diff = (
        "diff --git a/demo.py b/demo.py\n"
        "--- a/demo.py\n"
        "+++ b/demo.py\n"
        "@@ -1,1 +1,2 @@\n"
        " def run():\n"
        "+    print('debug')"
    )
    response = client.post(
        "/ml/review",
        headers=SECRET_HEADER,
        json={"diff": diff, "language": "python"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["engine"] == "fallback"
    assert body["modelVersion"] == "rule-baseline-v1"
    assert body["findings"][0]["filePath"] == "demo.py"
    assert len(body["findings"][0]["hunkHash"]) == 64


def test_model_timeout_degrades_to_fallback(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
):
    from app import main as main_module

    def slow_predict(*_args, **_kwargs):
        time.sleep(0.05)
        return []

    client.app.state.model.predict_hunk.side_effect = slow_predict
    monkeypatch.setattr(main_module, "INFERENCE_TIMEOUT_S", 0.001)
    diff = (
        "diff --git a/demo.py b/demo.py\n"
        "--- a/demo.py\n"
        "+++ b/demo.py\n"
        "@@ -1,1 +1,2 @@\n"
        " def run():\n"
        "+    print('debug')"
    )
    response = client.post(
        "/ml/review",
        headers=SECRET_HEADER,
        json={"diff": diff, "language": "python"},
    )
    assert response.status_code == 200
    assert response.json()["engine"] == "fallback"


def test_file_mode_remains_functional_in_fallback(client: TestClient):
    client.app.state.model = None
    response = client.post(
        "/ml/review",
        headers=SECRET_HEADER,
        json={
            "diff": "def run():\n    print('debug')",
            "language": "python",
            "mode": "file",
            "filePath": "src/demo.py",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["engine"] == "fallback"
    assert body["findings"][0]["filePath"] == "src/demo.py"
    assert body["findings"][0]["lineStart"] == 2


def test_quality_score_decreases_with_findings():
    """Pure unit test of compute_quality_score — no FastAPI needed.

    Scoring contract:
      * critical: 20-point penalty × confidence
      * major:    10-point penalty × confidence
      * minor:     3-point penalty × confidence
      * score = max(0, min(100, 100 - sum(penalty * conf)))
      * rounded to 2 decimals.
    """
    from app.model import compute_quality_score

    assert compute_quality_score([]) == 100.0
    one_minor = [Finding(
        antiPattern="READ_MAGIC_NUMBER",
        category="READABILITY",
        severity="minor",
        confidence=0.5,
        explanation="x",
    )]
    # 100 - 3.0*0.5 = 98.5
    assert compute_quality_score(one_minor) == 98.5
    one_major = [Finding(
        antiPattern="PERFORMANCE_N_PLUS_1",
        category="PERFORMANCE",
        severity="major",
        confidence=0.5,
        explanation="x",
    )]
    # 100 - 10.0*0.5 = 95.0
    assert compute_quality_score(one_major) == 95.0
    one_critical = [Finding(
        antiPattern="SECURITY_HARDCODED_SECRET",
        category="SECURITY",
        severity="critical",
        confidence=0.5,
        explanation="x",
    )]
    # 100 - 20.0*0.5 = 90.0
    assert compute_quality_score(one_critical) == 90.0
    # Floors at 0.
    many = [one_critical[0]] * 10
    assert compute_quality_score(many) == 0.0
