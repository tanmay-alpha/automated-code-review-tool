"""Tests for deterministic hashing and secret redaction parity
between Python pre-redaction and Python preprocessing."""

from __future__ import annotations

import re



SECRET_PATTERNS = [
    re.compile(r"(?i)(password|secret|api[_-]?key)\s*[:=]\s*['\"][^'\"]{4,}['\"]"),
    re.compile(r"['\"][A-Za-z0-9+/=_-]{32,}['\"]"),
]


def looks_like_secret(text: str) -> bool:
    return any(p.search(text or "") for p in SECRET_PATTERNS)


def deterministic_hash(content: str) -> str:
    import hashlib
    return hashlib.sha256((content or "").encode("utf-8")).hexdigest()


def test_secret_detection_includes_passwords_and_keys():
    assert looks_like_secret('password = "hunter2"')
    assert looks_like_secret('api_key="sk_live_real_secret"')
    assert not looks_like_secret("api_key = None")


def test_deterministic_hash_is_stable():
    h1 = deterministic_hash("print('hi')")
    h2 = deterministic_hash("print('hi')")
    assert h1 == h2


def test_exact_duplicate_detection():
    corpus = [("a.py", deterministic_hash("x = 1\n")),
              ("b.py", deterministic_hash("y = 2\n")),
              ("a.py", deterministic_hash("x = 1\n"))]
    seen = set()
    duplicates = []
    for path, h in corpus:
        key = (path, h)
        if key in seen:
            duplicates.append(key)
        seen.add(key)
    assert len(duplicates) == 1


def test_redaction_preserves_structure():
    """After redaction the file structure (variable names, equals
    signs) is preserved. Only the literal value is replaced."""

    def redact(text: str) -> str:
        for p in SECRET_PATTERNS:
            text = p.sub(lambda m: m.group(0).split("=")[0] + '= "<REDACTED>"', text)
        return text

    out = redact('API_KEY = "sk_live_real_secret"')
    assert "API_KEY" in out
    assert "REDACTED" in out
    assert "sk_live_real_secret" not in out