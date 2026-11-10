package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.MlFinding;
import com.automatedcodereviewtool.entity.Repository;

import java.util.List;

/**
 * Per-repository review settings as seen by the webhook pipeline. Mirrors
 * the method shapes of the repository-settings policy, so that policy can
 * implement this interface directly.
 *
 * <p>The publication decisions are captured on the publication row when the
 * review is persisted, so a later settings change does not alter a queued
 * publication.</p>
 */
public interface ReviewPipelinePolicy {

    /** Worker findings the repository's settings allow, in their original order. */
    List<MlFinding> apply(Repository repo, List<MlFinding> findings);

    /** Post (and keep updating) the sticky summary comment. */
    boolean postComments(Repository repo);

    /** Also post an inline review on added lines; only honoured when {@link #postComments} is true. */
    boolean inlineComments(Repository repo);
}
