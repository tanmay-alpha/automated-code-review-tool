package com.automatedcodereviewtool.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses unified-diff text into {@link FileHunk} objects (one per
 * unified-diff hunk, not one per file). Multi-file, multi-hunk,
 * rename-aware, and skips binary files.
 *
 * <p>Hunk bodies are delimited by the line counts in the {@code @@}
 * header, so an added line whose text starts with {@code ++ } (raw
 * {@code +++ …}) is still read as content, not as a file header.</p>
 */
public final class HunkParser {

    private HunkParser() {}

    /** Result for a single file in the diff. */
    public record FileDiff(
            String oldPath,
            String newPath,
            boolean created,
            boolean deleted,
            boolean renamed,
            boolean binary,
            List<FileHunk> hunks
    ) {}

    public record FileHunk(
            String filePath,
            String language,
            int oldStart,
            int oldCount,
            int newStart,
            int newCount,
            List<String> addedLines,
            List<String> removedLines,
            List<String> contextLines,
            String rawHunk
    ) {}

    public record ParseResult(List<FileDiff> files) {}

    public static ParseResult parse(String unifiedDiff) {
        List<FileDiff> files = new ArrayList<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) return new ParseResult(files);

        String normalizedDiff = unifiedDiff.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalizedDiff.split("\n", -1);
        FileDiff currentFile = null;
        HunkBuilder currentHunk = null;
        int oldRemaining = 0;
        int newRemaining = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean inHunkBody = currentHunk != null && (oldRemaining > 0 || newRemaining > 0);

            if (inHunkBody && !line.startsWith("diff --git ")) {
                currentHunk.appendRaw(line);
                if (line.startsWith("+")) {
                    currentHunk.addAdded(line.substring(1));
                    newRemaining--;
                } else if (line.startsWith("-")) {
                    currentHunk.addRemoved(line.substring(1));
                    oldRemaining--;
                } else if (line.startsWith("\\")) {
                    // "\ No newline at end of file": part of hunk identity, not a source line.
                } else {
                    // " text", or an empty line some tools emit for a blank context line.
                    currentHunk.addContext(line.startsWith(" ") ? line.substring(1) : line);
                    oldRemaining--;
                    newRemaining--;
                }
                continue;
            }

            if (currentHunk != null && line.startsWith("\\")) {
                // Metadata line that follows the final body line of a hunk.
                currentHunk.appendRaw(line);
                continue;
            }

            if (line.startsWith("diff --git ")) {
                if (currentFile != null) {
                    if (currentHunk != null) currentFile.hunks().add(currentHunk.build());
                    files.add(currentFile);
                }
                currentFile = new FileDiff(null, null, false, false, false, false, new ArrayList<>());
                currentHunk = null;
                int bIdx = line.indexOf(" b/");
                if (bIdx > 0) {
                    currentFile = new FileDiff(
                            null,
                            line.substring(bIdx + 3),
                            false, false, false, false, new ArrayList<>()
                    );
                }
                continue;
            }

