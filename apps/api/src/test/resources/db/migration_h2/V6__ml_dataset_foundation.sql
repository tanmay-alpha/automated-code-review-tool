CREATE SCHEMA IF NOT EXISTS ml;

CREATE TABLE IF NOT EXISTS ml.code_samples (
    id                  UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    repository_id       UUID         NOT NULL REFERENCES PUBLIC.repositories(id) ON DELETE CASCADE,
    pull_request_id     UUID         NOT NULL REFERENCES PUBLIC.pull_requests(id) ON DELETE CASCADE,
    commit_sha          VARCHAR(40)  NOT NULL,
    file_path           TEXT         NOT NULL,
    language            VARCHAR(30)  NOT NULL,
    old_start           INT          NOT NULL,
    old_count           INT          NOT NULL,
    new_start           INT          NOT NULL,
    new_count           INT          NOT NULL,
    raw_hunk            TEXT         NOT NULL,
    added_code          TEXT,
    context_code        TEXT,
    content_sha256      VARCHAR(64)  NOT NULL,
    group_key           VARCHAR(255) NOT NULL,
    source_type         VARCHAR(30)  NOT NULL,
    redaction_version   VARCHAR(30)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_code_sample_source_type
        CHECK (source_type IN ('pr_diff', 'file_scan', 'import')),

    CONSTRAINT uq_code_samples_content_sha UNIQUE (content_sha256)
);

CREATE TABLE IF NOT EXISTS ml.annotations (
    id              UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    code_sample_id  UUID         NOT NULL REFERENCES ml.code_samples(id) ON DELETE CASCADE,
    anti_pattern_id VARCHAR(80)  NOT NULL,
    label_state     VARCHAR(20)  NOT NULL,
    line_start      INT          NOT NULL,
    line_end        INT          NOT NULL,
    source          VARCHAR(50)  NOT NULL,
    confidence      NUMERIC(4,3) NOT NULL,
    reviewer_user_id UUID,
    rationale       TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_annotation_label_state
        CHECK (label_state IN ('positive', 'negative', 'neutral'))
);

CREATE TABLE IF NOT EXISTS ml.dataset_versions (
    id          UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    version     VARCHAR(50)  NOT NULL,
    description TEXT,
    split_ratio VARCHAR(20)  NOT NULL DEFAULT '70/15/15',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_dataset_versions_version UNIQUE (version)
);

CREATE TABLE IF NOT EXISTS ml.dataset_items (
    id                UUID      DEFAULT RANDOM_UUID() PRIMARY KEY,
    dataset_version_id UUID     NOT NULL REFERENCES ml.dataset_versions(id) ON DELETE CASCADE,
    code_sample_id    UUID      NOT NULL REFERENCES ml.code_samples(id) ON DELETE CASCADE,
    split             VARCHAR(10) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_dataset_items_version_sample
        UNIQUE (dataset_version_id, code_sample_id)
);
