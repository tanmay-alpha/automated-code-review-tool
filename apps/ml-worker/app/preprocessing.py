"""Canonical text preprocessing shared by training, evaluation, and serving."""

from __future__ import annotations

import hashlib

SUPPORTED_LANGUAGES = frozenset(
    {
        "python",
        "java",
        "javascript",
        "typescript",
        "go",
        "rust",
        "c",
        "cpp",
        "csharp",
        "ruby",
        "php",
        "unknown",
    }
)
SUPPORTED_MODES = frozenset({"diff", "file"})


def canonicalize_hunk(hunk: str) -> str:
    """Return the persisted hunk identity form: LF endings, no trailing LF."""
    return str(hunk or "").replace("\r\n", "\n").replace("\r", "\n").rstrip("\n")


def hunk_sha256(hunk: str) -> str:
    """Hash canonical structural hunk bytes using lowercase UTF-8 SHA-256."""
    return hashlib.sha256(canonicalize_hunk(hunk).encode("utf-8")).hexdigest()


def build_model_text(hunk: str, language: str, mode: str = "diff") -> str:
    """Return the sole text representation accepted by the classifier.

    Review comments, human rationales, and labels are deliberately not
    accepted by this API. Newlines are normalized so identical hunks have
    byte-for-byte identical model inputs on every platform.
    """
    normalized_language = (language or "unknown").strip().lower()
    normalized_mode = (mode or "diff").strip().lower()
    if normalized_language not in SUPPORTED_LANGUAGES:
        raise ValueError(
            f"Unsupported language {language!r}. "
            f"Allowed: {sorted(SUPPORTED_LANGUAGES)}"
        )
    if normalized_mode not in SUPPORTED_MODES:
        raise ValueError(
            f"Unsupported mode {mode!r}. Allowed: {sorted(SUPPORTED_MODES)}"
        )
    body = "" if hunk is None else str(hunk)
    body = body.replace("\r\n", "\n").replace("\r", "\n")
    return (
        f"[LANGUAGE={normalized_language}]\n"
        f"[MODE={normalized_mode}]\n"
        f"{body}"
    )
