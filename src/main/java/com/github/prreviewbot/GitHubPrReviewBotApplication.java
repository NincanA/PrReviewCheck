package com.github.prreviewbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main Spring Boot application for GitHub PR Review Bot
 */
@SpringBootApplication
@EnableAsync
public class GitHubPrReviewBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitHubPrReviewBotApplication.class, args);
    }
}
