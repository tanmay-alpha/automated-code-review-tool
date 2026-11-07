"""Shared fixtures for ML-worker tests."""

import os
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

# Ensure the FastAPI app's Settings singleton picks up the test secret.
# Settings is instantiated on import; if a prior test already imported
# app.main with an empty ML_WORKER_SECRET the auth middleware rejects
# every request with 403. We set the env here BEFORE the first test
# imports app.main.
os.environ.setdefault("ML_WORKER_SECRET", "testsecret")
os.environ.setdefault("MODEL_NAME", "none")


class _FakeModel:
    """Minimal stand-in for AutomatedCodeReviewToolModel.

    ``predict_hunk`` is a MagicMock so tests can set ``return_value`` or
    ``side_effect``.
    """

    def __init__(self) -> None:
        self.model_name = "none"
        self.device = "cpu"
        self.taxonomy_version = "1.0.0"
        self.predict_hunk = MagicMock(
            return_value=SimpleNamespace(findings=(), windows_processed=1)
        )

    @property
    def is_healthy(self) -> bool:
        return True

    @property
    def model_version(self) -> str:
        return self.model_name


@asynccontextmanager
async def _noop_lifespan(_app):
    yield


@pytest.fixture
def client():
    """FastAPI TestClient wired with a fake model and the test secret."""
    from app.main import app

    # Override the lifespan so it never runs — we set state.model directly.
    app.router.lifespan_context = _noop_lifespan  # type: ignore[assignment]
    app.state.model = _FakeModel()
    with TestClient(app) as c:
        yield c
