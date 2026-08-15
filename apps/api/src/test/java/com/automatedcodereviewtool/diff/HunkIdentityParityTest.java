package com.automatedcodereviewtool.diff;

import com.automatedcodereviewtool.service.CodeSampleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class HunkIdentityParityTest {

    @Test
    void javaParserAndHasherMatchSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("hunk_identity_cases.json")) {
            assertThat(stream).as("shared hunk identity fixture").isNotNull();
            JsonNode fixture = mapper.readTree(stream);
            for (JsonNode testCase : fixture.path("cases")) {
                String rawHunk = testCase.path("rawHunk").asText();
                String expected = testCase.path("hunkSha256").asText();

                assertThat(CodeSampleService.sha256Hex(
                        CodeSampleService.canonicalizeRawHunk(rawHunk)))
                        .as(testCase.path("name").asText())
                        .isEqualTo(expected);

                String crlf = rawHunk.replace("\n", "\r\n") + "\r\n";
                assertThat(CodeSampleService.sha256Hex(
                        CodeSampleService.canonicalizeRawHunk(crlf)))
                        .isEqualTo(expected);

                String diff = "diff --git a/demo.js b/demo.js\n"
                        + "--- a/demo.js\n+++ b/demo.js\n" + rawHunk;
                HunkParser.FileHunk parsed = HunkParser.parse(diff)
                        .files().get(0).hunks().get(0);
                assertThat(parsed.rawHunk()).isEqualTo(rawHunk);
            }
        }
    }
}
