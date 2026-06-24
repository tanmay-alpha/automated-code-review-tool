import pytest
from fastapi.testclient import TestClient
from unittest.mock import MagicMock
from app.main import app
from app.schemas import Finding

@pytest.fixture
def client():
    return TestClient(app)

@pytest.fixture
def fake_model():
    model = MagicMock()
    model.predict.return_value = []
    return model

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

@pytest.fixture(autouse=True)
def inject_fake_model(fake_model):
    app.state.model = fake_model
    yield
    del app.state.model
