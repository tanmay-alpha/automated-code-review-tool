"""
Unified-diff parser unit tests.

Pure unit tests — no model loading, no network. Fast.

Run from the repo root:
    cd apps/ml-worker && pytest tests/test_diff_parser.py -v
"""
from __future__ import annotations

import json
from pathlib import Path

from app.diff_parser import FileHunk, parse_diff
from app.preprocessing import hunk_sha256


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
    # The hand-written header counts are not used: parse_diff counts the
    # actual "+" and "-" lines.
    assert len(h.removed_lines) == 1
    assert len(h.added_lines) == 4
    assert "db.query" in h.removed_lines[0].text


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


def test_empty_diff_has_no_hunks():
    assert parse_diff("") == []
    assert parse_diff("   \n\n  \n") == []


def test_malformed_hunk_header_is_skipped_like_java():
    diff = (
        "diff --git a/x.py b/x.py\n@@ nonsense @@\n+x = 1\n"
        "@@ -0,0 +1 @@\n+y = 2\n"
    )
    [hunk] = parse_diff(diff)
    assert [line.text for line in hunk.added_lines] == ["y = 2"]


def test_header_lookalikes_inside_a_hunk_body_are_content():
    # Counts in "@@ -1,2 +1,2 @@" say both lines below belong to the body.
    diff = "diff --git a/a.py b/a.py\n@@ -1,2 +1,2 @@\n--- y\n+++ x\n"
    [hunk] = parse_diff(diff)
    assert hunk.file_path == "a.py"
    assert [line.text for line in hunk.removed_lines] == ["-- y"]
    assert [line.text for line in hunk.added_lines] == ["++ x"]


def test_hunk_body_stops_at_header_counts():
    diff = (
        "diff --git a/a.py b/a.py\n@@ -1 +1,2 @@\n x = 1\n+y = 2\n"
        "\\ No newline at end of file\n"
        "trailing junk that is not part of the hunk\n"
    )
    [hunk] = parse_diff(diff)
    assert hunk.raw_hunk == "@@ -1 +1,2 @@\n x = 1\n+y = 2\n\\ No newline at end of file"


def test_git_header_path_uses_first_b_slash_like_java():
    [hunk] = parse_diff("diff --git a/x b/y b/z.py\n@@ -0,0 +1 @@\n+v = 1\n")
    assert hunk.file_path == "y b/z.py"


def test_infers_python_language():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    assert hunks[0].language == "python"
    # Path variants still resolve to python.
    diff = SINGLE_FILE_DIFF.replace("src/auth/service.py", "lib/deep/nested/foo.py")
    assert parse_diff(diff)[0].language == "python"


def test_language_detection_matches_java_hunk_parser():
    # Same extension table as HunkParser.detectLanguage in apps/api, so the
    # model sees the same [LANGUAGE=...] tag at training and serving time.
    expected = {
        "web/foo.js": "javascript",
        "web/foo.jsx": "javascript",
        "web/foo.ts": "typescript",
        "web/foo.tsx": "typescript",
        "src/App.java": "java",
        "cmd/main.go": "go",
        "src/lib.rs": "rust",
        "native/engine.c": "c",
        "native/engine.h": "c",
        "native/engine.cpp": "cpp",
        "src/Program.cs": "csharp",
        "app/models/user.rb": "ruby",
        "index.php": "php",
        "db/V1__init.sql": "unknown",
        "Dockerfile": "unknown",
    }
    for path, language in expected.items():
        diff = SINGLE_FILE_DIFF.replace("src/auth/service.py", path)
        assert parse_diff(diff)[0].language == language, path


def test_added_and_removed_lines_separated():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    h = hunks[0]
    # None of the removed lines should appear in added_lines and vice versa.
    removed_texts = {line.text for line in h.removed_lines}
    added_texts = {line.text for line in h.added_lines}
    assert not removed_texts & added_texts
    # The SQL f-string line should be in removed, not added.
    assert any("f\"SELECT" in line.text for line in h.removed_lines)
    # The parameterized query should be in added, not removed.
    assert any("= ?" in line.text for line in h.added_lines)


def test_n_plus_1_diff_structure():
    """Real N+1 example: parser should produce a clean hunk with
    a removed f-string query and an added parameterized query."""
    hunks = parse_diff(N_PLUS_ONE_DIFF)
    assert len(hunks) == 1
    h = hunks[0]
    assert h.file_path == "src/orders.py"
    assert h.language == "python"
    assert h.new_start == 3
    assert h.new_count == 10

    # Removed: the old f-string query (broken across 2 lines in the diff).
    removed_blob = "\n".join(line.text for line in h.removed_lines)
    assert "SELECT * FROM orders WHERE user_id = {uid}" in removed_blob
    assert "f\"" in removed_blob

    # Added: parameterized query with placeholders.
    added_blob = "\n".join(line.text for line in h.added_lines)
    assert "WHERE user_id = ?" in added_blob
    assert "f\"" not in added_blob

    assert "db.execute(" in h.raw_hunk
    assert "+        )" in h.raw_hunk


