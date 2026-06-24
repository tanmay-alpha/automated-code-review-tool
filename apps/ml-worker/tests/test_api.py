"""
CodeLens — FastAPI ML worker endpoint tests (Issue #9).

Uses FastAPI's TestClient and a fake `app.state.model` so the tests
run without loading the real CodeBERT checkpoint (~500 MB).

Run from the repo root:
    cd apps/ml-worker && python -m pytest tests/test_api.py -v
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from app.schemas import Finding


SECRET_HEADER = {"X-ML-Worker-Secret": "testsecret"}


# ----------------------------------------------------------------------
# Tests
# ----------------------------------------------------------------------
def test_health_returns_ok(client: TestClient):
    """No auth required; returns 200 with the expected envelope."""
    client.app.state.model.model_name = "fake/codelens-test"
    client.app.state.model.device = "cpu"
    r = client.get("/ml/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["modelLoaded"] is True
    assert body["modelName"] == "fake/codelens-test"
    assert body["device"] == "cpu"


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
    client.app.state.model.predict.return_value = [
        Finding(
            antiPattern="PERFORMANCE_N_PLUS_1",
            category="PERFORMANCE",
            severity="major",
            confidence=0.87,
            explanation="N+1 query pattern detected.",
        )
    ]
    r = client.post(
        "/ml/review",
        headers=SECRET_HEADER,
        json={
            "diff": "+ for user in users:\n+     db.query(user.id)",
            "language": "python",
            "mode": "diff",
        },
    )
    assert r.status_code == 200
    body = r.json()
    assert isinstance(body["findings"], list)
    assert len(body["findings"]) == 1
    assert body["findings"][0]["antiPattern"] == "PERFORMANCE_N_PLUS_1"
    assert body["findings"][0]["severity"] == "major"
    assert 0.0 <= body["qualityScore"] <= 100.0
    assert body["processingTimeMs"] >= 0
    assert body["windowsProcessed"] >= 1


def test_quality_score_decreases_with_findings():
    """Pure unit test of compute_quality_score — no FastAPI needed."""
    from app.model import compute_quality_score

    assert compute_quality_score([]) == 100.0
    one_minor = [Finding(
        antiPattern="READ_MAGIC_NUMBER",
        category="READABILITY",
        severity="minor",
        confidence=0.5,
        explanation="x",
    )]
    assert compute_quality_score(one_minor) == 97.0
    one_major = [Finding(
        antiPattern="PERFORMANCE_N_PLUS_1",
        category="PERFORMANCE",
        severity="major",
        confidence=0.5,
        explanation="x",
    )]
    assert compute_quality_score(one_major) == 90.0
    one_critical = [Finding(
        antiPattern="SECURITY_HARDCODED_SECRET",
        category="SECURITY",
        severity="critical",
        confidence=0.5,
        explanation="x",
    )]
    assert compute_quality_score(one_critical) == 80.0
    # Floors at 0.
    many = [one_critical[0]] * 10
    assert compute_quality_score(many) == 0.0
