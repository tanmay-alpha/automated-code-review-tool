"""Add the ML-worker root and the repo root to sys.path so that
``import taxonomy`` and ``import app`` work inside tests."""

import os
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

_HERE = Path(__file__).resolve().parent
_ML_ROOT = _HERE.parent
_REPO_ROOT = _ML_ROOT.parent

for _p in (str(_ML_ROOT), str(_REPO_ROOT)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

# Ensure the FastAPI app's Settings singleton picks up the test secret.
# Settings is instantiated on import; if a prior test already imported
# app.main with an empty ML_WORKER_SECRET the auth middleware rejects
# every request with 403. We set the env here BEFORE the first test
# imports app.main.
os.environ.setdefault("ML_WORKER_SECRET", "testsecret")
os.environ.setdefault("MODEL_NAME", "none")


class _FakeModel:
    """Minimal stub used by tests that don't need real inference.

    ``predict`` is a MagicMock so tests can assign
    ``.return_value`` (e.g. ``model.predict.return_value = [...]``).
    """

    def __init__(self) -> None:
        self.model_name = "none"
        self.device = "cpu"
        self.model_loaded = True
        self.model_version = "rule-baseline-v1"
        self.taxonomy_version = "1.0.0"
        self.labels: list[str] = []
        self.predict = MagicMock(return_value=[])

    @property
    def is_healthy(self) -> bool:
        return True


@asynccontextmanager
async def _noop_lifespan(_app):  # noqa: ANN001
    yield


@pytest.fixture
def client():
    """FastAPI TestClient wired with a fake model and the test secret."""
    from app.main import app  # noqa: WPS433

    # Override the lifespan so it never runs — we set state.model directly.
    app.router.lifespan_context = _noop_lifespan  # type: ignore[assignment]
    app.state.model = _FakeModel()
    with TestClient(app) as c:
        yield c
