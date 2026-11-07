package com.automatedcodereviewtool.webhook;

import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HmacVerifierTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private HmacVerifier verifier;

    @BeforeEach
    void setUp() {
        RepositoryRepository repos = mock(RepositoryRepository.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(repos.findByGithubId(123L)).thenReturn(Optional.of(
                Repository.builder().githubId(123L).webhookSecret("encrypted").build()));
        when(encryption.decrypt("encrypted")).thenReturn(SECRET);
        verifier = new HmacVerifier(repos, encryption);
    }

    @Test
    void verifiesPayloadWithNonAsciiAndEscapedUnicode() throws Exception {
        // Raw UTF-8 multi-byte characters and a JSON \\u escape, as GitHub sends them.
        byte[] payload = "{\"title\":\"Café ✓ — \\u00e9 日本\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(payload, sign(payload), "123")).isTrue();
    }

    @Test
    void rejectsSignatureOfDifferentBytes() throws Exception {
        byte[] signed = "{\"title\":\"\\u00e9\"}".getBytes(StandardCharsets.UTF_8);
        // Same text after JSON decoding, different bytes on the wire.
        byte[] received = "{\"title\":\"é\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(received, sign(signed), "123")).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeader() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(payload, null, "123")).isFalse();
        assertThat(verifier.verify(payload, "sha1=abc", "123")).isFalse();
    }

    private static String sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
