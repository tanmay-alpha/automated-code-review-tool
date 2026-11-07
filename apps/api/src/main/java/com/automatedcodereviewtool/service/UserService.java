package com.automatedcodereviewtool.service;

import com.automatedcodereviewtool.dto.GitHubUserInfo;
import com.automatedcodereviewtool.entity.User;
import com.automatedcodereviewtool.repository.UserRepository;
import com.automatedcodereviewtool.security.EncryptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Application service over the {@code users} table.
 *
 * <p>{@link #findOrCreateFromGitHub(GitHubUserInfo, String)} is called from
 * the OAuth callback. It upserts the user row and AES-encrypts the GitHub
 * access token before storing it.</p>
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;

    public UserService(UserRepository userRepository,
                       EncryptionService encryptionService) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
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
}
