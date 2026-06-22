package com.codelens.service;

import com.codelens.entity.ApiKey;
import com.codelens.entity.User;
import com.codelens.repository.ApiKeyRepository;
import com.codelens.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Mints, lists, and revokes API keys.
 *
 * <p>Format: {@code cl_live_} + 32 lowercase hex chars = 64 hex chars
 * after the prefix, 256 bits of entropy. The prefix
 * {@code cl_live_} + first 8 hex chars is stored plaintext (for
 * O(1) lookup in {@link com.codelens.security.ApiKeyAuthFilter});
 * the rest is bcrypt-hashed and never returned after creation.</p>
 */
@Service
public class ApiKeyService {

    public static final String KEY_PREFIX_LITERAL = "cl_live_";
    private static final int HEX_BYTES = 16;       // 32 hex chars after the literal
    private static final int PREFIX_HEX_CHARS = 8; // first 8 hex chars of the key portion

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         UserRepository userRepository,
                         PasswordEncoder passwordEncoder) {
        this.apiKeyRepository = apiKeyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CreatedApiKey createKey(User caller, String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new IllegalStateException("User " + caller.getId() + " not found"));

        byte[] raw = new byte[HEX_BYTES];
        secureRandom.nextBytes(raw);
        String hex = HexFormat.of().formatHex(raw);
        String keyValue = KEY_PREFIX_LITERAL + hex;
        String prefix = keyValue.substring(0, KEY_PREFIX_LITERAL.length() + PREFIX_HEX_CHARS);

        ApiKey apiKey = ApiKey.builder()
                .user(user)
                .label(label)
                .prefix(prefix)
                .keyHash(passwordEncoder.encode(keyValue))
                .revoked(false)
                .build();
        ApiKey saved = apiKeyRepository.save(apiKey);
        return new CreatedApiKey(saved, keyValue);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> listKeys(User caller) {
        return apiKeyRepository.findAllByUserId(caller.getId());
    }

    @Transactional
    public void revokeKey(User caller, UUID keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Key " + keyId + " not found"));
        if (!key.getUser().getId().equals(caller.getId())) {
            throw new jakarta.persistence.EntityNotFoundException("Key " + keyId + " not found");
        }
        key.setRevoked(true);
        apiKeyRepository.save(key);
    }

    /**
     * Look up a key by its prefix. Returns {@code null} when no
     * key matches — the filter then 403s. Used by
     * {@code ApiKeyAuthFilter}; package-private would be cleaner but
     * Spring needs the bean to be public.
     */
    public ApiKey findByPrefix(String prefix) {
        return apiKeyRepository.findByPrefix(prefix).orElse(null);
    }

    /**
     * Verify a candidate key string against the stored bcrypt hash.
     * Returns {@code true} only when the prefix matches <em>and</em>
     * the bcrypt verifies <em>and</em> the key is not revoked.
     */
    public boolean verify(ApiKey key, String candidate) {
        if (key == null || key.isRevoked()) return false;
        if (candidate == null || !candidate.startsWith(key.getPrefix())) return false;
        return passwordEncoder.matches(candidate, key.getKeyHash());
    }

    /** Stamp the key as used-now. Called after a successful request. */
    public void markUsed(ApiKey key) {
        key.setLastUsedAt(Instant.now());
        apiKeyRepository.save(key);
    }

    public record CreatedApiKey(ApiKey apiKey, String plaintext) {
    }
}