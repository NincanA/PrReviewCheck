package com.github.prreviewbot.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.prreviewbot.config.GitHubConfig;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for interacting with GitHub API
 */
@Service
public class GitHubService {
    
    private static final Logger logger = LoggerFactory.getLogger(GitHubService.class);
    
    private final GitHubConfig githubConfig;
    private String token;
    
    @Autowired
    public GitHubService(GitHubConfig githubConfig) {
        this.githubConfig = githubConfig;
    }
    
    /**
     * Creates authenticated GitHub client for app installation
     */
    public GitHub createGitHubClient(long installationId)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {

        String pemFilePath = "/Users/ayush.bemera/github-pr-review-bot/src/main/resources/privateKey_pkcs8.pem";

        // Read and clean up PEM file
        String privateKeyPem = new String(Files.readAllBytes(Paths.get(pemFilePath)))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        // Decode and create PrivateKey object
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyPem);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

        // Timestamps
        Instant now = Instant.now();
        Instant issuedAt = now.minusSeconds(60); // 60 sec ago for clock drift
        Instant expiration = now.plusSeconds(600); // 10 minutes max

        // Build JWT
        String jwt = Jwts.builder()
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiration))
                .setIssuer(String.valueOf(2188473))  // GitHub App ID goes here
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();

        System.out.println("Generated JWT:\n" + jwt);

        // 1. Build GitHub client as App
        GitHub gitHubApp = new GitHubBuilder()
                .withJwtToken(jwt)
                .build();

        // 2. Use the App-authenticated client to get the installation and create an access token
        GHAppInstallation installation = gitHubApp.getApp().getInstallationById(installationId);
        GHAppInstallationToken instToken = installation.createToken().create();
        token = instToken.getToken();

        System.out.println("Generated Installation Token:\n" + token);

        // 3. Build GitHub client authenticated as the installation
        GitHub gitHubInstallation = new GitHubBuilder()
                .withAppInstallationToken(token)
                .build();

        return gitHubInstallation;
    }
    
    /**
     * Fetches PR diff for analysis
     */
    public PagedIterable<GHPullRequestFileDetail> getPrDiff(GitHub github, String owner, String repo, int prNumber) throws IOException {
        logger.error("owner details: {} + repo details: {}\n", owner ,repo);
        GHRepository repository = github.getRepository(owner + "/" + repo);
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);
        return pullRequest.listFiles();
    }
    
    /**
     * Gets changed files in a PR
     */
    public List<?> getChangedFiles(GitHub github, String owner, String repo, int prNumber) throws IOException {
        GHRepository repository = github.getRepository(owner + "/" + repo);
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);
        
        return pullRequest.listFiles().toList();
    }
    
    /**
     * Posts a review comment on a specific line
     */
    public void postReviewComment(GitHub github, String owner, String repo, int prNumber, 
                                 String body, String path, int line, String side) throws IOException {
        GHRepository repository = github.getRepository(owner + "/" + repo);
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);
        
        GHPullRequestReviewComment comment = pullRequest.createReviewComment(
                body, pullRequest.getHead().getSha(), path, line);
        
        logger.info("Posted review comment: {}", comment.getHtmlUrl());
    }
    
    /**
     * Posts a reply to an existing review comment
     */
    public void replyToReviewComment(GitHub github, long commentId, String body) throws IOException {
        // Note: This method needs to be implemented based on the actual GitHub API
        // For now, we'll log the action
        logger.info("Would reply to comment {} with: {}", commentId, body);
    }
    
    /**
     * Creates JWT token for GitHub App authentication
     */
    private String createJwtToken() {
        try {
            // This is a simplified version - in production, you'd use a proper JWT library
            // For now, we'll return a placeholder token
            logger.error("Creating JWT token for app ID: {}", githubConfig.getApp().getPrivateKey());
            return "placeholder-jwt-token";
        } catch (Exception e) {
            logger.error("Error creating JWT token", e);
            throw new RuntimeException("Failed to create JWT token", e);
        }
    }
    
    /**
     * Extracts repository information from PR
     */
    public RepositoryInfo extractRepositoryInfo(JsonNode pullRequest) {
        String htmlUrl = pullRequest.get("html_url").asText();
        String[] parts = htmlUrl.split("/");
        String owner = parts[3];
        String repo = parts[4];
        int prNumber = pullRequest.get("number").asInt();
        
        return new RepositoryInfo(owner, repo, prNumber);
    }
    
    /**
     * Data class for repository information
     */
    public static class RepositoryInfo {
        private final String owner;
        private final String repo;
        private final int prNumber;
        
        public RepositoryInfo(String owner, String repo, int prNumber) {
            this.owner = owner;
            this.repo = repo;
            this.prNumber = prNumber;
        }
        
        public String getOwner() { return owner; }
        public String getRepo() { return repo; }
        public int getPrNumber() { return prNumber; }
    }

    public String getToken() {
        return token;
    }
}
