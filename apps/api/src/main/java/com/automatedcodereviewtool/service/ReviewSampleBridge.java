package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.diff.HunkParser;
import com.automatedcodereviewtool.entity.CodeSample;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Glue between {@link HunkParser} and {@link CodeSampleService}.
 *
 * <p>After inference succeeds, every hunk is persisted in redacted form as
 * a {@code ml.code_samples} row in the same short persistence transaction as
 * the review's findings and outbox reference. Findings are linked to these
 * samples later by {@link OutboxProcessor}.</p>
 */
@Service
public class ReviewSampleBridge {

    private final CodeSampleService codeSampleService;

    public ReviewSampleBridge(CodeSampleService codeSampleService) {
        this.codeSampleService = codeSampleService;
    }

    /**
     * Persist code samples for every hunk in the diff. Returns the
     * ordered list of persisted {@link CodeSample} entities (one per
     * hunk) in the same order as the parsed hunks.
     */
    public List<CodeSample> persistHunksForReview(UUID repositoryId,
                                                  UUID pullRequestId,
                                                  String commitSha,
                                                  String diff) {
        HunkParser.ParseResult parsed = HunkParser.parse(diff);
        List<CodeSample> samples = new ArrayList<>();
        for (HunkParser.FileDiff file : parsed.files()) {
            String newPath = file.newPath() == null ? file.oldPath() : file.newPath();
            for (HunkParser.FileHunk hunk : file.hunks()) {
                String added = String.join("\n", hunk.addedLines());
                String context = String.join("\n", hunk.contextLines());
                CodeSampleService.PersistRequest req = new CodeSampleService.PersistRequest(
                        repositoryId,
                        pullRequestId,
                        commitSha,
                        newPath,
                        hunk.language(),
                        hunk.oldStart(),
                        hunk.oldCount(),
                        hunk.newStart(),
                        hunk.newCount(),
                        hunk.rawHunk(),
                        added,
                        context,
                        "pr_diff"
                );
                samples.add(codeSampleService.persistSample(req));
            }
        }
        return samples;
    }
}