def test_raw_hunk_keeps_both_sides():
    raw = parse_diff(SINGLE_FILE_DIFF)[0].raw_hunk
    assert raw.startswith("@@ -10,6 +10,9 @@")
    assert "-    return db.query" in raw
    assert "+    user_row = db.query" in raw


def test_trailing_blank_lines_do_not_change_hunk_identity():
    # Java canonicalizes trailing newlines away before hashing; so must we.
    with_gap = SINGLE_FILE_DIFF + "\n\n" + MULTI_FILE_DIFF
    first = parse_diff(with_gap)[0]
    assert not first.raw_hunk.endswith("\n")
    assert first.hunk_sha256 == parse_diff(SINGLE_FILE_DIFF)[0].hunk_sha256
    assert first.hunk_sha256 == hunk_sha256(first.raw_hunk)


def test_whitespace_stripped_context_lines_keep_line_numbers():
    # Some tools strip the single space that marks an empty context line.
    diff = (
        "diff --git a/a.py b/a.py\n"
        "@@ -1,3 +1,4 @@\n"
        " def f():\n"
        "\n"
        "+    x = 1\n"
        "     return 2\n"
    )
    hunk = parse_diff(diff)[0]
    assert hunk.added_lines[0].new_line == 3


def test_form_feed_is_not_a_line_break():
    diff = "diff --git a/a.py b/a.py\n@@ -0,0 +1,2 @@\n+a = 1\x0c\n+b = 2\n"
    hunk = parse_diff(diff)[0]
    assert [line.new_line for line in hunk.added_lines] == [1, 2]


def test_plain_unified_diff_without_git_headers():
    diff = (
        "--- a/one.py\n+++ b/one.py\n@@ -1 +1,2 @@\n x = 1\n+y = 2\n"
        "--- a/two.js\n+++ b/two.js\n@@ -1 +1,2 @@\n let a;\n+let b;\n"
    )
    hunks = parse_diff(diff)
    assert [(h.file_path, h.language) for h in hunks] == [
        ("one.py", "python"),
        ("two.js", "javascript"),
    ]
    assert [len(h.added_lines) for h in hunks] == [1, 1]


def test_returns_list_of_filehunks():
    hunks = parse_diff(SINGLE_FILE_DIFF)
    assert all(isinstance(h, FileHunk) for h in hunks)


def test_shared_hunk_identity_contract() -> None:
    fixture_path = (
        Path(__file__).resolve().parents[3]
        / "contracts"
        / "hunk_identity_cases.json"
    )
    contract = json.loads(fixture_path.read_text(encoding="utf-8"))
    case = contract["cases"][0]
    diff = (
        "diff --git a/demo.js b/demo.js\r\n"
        "--- a/demo.js\r\n"
        "+++ b/demo.js\r\n"
        + case["rawHunk"].replace("\n", "\r\n")
    )
    hunk = parse_diff(diff)[0]
    assert hunk.raw_hunk == case["rawHunk"]
    assert hunk.hunk_sha256 == case["hunkSha256"]


def test_git_quoted_non_ascii_path_is_decoded_and_reviewed():
    # git's default core.quotePath writes non-ASCII paths as C-quoted octal.
    diff = (
        'diff --git "a/app/caf\\303\\251.py" "b/app/caf\\303\\251.py"\n'
        "new file mode 100644\n"
        "--- /dev/null\n"
        '+++ "b/app/caf\\303\\251.py"\n'
        "@@ -0,0 +1 @@\n"
        "+print(1)\n"
    )
    [hunk] = parse_diff(diff)
    assert hunk.file_path == "app/café.py"
    assert hunk.language == "python"


def test_quoted_path_escapes_and_unquoted_paths_with_spaces():
    [quoted] = parse_diff(
        'diff --git "a/dir/tab\\there.js" "b/dir/tab\\there.js"\n@@ -0,0 +1 @@\n+x;\n'
    )
    assert quoted.file_path == "dir/tab\there.js"
    [spaced] = parse_diff("diff --git a/my dir/a.py b/my dir/a.py\n@@ -0,0 +1 @@\n+x = 1\n")
    assert spaced.file_path == "my dir/a.py"


def test_binary_marker_does_not_hide_the_next_plain_diff_file():
    diff = (
        "Binary files a/logo.png and b/logo.png differ\n"
        "--- a/app.py\n+++ b/app.py\n@@ -1 +1,2 @@\n x = 1\n+y = 2\n"
    )
    [hunk] = parse_diff(diff)
    assert hunk.file_path == "app.py"
