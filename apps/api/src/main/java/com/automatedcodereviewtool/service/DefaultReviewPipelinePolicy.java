package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.entity.Repository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Repository-settings defaults (all findings, summary comment on, inline
 * review off), used until per-repository settings are wired in.
 */
@Component
public class DefaultReviewPipelinePolicy implements ReviewPipelinePolicy {

    @Override
    public List<MlFinding> apply(Repository repo, List<MlFinding> findings) {
        return findings == null ? List.of() : findings;
    }

    @Override
    public boolean postComments(Repository repo) {
        return true;
    }

    @Override
    public boolean inlineComments(Repository repo) {
        return false;
    }
}
