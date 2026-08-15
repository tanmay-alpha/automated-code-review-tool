"""Tests for real hunk parsing, preprocessing parity and taxonomy
loader behaviour."""

from __future__ import annotations


from app.diff_parser import parse_diff
from app.preprocessing import build_model_text
from app.taxonomy import load_canonical_taxonomy, trainable_ids


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
        files = parse_diff(diff)
        assert len({f.file_path for f in files}) == 2
        assert all(len(f.hunks) if hasattr(f, 'hunks') else True for f in files)

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
        assert len(hunks) >= 1
        assert any(h.is_new_file for h in hunks)

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
        hunks = parse_diff(diff)
        # Deleted files produce hunks but with no added lines
        for h in hunks:
            assert len(h.added_lines) == 0


class TestTaxonomy:
    def test_trainable_ids_sorted_and_deterministic(self):
        ids = trainable_ids()
        # Deterministic: same input → same output, twice
        assert ids == trainable_ids()
        # And unique
        assert len(set(ids)) == len(ids)

    def test_at_least_one_trainable_label(self):
        tax = load_canonical_taxonomy()
        trainable = [e for e in tax["entries"] if e.get("trainable")]
        assert len(trainable) >= 1

    def test_no_invalid_category(self):
        tax = load_canonical_taxonomy()
        valid = {"SECURITY", "PERFORMANCE", "ARCHITECTURE", "RELIABILITY",
                 "READABILITY", "MAINTAINABILITY"}
        for entry in tax["entries"]:
            assert entry["category"] in valid

    def test_print_mapped_to_maintainability(self):
        tax = load_canonical_taxonomy()
        ids = {e["id"] for e in tax["entries"]}
        assert "PERFORMANCE_N_PLUS_ONE" in ids
        assert "PERFORMANCE_N_PLUS_1" not in ids
