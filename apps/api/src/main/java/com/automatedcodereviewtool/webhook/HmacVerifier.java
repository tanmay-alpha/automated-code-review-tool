package com.automatedcodereviewtool.webhook;

import com.automatedcodereviewtool.entity.Repository;
import com.automatedcodereviewtool.repository.RepositoryRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the {@code X-Hub-Signature-256} header on incoming GitHub
 * webhook payloads.
 *
 * <p>GitHub computes HMAC-SHA256 over the raw request body using the
 * shared secret it was given at webhook installation, and sends the
 * result hex-encoded with a {@code sha256=} prefix. We re-derive the
 * digest over the same raw bytes (never a decoded/re-encoded string) and
 * compare with {@link MessageDigest#isEqual(byte[], byte[])}, a
 * constant-time comparison.</p>
 *
 * <p>The shared secret is stored encrypted in {@code repositories.webhook_secret};
 * we decrypt it here with {@link EncryptionService} before use.</p>
 */
@Component
public class HmacVerifier {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final RepositoryRepository repositoryRepository;
    private final EncryptionService encryptionService;

    public HmacVerifier(RepositoryRepository repositoryRepository,
                        EncryptionService encryptionService) {
        this.repositoryRepository = repositoryRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * @param payload         the raw request body bytes, exactly as received
     * @param signatureHeader value of the {@code X-Hub-Signature-256} header
     * @param repoGithubId    the GitHub repo id from the payload
     * @return true if the signature matches
     * @throws HmacVerificationException if we have no secret on file for the repo
     */
    public boolean verify(byte[] payload, String signatureHeader, String repoGithubId) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        Long githubId = parseLong(repoGithubId);
        if (githubId == null) {
            return false;
        }
        Repository repo = repositoryRepository.findByGithubId(githubId)
                .orElseThrow(() -> new HmacVerificationException(
                        "no repository on file for github id " + repoGithubId));
        if (repo.getWebhookSecret() == null || repo.getWebhookSecret().isBlank()) {
            throw new HmacVerificationException(
                    "repository " + repoGithubId + " has no webhook secret configured");
        }
        String secret = encryptionService.decrypt(repo.getWebhookSecret());
        String computed = SIGNATURE_PREFIX + hmacHex(secret, payload);

        // MessageDigest.isEqual is constant-time over the longer of the two arrays,
        // so an attacker can't probe signature bytes by timing.
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] digest = mac.doFinal(payload);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
