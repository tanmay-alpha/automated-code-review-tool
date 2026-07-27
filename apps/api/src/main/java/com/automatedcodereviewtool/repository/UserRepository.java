package com.codelens.repository;

import com.codelens.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByGithubId(Long githubId);

    Optional<User> findByGithubUsername(String githubUsername);

    Optional<User> findByApiKeyPrefix(String apiKeyPrefix);
}