            if (line.startsWith("new file")) {
                if (currentFile != null) {
                    currentFile = new FileDiff(null, currentFile.newPath(), true, false,
                            currentFile.renamed(), currentFile.binary(), currentFile.hunks());
                }
                continue;
            }
            if (line.startsWith("deleted file")) {
                if (currentFile != null) {
                    currentFile = new FileDiff(currentFile.oldPath(), null, false, true,
                            currentFile.renamed(), currentFile.binary(), currentFile.hunks());
                }
                continue;
            }
            if (line.startsWith("rename ")) {
                // `rename from path` / `rename to path` — pick destination
                if (line.startsWith("rename to ") && currentFile != null) {
                    String dst = line.substring("rename to ".length()).trim();
                    currentFile = new FileDiff(currentFile.oldPath(), dst,
                            false, false, true, currentFile.binary(), currentFile.hunks());
                }
                continue;
            }
            if (line.startsWith("Binary files")) {
                if (currentFile != null) {
                    currentFile = new FileDiff(currentFile.oldPath(), currentFile.newPath(),
                            currentFile.created(), currentFile.deleted(),
                            currentFile.renamed(), true, currentFile.hunks());
                }
                continue;
            }
            if (line.startsWith("--- ")) {
                String path = stripDiffPrefix(line.substring(4));
                if (currentFile != null && currentFile.newPath() == null) {
                    currentFile = new FileDiff(path, currentFile.newPath(),
                            currentFile.created(), currentFile.deleted(),
                            currentFile.renamed(), currentFile.binary(), currentFile.hunks());
                }
                continue;
            }
            if (line.startsWith("+++ ")) {
                String path = stripDiffPrefix(line.substring(4));
                if (currentFile != null) {
                    // "+++ /dev/null" means the file was deleted: there is no new path.
                    String newPath = "/dev/null".equals(path) ? null : path;
                    currentFile = new FileDiff(currentFile.oldPath(), newPath,
                            currentFile.created(), currentFile.deleted() || newPath == null,
                            currentFile.renamed(), currentFile.binary(), currentFile.hunks());
                }
                continue;
            }
            if (line.startsWith("@@")) {
                if (currentHunk != null && currentFile != null) {
                    currentFile.hunks().add(currentHunk.build());
                }
                HunkHeader h = parseHunkHeader(line);
                if (h == null || currentFile == null) {
                    currentHunk = null;
                    continue;
                }
                String newPath = currentFile.newPath() == null ? currentFile.oldPath() : currentFile.newPath();
                currentHunk = new HunkBuilder(
                        newPath, h.oldStart(), h.oldCount(), h.newStart(), h.newCount(), line);
                oldRemaining = h.oldCount();
                newRemaining = h.newCount();
            }
            // Anything else outside a hunk body (index lines, mode lines,
            // a trailing empty line) carries no content.
        }
        if (currentFile != null) {
            if (currentHunk != null) currentFile.hunks().add(currentHunk.build());
            files.add(currentFile);
        }

        // Final pass: detect language per file from extension.
        List<FileDiff> normalized = new ArrayList<>(files.size());
        for (FileDiff f : files) {
            if (f.binary()) continue;
            String newPath = f.newPath() == null ? f.oldPath() : f.newPath();
            String lang = detectLanguage(newPath);
            List<FileHunk> hunks = new ArrayList<>(f.hunks().size());
            for (FileHunk h : f.hunks()) {
                hunks.add(new FileHunk(
                        newPath, lang,
                        h.oldStart(), h.oldCount(), h.newStart(), h.newCount(),
                        h.addedLines(), h.removedLines(), h.contextLines(),
                        h.rawHunk()));
            }
            normalized.add(new FileDiff(f.oldPath(), newPath, f.created(), f.deleted(),
                    f.renamed(), f.binary(), hunks));
        }
        return new ParseResult(normalized);
    }

    private static String stripDiffPrefix(String s) {
        s = s.trim();
        if (s.startsWith("a/") || s.startsWith("b/")) s = s.substring(2);
        return s;
    }

    private record HunkHeader(int oldStart, int oldCount, int newStart, int newCount) {}

    private static HunkHeader parseHunkHeader(String line) {
        // @@ -oldStart,oldCount +newStart,newCount @@
        try {
            int start = line.indexOf("@@");
            int end = line.indexOf("@@", start + 2);
            if (start < 0 || end < 0) return null;
            String body = line.substring(start + 2, end).trim();
            String[] parts = body.split(" ");
            String left = parts[0].substring(1);   // drop leading '-'
            String right = parts[1].substring(1); // drop leading '+'
            int oldStart = Integer.parseInt(left.split(",")[0]);
            int oldCount = left.contains(",") ? Integer.parseInt(left.split(",")[1]) : 1;
            int newStart = Integer.parseInt(right.split(",")[0]);
            int newCount = right.contains(",") ? Integer.parseInt(right.split(",")[1]) : 1;
            return new HunkHeader(oldStart, oldCount, newStart, newCount);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String detectLanguage(String path) {
        if (path == null) return "unknown";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".c") || lower.endsWith(".h")) return "c";
        if (lower.endsWith(".cpp") || lower.endsWith(".hpp") || lower.endsWith(".cc")) return "cpp";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".php")) return "php";
        return "unknown";
    }

    private static final class HunkBuilder {
        private final String filePath;
        private final int oldStart, oldCount, newStart, newCount;
        private final List<String> added = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private final List<String> context = new ArrayList<>();
        private final StringBuilder raw = new StringBuilder();

        HunkBuilder(String filePath, int oldStart, int oldCount, int newStart, int newCount,
                    String header) {
            this.filePath = filePath;
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            raw.append(header).append('\n');
        }

        void appendRaw(String line) { raw.append(line).append('\n'); }
        void addAdded(String text) { added.add(text); }
        void addRemoved(String text) { removed.add(text); }
        void addContext(String text) { context.add(text); }
        FileHunk build() {
            return new FileHunk(filePath, detectLanguage(filePath),
                    oldStart, oldCount, newStart, newCount,
                    List.copyOf(added), List.copyOf(removed), List.copyOf(context),
                    stripTrailingLf(raw.toString()));
        }

        private static String stripTrailingLf(String value) {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '\n') end--;
            return value.substring(0, end);
        }
    }
}
