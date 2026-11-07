package com.automatedcodereviewtool.diff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HunkParserTest {

    @Test
    void addedLineStartingWithPlusPlusIsContentNotAFileHeader() {
        String diff = """
                diff --git a/notes.md b/notes.md
                --- a/notes.md
                +++ b/notes.md
                @@ -1,2 +1,3 @@
                 title
                +++ counter
                 end
                """;

        HunkParser.FileDiff file = HunkParser.parse(diff).files().get(0);

        assertThat(file.newPath()).isEqualTo("notes.md");
        HunkParser.FileHunk hunk = file.hunks().get(0);
        assertThat(hunk.addedLines()).containsExactly("++ counter");
        assertThat(hunk.contextLines()).containsExactly("title", "end");
    }

    @Test
    void removedLineStartingWithMinusMinusIsContentNotAFileHeader() {
        String diff = """
                diff --git a/q.sql b/q.sql
                --- a/q.sql
                +++ b/q.sql
                @@ -1,2 +1,1 @@
                --- old comment
                 select 1;
                """;

        HunkParser.FileHunk hunk = HunkParser.parse(diff).files().get(0).hunks().get(0);

        assertThat(hunk.removedLines()).containsExactly("-- old comment");
        assertThat(hunk.contextLines()).containsExactly("select 1;");
    }

    @Test
    void deletedFileHasNoDevNullPath() {
        String diff = """
                diff --git a/gone.py b/gone.py
                deleted file mode 100644
                --- a/gone.py
                +++ /dev/null
                @@ -1,1 +0,0 @@
                -print("bye")
                """;

        HunkParser.FileDiff file = HunkParser.parse(diff).files().get(0);

        assertThat(file.deleted()).isTrue();
        assertThat(file.newPath()).isEqualTo("gone.py");
        assertThat(file.hunks()).extracting(HunkParser.FileHunk::filePath).containsExactly("gone.py");
    }

    @Test
    void multipleFilesAndNoNewlineMarkerAreParsed() {
        String diff = """
                diff --git a/a.py b/a.py
                --- a/a.py
                +++ b/a.py
                @@ -1 +1 @@
                -x = 1
                +x = 2
                \\ No newline at end of file
                diff --git a/b.py b/b.py
                --- a/b.py
                +++ b/b.py
                @@ -3,0 +4,1 @@
                +y = 3
                """;

        List<HunkParser.FileDiff> files = HunkParser.parse(diff).files();

        assertThat(files).extracting(HunkParser.FileDiff::newPath).containsExactly("a.py", "b.py");
        assertThat(files.get(0).hunks().get(0).rawHunk())
                .isEqualTo("@@ -1 +1 @@\n-x = 1\n+x = 2\n\\ No newline at end of file");
        assertThat(files.get(1).hunks().get(0).addedLines()).containsExactly("y = 3");
        assertThat(files.get(1).hunks().get(0).newStart()).isEqualTo(4);
    }
}
