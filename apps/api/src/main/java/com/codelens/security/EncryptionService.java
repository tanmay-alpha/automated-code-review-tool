package com.codelens.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for sensitive at-rest fields
 * (GitHub OAuth access tokens, webhook secrets).
 *
 * <p>Ciphertext format: base64( {@code IV || ciphertext || GCM_TAG} ) where
 * IV is 12 bytes (the GCM standard) and the tag is the last 16 bytes of
 * the output. The key comes from the {@code app.encryption.key} property
 * — 32 raw bytes, or base64-encoded 32 bytes.</p>
 *
 * <p>{@link #encrypt(String)} and {@link #decrypt(String)} are inverses;
 * a different IV is generated per call so encrypting the same plaintext
 * twice yields different ciphertext.</p>
 */
@Service
public class EncryptionService {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final String configuredKey;
    private SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${app.encryption.key}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    @PostConstruct
    void init() {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("app.encryption.key must be configured");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException notBase64) {
            raw = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "app.encryption.key must decode to exactly 32 bytes for AES-256");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ciphertextWithTag.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ciphertextWithTag, 0, out, IV_LEN, ciphertextWithTag.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            if (all.length < IV_LEN + TAG_BITS / 8) {
                throw new IllegalArgumentException("Ciphertext too short");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ct);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
