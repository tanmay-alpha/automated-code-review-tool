"""
CodeLens — diff_parser unit tests (Issue #7).

Pure unit tests — no model loading, no network. Fast.

Run from the repo root:
    cd apps/ml-worker && pytest tests/test_diff_parser.py -v
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

# Allow `from app.diff_parser import ...` regardless of where pytest is invoked from.
_HERE = Path(__file__).resolve().parent
_ML_WORKER = _HERE.parent
sys.path.insert(0, str(_ML_WORKER))

from app.diff_parser import FileHunk, hunks_to_text, parse_diff  # noqa: E402


# ----------------------------------------------------------------------
# Fixtures: realistic diff snippets
# ----------------------------------------------------------------------
SINGLE_FILE_DIFF = """\
diff --git a/src/auth/service.py b/src/auth/service.py
index 1234567..89abcde 100644
--- a/src/auth/service.py
+++ b/src/auth/service.py
@@ -10,6 +10,9 @@ def login(user, pwd):
     if not user:
         return None
-    return db.query(f"SELECT * FROM users WHERE name='{user}'")
+    user_row = db.query(
+        "SELECT * FROM users WHERE name = ?", (user,)
+    )
+    return user_row
"""

MULTI_FILE_DIFF = """\
diff --git a/src/service.py b/src/service.py
index 1111111..2222222 100644
--- a/src/service.py
+++ b/src/service.py
@@ -1,3 +1,4 @@
 def foo():
+    return 42
     return 1
diff --git a/src/util.js b/src/util.js
index 3333333..4444444 100644
--- a/src/util.js
+++ b/src/util.js
@@ -1,3 +1,4 @@
 function bar() {
+  return 'hi';
   return null;
 }
diff --git a/src/App.java b/src/App.java
index 5555555..6666666 100644
--- a/src/App.java
+++ b/src/App.java
@@ -1,3 +1,4 @@
 public class App {
+    public static int VALUE = 7;
     public static void main(String[] a) {}
 }
"""

BINARY_DIFF = """\
diff --git a/img/logo.png b/img/logo.png
index abc..def 100644
Binary files a/img/logo.png and b/img/logo.png differ
"""

N_PLUS_ONE_DIFF = """\
diff --git a/src/orders.py b/src/orders.py
index aaa..bbb 100644
--- a/src/orders.py
+++ b/src/orders.py
@@ -3,8 +3,10 @@ def get_orders(user_ids):
     orders = []
     for uid in user_ids:
-        result = db.execute(
-            f"SELECT * FROM orders WHERE user_id = {uid}"
-        )
+        result = db.execute(
+            "SELECT * FROM orders WHERE user_id = ?", (uid,)
+        )
         orders.extend(result.fetchall())
     return orders
"""


# ----------------------------------------------------------------------
# Tests
# ----------------------------------------------------------------------
def test_parses_single_file_diff():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    assert len(hunks) == 1
    h = hunks[0]
    assert h.file_path == "src/auth/service.py"
    assert h.language == "python"
    # 1 line removed, 3 lines added.
    assert len(h.removed_lines) == 1
    assert len(h.added_lines) == 3
    assert "db.query" in h.removed_lines[0]


def test_parses_multi_file_diff():
    hunks = parse_diff(MULTI_FILE_DIFF)
    assert len(hunks) == 3
    paths = [h.file_path for h in hunks]
    assert paths == ["src/service.py", "src/util.js", "src/App.java"]
    languages = [h.language for h in hunks]
    assert languages == ["python", "javascript", "java"]


def test_skips_binary_files():
    # Binary file is the ONLY entry — result should be empty.
    hunks = parse_diff(BINARY_DIFF)
    assert hunks == []
    # Mixed diff: binary file is skipped, text file is kept.
    mixed = BINARY_DIFF + SINGLE_FILE_DIFF
    hunks = parse_diff(mixed)
    assert len(hunks) == 1
    assert hunks[0].file_path == "src/auth/service.py"


def test_raises_on_empty_diff():
    with pytest.raises(ValueError, match="diff must not be empty"):
        parse_diff("")
    with pytest.raises(ValueError, match="diff must not be empty"):
        parse_diff("   \n\n  \n")
    with pytest.raises(ValueError, match="diff must not be empty"):
        parse_diff(None)  # type: ignore[arg-type]


def test_infers_python_language():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    assert hunks[0].language == "python"
    # Path variants still resolve to python.
    diff = SINGLE_FILE_DIFF.replace("src/auth/service.py", "lib/deep/nested/foo.py")
    assert parse_diff(diff)[0].language == "python"


def test_infers_javascript_language():
    # Both .js and .ts should map to javascript per the plan.
    js_diff = SINGLE_FILE_DIFF.replace("src/auth/service.py", "web/foo.js")
    assert parse_diff(js_diff)[0].language == "javascript"
    ts_diff = SINGLE_FILE_DIFF.replace("src/auth/service.py", "web/foo.ts")
    assert parse_diff(ts_diff)[0].language == "javascript"


def test_added_and_removed_lines_separated():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    h = hunks[0]
    # None of the removed lines should appear in added_lines and vice versa.
    assert not set(h.removed_lines) & set(h.added_lines)
    # The SQL f-string line should be in removed, not added.
    assert any("f\"SELECT" in line for line in h.removed_lines)
    # The parameterized query should be in added, not removed.
    assert any("= ?" in line for line in h.added_lines)


def test_n_plus_1_diff_structure():
    """Real N+1 example: parser should produce a clean hunk with
    a removed f-string query and an added parameterized query."""
    hunks = parse_diff(N_PLUS_ONE_DIFF)
    assert len(hunks) == 1
    h = hunks[0]
    assert h.file_path == "src/orders.py"
    assert h.language == "python"

    # Removed: the old f-string query (broken across 2 lines in the diff).
    removed_blob = "\n".join(h.removed_lines)
    assert "SELECT * FROM orders WHERE user_id = {uid}" in removed_blob
    assert "f\"" in removed_blob

    # Added: parameterized query with placeholders.
    added_blob = "\n".join(h.added_lines)
    assert "WHERE user_id = ?" in added_blob
    assert "f\"" not in added_blob

    # hunks_to_text should preserve both sides.
    text = hunks_to_text(hunks)
    assert "# file: src/orders.py" in text
    assert "-     result = db.execute(" in text or "-         result = db.execute(" in text
    assert "+ " in text


def test_hunks_to_text_includes_added_and_removed():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    text = hunks_to_text(hunks)
    assert "# file: src/auth/service.py" in text
    assert "-    return db.query" in text
    assert "+    user_row = db.query" in text


def test_returns_list_of_filehunks():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    assert all(isinstance(h, FileHunk) for h in hunks)
