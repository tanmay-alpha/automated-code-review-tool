from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.preprocessing import build_model_text, canonicalize_hunk, hunk_sha256


def test_model_text_normalizes_newlines_and_validates_context() -> None:
    assert build_model_text("+a\r\n+b\r", "JAVA", "DIFF") == (
        "[LANGUAGE=java]\n[MODE=diff]\n+a\n+b\n"
    )
    with pytest.raises(ValueError, match="Unsupported language"):
        build_model_text("+a", "brainfuck")
    with pytest.raises(ValueError, match="Unsupported mode"):
        build_model_text("+a", "java", "comment")


def test_hunk_identity_matches_cross_language_fixture() -> None:
    fixture = Path(__file__).resolve().parents[3] / "contracts" / "hunk_identity_cases.json"
    payload = json.loads(fixture.read_text(encoding="utf-8"))
    assert payload["redactionStage"].startswith("pre-redaction")
    for case in payload["cases"]:
        assert hunk_sha256(case["rawHunk"] + "\r\n") == case["hunkSha256"]
        assert canonicalize_hunk(case["rawHunk"] + "\n\n") == case["rawHunk"]
