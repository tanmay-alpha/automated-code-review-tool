package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.entity.CodeSample;
import com.automatedcodereviewtool.repository.CodeSampleRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeSampleSecretPersistenceTest {

    @Test
    void structuralHashUsesOriginalButOnlyRedactedContentIsPersisted() {
        CodeSampleRepository repository = mock(CodeSampleRepository.class);
        when(repository.findByPullRequestIdAndCommitShaAndFilePathAndNewStartAndContentSha256(
                any(), any(), any(), anyInt(), any())).thenReturn(Optional.empty());
        when(repository.save(any(CodeSample.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CodeSampleService service = new CodeSampleService(repository, new SecretRedactor());
        UUID repoId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();
        String raw = "@@ -1 +1 @@\n-password = old\n+password = \"live-secret-value\"";

        CodeSample persisted = service.persistSample(new CodeSampleService.PersistRequest(
                repoId, prId, "a".repeat(40), "src/app.py", "python",
                1, 1, 1, 1, raw, "password = \"live-secret-value\"", "", "pr_diff"));

        assertThat(persisted.getRawHunk()).doesNotContain("live-secret-value");
        assertThat(persisted.getAddedCode()).doesNotContain("live-secret-value");
        assertThat(persisted.getHunkSha256()).isEqualTo(
                CodeSampleService.sha256Hex(CodeSampleService.canonicalizeRawHunk(raw)));
        assertThat(persisted.getContentSha256()).isEqualTo(
                CodeSampleService.sha256Hex(persisted.getAddedCode()));
    }
}
