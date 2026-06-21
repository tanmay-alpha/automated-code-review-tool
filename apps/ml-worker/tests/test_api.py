"""
CodeLens — FastAPI ML worker endpoint tests (Issue #9).

Uses FastAPI's TestClient and a fake `app.state.model` so the tests
run without loading the real CodeBERT checkpoint (~500 MB).

Run from the repo root:
    cd apps/ml-worker && ML_WORKER_SECRET=test-secret pytest tests/test_api.py -v
"""
from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

# Set the secret BEFORE importing app.main — Settings() reads it at
# instantiation.
os.environ.setdefault("ML_WORKER_SECRET", "test-secret")

_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
sys.path.insert(0, str(_ML_WORKER))

from app import main as app_main  # noqa: E402
from app.main import app  # noqa: E402
from app.schemas import Finding  # noqa: E402


# ----------------------------------------------------------------------
# Fake model — replaces CodeLensModel in app.state
# ----------------------------------------------------------------------
class FakeCodeLensModel:
    """Stub that returns a configurable list of findings."""

    def __init__(self, findings: list[Finding] | None = None) -> None:
        self.findings = findings if findings is not None else []
        self.last_windows_processed = 1
        self.model_name = "fake/codelens-test"
        self.device = "cpu"

    def predict(self, text: str, language: str) -> list[Finding]:
        return list(self.findings)


@pytest.fixture
def client() -> TestClient:
    """A TestClient whose app.state.model is a FakeCodeLensModel.

    We bypass the lifespan by setting `app.state.model` manually
    *before* TestClient starts, so no real model is ever loaded.
    """
    fake = FakeCodeLensModel()
    app.state.model = fake
    with TestClient(app) as c:
        yield c
    # Clear so a later test can set its own fake.
    app.state.model = None


@pytest.fixture
def client_no_model() -> TestClient:
    """A TestClient where the model is missing (lifespan disabled)."""
    app.state.model = None
    with TestClient(app) as c:
        yield c


SECRET_HEADER = {"X-ML-Worker-Secret": "test-secret"}


# ----------------------------------------------------------------------
# Tests
# ----------------------------------------------------------------------
def test_health_returns_ok(client: TestClient):
    """No auth required; returns 200 with the expected envelope."""
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
    assert "X-ML-Worker-Secret" in r.json()["detail"]


def test_review_rejects_wrong_secret(client: TestClient):
    r = client.post(
        "/ml/review",
        headers={"X-ML-Worker-Secret": "WRONG"},
        json={"diff": "+x = 1", "language": "python"},
    )
    assert r.status_code == 403


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
    client.app.state.model.findings = [
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
