package com.codelens.service;

import com.codelens.config.GitHubConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubServiceTest {

    @Test
    void getOAuthRedirectUrl_encodesQueryParameters() {
        GitHubConfig config = new GitHubConfig();
        config.setClientId("test-client");
        config.setClientSecret("test-secret");
        config.setOauthRedirectUri("http://localhost:8080/api/auth/callback");

        GitHubService service = new GitHubService(WebClient.builder(), config);

        String url = service.getOAuthRedirectUrl();

        assertThat(URI.create(url)).isNotNull();
        assertThat(url).contains("client_id=test-client");
        assertThat(url).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fauth%2Fcallback");
        assertThat(url).contains("scope=read%3Auser+repo");
    }
}
