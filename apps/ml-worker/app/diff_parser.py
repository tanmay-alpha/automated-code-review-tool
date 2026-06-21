"""
CodeLens — Unified diff parser (Issue #7).

Parses a unified-diff string into a list of per-file FileHunk objects,
handles multiple files in one diff, skips binary files silently, and
refuses empty input. Very long diffs are NOT truncated here — the
sliding-window tokenizer (tokenizer_utils.py) handles length downstream.

Exports:
    FileHunk                 — dataclass-like class
    parse_diff(diff_text)    — main entry point
    hunks_to_text(hunks)     — flatten hunks to a single string
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import PurePosixPath


# ----------------------------------------------------------------------
# Public data class
# ----------------------------------------------------------------------
@dataclass
class FileHunk:
    """A parsed section of a unified diff covering one file."""

    file_path: str
    added_lines: list[str] = field(default_factory=list)
    removed_lines: list[str] = field(default_factory=list)
    language: str = "unknown"


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------
LANGUAGE_BY_EXT: dict[str, str] = {
    ".py": "python",
    ".js": "javascript",
    ".ts": "javascript",   # .ts grouped with JS as the plan specifies
    ".java": "java",
}


def infer_language(file_path: str) -> str:
    """Infer language from file extension. Returns 'unknown' if unmapped."""
    if not file_path:
        return "unknown"
    ext = PurePosixPath(file_path).suffix.lower()
    return LANGUAGE_BY_EXT.get(ext, "unknown")


# Regexes used during parsing.
_GIT_HEADER_RE = re.compile(r"^diff --git a/(.+?) b/(.+?)\s*$")
_DEV_NULL_RE = re.compile(r"^diff --git a/(.+?) b/(.+?)\s*$")
_PLUS_FILE_RE = re.compile(r"^\+\+\+\s+(?:b/)?(.+?)\s*$")
_MINUS_FILE_RE = re.compile(r"^---\s+(?:a/)?(.+?)\s*$")
_BINARY_PATCH_RE = re.compile(r"^GIT binary patch$")
_BINARY_DIFFER_RE = re.compile(r"^Binary files .* differ$")


def _is_binary_marker(line: str) -> bool:
    return bool(_BINARY_PATCH_RE.match(line) or _BINARY_DIFFER_RE.match(line))


def _extract_file_path(file_block: list[str]) -> str:
    """Extract the destination file path from a per-file diff block."""
    # Prefer the path from the `diff --git` header (post-rename destination).
    for line in file_block:
        m = _GIT_HEADER_RE.match(line)
        if m:
            # m.group(2) is the b/ side.
            return m.group(2)
    # Fallback: `+++ b/path` (most reliable for non-git diffs).
    for line in file_block:
        m = _PLUS_FILE_RE.match(line)
        if m and m.group(1) != "/dev/null":
            return m.group(1)
    # Last resort: `--- a/path`.
    for line in file_block:
        m = _MINUS_FILE_RE.match(line)
        if m and m.group(1) != "/dev/null":
            return m.group(1)
    return ""


def _split_blocks(diff_text: str) -> list[list[str]]:
    """Split a multi-file diff into per-file blocks.

    A new block begins at each `diff --git` line. Lines before the first
    `diff --git` (e.g., a commit header) are attached to the first block.
    """
    lines = diff_text.splitlines()
    blocks: list[list[str]] = []
    current: list[str] = []
    for line in lines:
        if _GIT_HEADER_RE.match(line):
            if current:
                blocks.append(current)
            current = [line]
        else:
            current.append(line)
    if current:
        blocks.append(current)
    return blocks


# ----------------------------------------------------------------------
# Public API
# ----------------------------------------------------------------------
def parse_diff(diff_text: str) -> list[FileHunk]:
    """
    Parse a unified diff into a list of FileHunk objects.

    Empty input raises ValueError. Binary files are skipped silently
    (no entry is returned for them). Multiple files in one diff are
    split correctly. Diff content is never truncated — length is the
    tokenizer's problem.
    """
    if diff_text is None or not diff_text.strip():
        raise ValueError("diff must not be empty")

    hunks: list[FileHunk] = []
    for block in _split_blocks(diff_text):
        # Binary-file check: skip the whole file silently.
        if any(_is_binary_marker(line) for line in block):
            continue

        file_path = _extract_file_path(block)
        added: list[str] = []
        removed: list[str] = []
        in_hunk = False

        for line in block:
            if line.startswith("@@"):
                in_hunk = True
                continue
            if not in_hunk:
                continue
            if line.startswith("+++") or line.startswith("---"):
                # File-path markers, not content.
                continue
            if line.startswith("+"):
                added.append(line[1:])
            elif line.startswith("-"):
                removed.append(line[1:])
            # `\` lines (no newline at EOF) and other context are ignored.

        hunks.append(
            FileHunk(
                file_path=file_path,
                added_lines=added,
                removed_lines=removed,
                language=infer_language(file_path),
            )
        )

    return hunks


def hunks_to_text(hunks: list[FileHunk]) -> str:
    """
    Flatten hunks into a single string suitable for tokenization.

    Format: each hunk contributes its file path as a header, followed by
    `- removed` lines and `+ added` lines. Different files are separated
    by blank lines.
    """
    parts: list[str] = []
    for h in hunks:
        if h.file_path:
            parts.append(f"# file: {h.file_path}")
        for line in h.removed_lines:
            parts.append(f"- {line}")
        for line in h.added_lines:
            parts.append(f"+ {line}")
        parts.append("")  # blank line between files
    return "\n".join(parts)