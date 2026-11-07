package com.automatedcodereviewtool.service;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** {@link ReviewServiceScenarios} on the H2 test database. */
@SpringBootTest(properties = {
        "app.webhook.recovery-delay-ms=3600000",
        "app.outbox.poll-delay-ms=3600000"
})
@ActiveProfiles("test")
class ReviewServiceIntegrationTest extends ReviewServiceScenarios {
}
