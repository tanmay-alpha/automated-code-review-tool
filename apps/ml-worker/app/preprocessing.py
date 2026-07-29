"""
automated-code-review-tool — Shared preprocessing (Phase 1A).

Single canonical function used at train, evaluate, and inference time so the
model always receives the exact same string representation. The human review
comment is NEVER part of the classifier input — it is held out as a future
generation target.
"""
from __future__ import annotations


def build_model_input(diff: str, language: str, mode: str = "diff") -> str:
    """Build the canonical model input from a unified diff.

    Canonical format::

        [LANGUAGE=<language>]
        [MODE=<mode>]
        <unified diff or hunk>

    Args:
        diff: Unified diff text (or hunk) to analyse.
        language: One of ``python``, ``javascript``, ``java``, ``unknown``.
        mode: ``"diff"`` (default) or ``"file"``. ``"file"`` is reserved for
            full-file context scanning.

    Returns:
        The formatted input string. Never raises — empty diffs are passed
        through so callers can decide how to react.

    Raises:
        ValueError: If ``language`` or ``mode`` are not recognised.
    """
    allowed_languages = {"python", "javascript", "java", "unknown"}
    allowed_modes = {"diff", "file"}

    if language not in allowed_languages:
        raise ValueError(
            f"Unsupported language {language!r}. "
            f"Allowed: {sorted(allowed_languages)}"
        )
    if mode not in allowed_modes:
        raise ValueError(
            f"Unsupported mode {mode!r}. Allowed: {sorted(allowed_modes)}"
        )

    if diff is None:
        diff = ""

    return f"[LANGUAGE={language}]\n[MODE={mode}]\n{diff}"