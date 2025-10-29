package com.github.prreviewbot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "github.app.id=test",
    "github.app.private-key=test",
    "github.webhook-secret=test",
    "llm.provider=openai",
    "openai.api-key=test"
})
class GitHubPrReviewBotApplicationTests {

    @Test
    void contextLoads() {
        // This test ensures the Spring context loads successfully
    }
}
