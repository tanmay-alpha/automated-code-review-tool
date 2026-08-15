package com.automatedcodereviewtool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One model-ready code-change unit (normally one PR diff hunk).
 *
 * <p>Stored in the {@code ml.code_samples} table (V6). Insertion
 * must be idempotent on
 * {@code (pull_request_id, commit_sha, file_path, new_start, content_sha256)}.</p>
 */
@Entity
@Table(
        name = "code_samples",
        schema = "ml",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_code_samples_pr_commit_file_start_hash",
                        columnNames = {
                                "pull_request_id",
                                "commit_sha",
                                "file_path",
                                "new_start",
                                "content_sha256"
                        }
                )
        }
)
public class CodeSample {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "pull_request_id", nullable = false)
    private UUID pullRequestId;

    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    @Column(name = "language", nullable = false, length = 30)
    private String language;

    @Column(name = "old_start", nullable = false)
    private int oldStart;

    @Column(name = "old_count", nullable = false)
    private int oldCount;

    @Column(name = "new_start", nullable = false)
    private int newStart;

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "raw_hunk", nullable = false, columnDefinition = "TEXT")
    private String rawHunk;

    @Column(name = "added_code", columnDefinition = "TEXT")
    private String addedCode;

    @Column(name = "context_code", columnDefinition = "TEXT")
    private String contextCode;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "hunk_sha256", nullable = false, length = 64)
    private String hunkSha256;

    @Column(name = "group_key", nullable = false, length = 255)
    private String groupKey;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "redaction_version", nullable = false, length = 30)
    private String redactionVersion;

    @Column(name = "repository_visibility", nullable = false, length = 20)
    private String repositoryVisibility = "private";

    @Column(name = "license_spdx", length = 30)
    private String licenseSpdx;

    @Column(name = "data_use_status", nullable = false, length = 40)
    private String dataUseStatus = "quarantined_unknown_license";

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (repositoryVisibility == null) {
            repositoryVisibility = "private";
        }
        if (dataUseStatus == null) {
            dataUseStatus = "quarantined_unknown_license";
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public UUID getPullRequestId() { return pullRequestId; }
    public void setPullRequestId(UUID pullRequestId) { this.pullRequestId = pullRequestId; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public int getOldStart() { return oldStart; }
    public void setOldStart(int oldStart) { this.oldStart = oldStart; }
    public int getOldCount() { return oldCount; }
    public void setOldCount(int oldCount) { this.oldCount = oldCount; }
    public int getNewStart() { return newStart; }
    public void setNewStart(int newStart) { this.newStart = newStart; }
    public int getNewCount() { return newCount; }
    public void setNewCount(int newCount) { this.newCount = newCount; }
    public String getRawHunk() { return rawHunk; }
    public void setRawHunk(String rawHunk) { this.rawHunk = rawHunk; }
    public String getAddedCode() { return addedCode; }
    public void setAddedCode(String addedCode) { this.addedCode = addedCode; }
    public String getContextCode() { return contextCode; }
    public void setContextCode(String contextCode) { this.contextCode = contextCode; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getHunkSha256() { return hunkSha256; }
    public void setHunkSha256(String hunkSha256) { this.hunkSha256 = hunkSha256; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getRedactionVersion() { return redactionVersion; }
    public void setRedactionVersion(String redactionVersion) { this.redactionVersion = redactionVersion; }
    public String getRepositoryVisibility() { return repositoryVisibility; }
    public void setRepositoryVisibility(String repositoryVisibility) { this.repositoryVisibility = repositoryVisibility; }
    public String getLicenseSpdx() { return licenseSpdx; }
    public void setLicenseSpdx(String licenseSpdx) { this.licenseSpdx = licenseSpdx; }
    public String getDataUseStatus() { return dataUseStatus; }
    public void setDataUseStatus(String dataUseStatus) { this.dataUseStatus = dataUseStatus; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
