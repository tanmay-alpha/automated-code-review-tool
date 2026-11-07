"""Tests for hunk parsing edge cases and model-input preprocessing."""

from __future__ import annotations

from app.diff_parser import parse_diff
from app.preprocessing import build_model_text


SIMPLE_DIFF = """\
diff --git a/a.py b/a.py
index e69de29..bcd1234 100644
--- a/a.py
+++ b/a.py
@@ -0,0 +1,3 @@
+def hello():
+    print("hi")
+
"""


class TestBuildModelText:
    def test_prefix_contains_language_and_mode(self):
        out = build_model_text(SIMPLE_DIFF, language="python", mode="diff")
        assert out.startswith("[LANGUAGE=python]\n[MODE=diff]\n")

    def test_body_contains_diff(self):
        out = build_model_text(SIMPLE_DIFF, language="python")
        assert "def hello" in out
        assert "print" in out

    def test_multiline_diff(self):
        long = "+line\n" * 20
        out = build_model_text(long, language="python")
        assert out.startswith("[LANGUAGE=python]\n")
        assert "+line" in out


class TestHunkParser:
    def test_multi_file_multi_hunk(self):
        diff = """\
diff --git a/a.py b/a.py
--- a/a.py
+++ b/a.py
@@ -1,2 +1,4 @@
- line1
+ line1
+ line2
+ line3
diff --git a/b.py b/b.py
--- a/b.py
+++ b/b.py
@@ -0,0 +1,2 @@
+ hello
+ world
"""
        hunks = parse_diff(diff)
        assert [h.file_path for h in hunks] == ["a.py", "b.py"]
        assert [len(h.added_lines) for h in hunks] == [3, 2]

    def test_new_file(self):
        diff = """\
diff --git a/new.py b/new.py
new file mode 100644
--- /dev/null
+++ b/new.py
@@ -0,0 +1,1 @@
+print(1)
"""
        hunks = parse_diff(diff)
        assert len(hunks) == 1
        assert hunks[0].file_path == "new.py"
        assert hunks[0].added_lines[0].new_line == 1

    def test_renamed_file(self):
        diff = """\
diff --git a/old.py b/new.py
rename from old.py
rename to new.py
--- a/old.py
+++ b/new.py
@@ -1 +1 @@
-old_body
+new_body
"""
        hunks = parse_diff(diff)
        assert any(h.file_path == "new.py" for h in hunks)

    def test_language_detection(self):
        diff = """\
diff --git a/main.rs b/main.rs
--- a/main.rs
+++ b/main.rs
@@ -0,0 +1,1 @@
+fn main() {}
"""
        hunks = parse_diff(diff)
        assert len(hunks) == 1
        assert hunks[0].language == "rust"

    def test_binary_file_skipped(self):
        diff = """\
diff --git a/logo.png b/logo.png
Binary files differ
"""
        assert parse_diff(diff) == []

    def test_deleted_file_no_new_defect(self):
        diff = """\
diff --git a/a.py b/a.py
deleted file mode 100644
--- a/a.py
+++ /dev/null
@@ -1,2 +0,0 @@
-line1
-line2
"""
        # Only removed lines: nothing new to review.
        assert parse_diff(diff) == []
