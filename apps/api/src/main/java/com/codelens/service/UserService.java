package com.codelens.service;

import com.codelens.dto.GitHubUserInfo;
import com.codelens.entity.User;
import com.codelens.repository.UserRepository;
import com.codelens.security.EncryptionService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service over the {@code users} table.
 *
 * <p>{@link #findOrCreateFromGitHub(GitHubUserInfo, String)} is called from
 * the OAuth callback. It upserts the user row and AES-encrypts the GitHub
 * access token before storing it.</p>
 *
 * <p>{@link #generateApiKey(UUID)} mints a fresh {@code cl_live_<hex>}, hashes
 * it with BCrypt, stores the prefix for display + lookup, and returns the
 * raw key — the user only ever sees it once.</p>
 */
@Service
public class UserService {

    static final String API_KEY_PREFIX = "cl_live_";
    private static final int API_KEY_HEX_LEN = 32;

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository userRepository,
                       EncryptionService encryptionService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User findOrCreateFromGitHub(GitHubUserInfo info, String accessToken) {
        String encrypted = encryptionService.encrypt(accessToken);
        return userRepository.findByGithubId(info.id())
                .map(existing -> {
                    existing.setAccessToken(encrypted);
                    existing.setGithubUsername(info.login());
                    existing.setAvatarUrl(info.avatarUrl());
                    return userRepository.save(existing);
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .githubId(info.id())
                        .githubUsername(info.login())
                        .avatarUrl(info.avatarUrl())
                        .accessToken(encrypted)
                        .build()));
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    /**
     * Generate a new API key for the user. Returns the raw key — the caller
     * must show it to the user once and then forget it.
     */
    @Transactional
    public String generateApiKey(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
        String rawKey = API_KEY_PREFIX + randomHex(API_KEY_HEX_LEN);
        user.setApiKeyHash(passwordEncoder.encode(rawKey));
        user.setApiKeyPrefix(rawKey.substring(0, 12));
        userRepository.save(user);
        return rawKey;
    }

    private String randomHex(int hexChars) {
        byte[] bytes = new byte[(hexChars + 1) / 2];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(hexChars);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        // In case rounding produced one extra char (odd hexChars), trim.
        return sb.substring(0, hexChars);
    }
}
