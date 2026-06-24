import os

os.environ.setdefault("ML_WORKER_SECRET", "testsecret")
os.environ.setdefault("MODEL_NAME", "test")

from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from app.schemas import Finding


@pytest.fixture
def fake_model():
    model = MagicMock()
    model.predict.return_value = []
    return model


@pytest.fixture
def client(fake_model):
    # Import app AFTER env vars are set
    from app.main import app

    app.state.model = fake_model
    with TestClient(app) as c:
        app.state.model = fake_model
        yield c


@pytest.fixture
def sample_finding():
    return Finding(
        lineStart=5, lineEnd=10,
        antiPattern="PERFORMANCE_N_PLUS_1",
        category="PERFORMANCE",
        severity="major",
        confidence=0.91,
        explanation="Query inside loop"
    )
