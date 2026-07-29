package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.repository.CodeSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists {@code ml.code_samples} records during PR scans.
 *
 * <p>Idempotent on
 * {@code (pull_request_id, commit_sha, file_path, new_start, content_sha256)}.</p>
 */
@Service
public class CodeSampleService {

    private static final Logger log = LoggerFactory.getLogger(CodeSampleService.class);

    private final CodeSampleRepository repository;
    private final SecretRedactor redactor;

    public CodeSampleService(CodeSampleRepository repository, SecretRedactor redactor) {
        this.repository = repository;
        this.redactor = redactor;
    }

    /**
     * Persist a code sample derived from a redacted PR hunk.
     *
     * <p>If a sample with the same uniqueness key already exists,
     * that existing row is returned unchanged.</p>
     */
    @Transactional
    public CodeSample persistSample(PersistRequest req) {
        String rawHunk = req.rawHunk == null ? "" : req.rawHunk;
        String redactedRaw = redactor.redact(rawHunk);
        String addedCode = req.addedCode == null ? "" : redactor.redact(req.addedCode);
        String contextCode = req.contextCode == null ? "" : redactor.redact(req.contextCode);

        String hashInput = addedCode.isEmpty() ? redactedRaw : addedCode;
        String sha = sha256Hex(hashInput);

        Optional<CodeSample> existing = repository
                .findByPullRequestIdAndCommitShaAndFilePathAndNewStartAndContentSha256(
                        req.pullRequestId, req.commitSha, req.filePath, req.newStart, sha);
        if (existing.isPresent()) {
            return existing.get();
        }

        CodeSample sample = new CodeSample();
        sample.setId(UUID.randomUUID());
        sample.setRepositoryId(req.repositoryId);
        sample.setPullRequestId(req.pullRequestId);
        sample.setCommitSha(req.commitSha);
        sample.setFilePath(req.filePath);
        sample.setLanguage(req.language);
        sample.setOldStart(req.oldStart);
        sample.setOldCount(req.oldCount);
        sample.setNewStart(req.newStart);
        sample.setNewCount(req.newCount);
        sample.setRawHunk(redactedRaw);
        sample.setAddedCode(addedCode.isEmpty() ? null : addedCode);
        sample.setContextCode(contextCode.isEmpty() ? null : contextCode);
        sample.setContentSha256(sha);
        sample.setGroupKey(req.repositoryId + ":" + req.pullRequestId);
        sample.setSourceType(req.sourceType == null ? "pr_diff" : req.sourceType);
        sample.setRedactionVersion(redactor.version());
        sample.setCreatedAt(OffsetDateTime.now());

        try {
            return repository.save(sample);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Race with a concurrent webhook delivery. Re-read.
            return repository
                    .findByPullRequestIdAndCommitShaAndFilePathAndNewStartAndContentSha256(
                            req.pullRequestId, req.commitSha, req.filePath, req.newStart, sha)
                    .orElseThrow(() -> ex);
        }
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Parameters needed to persist a code sample. Validation is
     * deliberately minimal — the caller is responsible for the
     * input contract.
     */
    public record PersistRequest(
            UUID repositoryId,
            UUID pullRequestId,
            String commitSha,
            String filePath,
            String language,
            int oldStart,
            int oldCount,
            int newStart,
            int newCount,
            String rawHunk,
            String addedCode,
            String contextCode,
            String sourceType
    ) {
    }
}