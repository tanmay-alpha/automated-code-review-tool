package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.diff.HunkParser;
import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.entity.Finding;
import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.dto.MlReviewResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Glue between {@link HunkParser} and {@link CodeSampleService}.
 *
 * <p>Before a PR scan invokes inference, every hunk in the diff is
 * persisted (redacted) as a {@code ml.code_samples} row. The
 * resulting sample IDs are then stamped onto the {@code findings}
 * that the ML worker emits for that hunk.</p>
 */
@Service
public class ReviewSampleBridge {

    private static final Logger log = LoggerFactory.getLogger(ReviewSampleBridge.class);

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

    /**
     * Tag each finding with the corresponding code sample id.
     * The mapping is best-effort: a finding that does not match a
     * known hunk (line range) is left without a sample id.
     */
    public void stampFindings(List<Finding> findings,
                              List<CodeSample> samples,
                              MlReviewResponse response) {
        if (response == null || response.findings() == null) return;
        List<MlFinding> mlFindings = response.findings();
        for (int i = 0; i < findings.size() && i < mlFindings.size(); i++) {
            Finding f = findings.get(i);
            MlFinding mf = mlFindings.get(i);
            CodeSample match = findMatch(samples, mf);
            if (match != null) {
                f.setCodeSampleId(match.getId());
            }
        }
    }

    private CodeSample findMatch(List<CodeSample> samples, MlFinding mf) {
        if (samples.isEmpty()) return null;
        if (mf == null || mf.lineStart() == null) {
            return null;
        }
        for (CodeSample s : samples) {
            if (s.getNewStart() <= mf.lineStart()
                    && mf.lineEnd() != null
                    && mf.lineEnd() <= s.getNewStart() + Math.max(s.getNewCount(), 1)) {
                return s;
            }
        }
        return null;
    }
}