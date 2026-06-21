package com.codelens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CodeLens API gateway entry point.
 *
 * Hosts the OAuth flow, webhook handlers, and proxies review requests
 * to the ml-worker service.
 */
@SpringBootApplication
public class CodeLensApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeLensApplication.class, args);
    }
}
